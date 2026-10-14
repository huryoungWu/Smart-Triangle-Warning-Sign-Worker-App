package com.example.myapplication.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * MJPEG 视频流组件（HTTP `multipart/x-mixed-replace`，无第三方库）。
 *
 * 用途：任务执行页「实时视频」与事故详情页「事故视频」——联调阶段均指向服务端
 * **同一路视频流**（tools/mock-server 提供时间驱动的动画帧；接真实视频源后本组件不变）。
 *
 * 行为：断流自动重连（1s 退避）；离开页面（Composable 销毁）自动断开；帧逐帧解码为 Bitmap。
 */
@Composable
fun MjpegStreamView(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var frame by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val frames = Channel<Result<Bitmap>>(Channel.CONFLATED)
        val reader = launch(Dispatchers.IO) {
            while (isActive) {
                val result = runCatching { readMjpeg(url) { bmp -> frames.trySend(Result.success(bmp)) } }
                result.onFailure { frames.trySend(Result.failure(it)) }
                delay(1000) // 断流重连退避
            }
        }
        for (result in frames) {
            result.onSuccess {
                frame = it
                failed = false
            }.onFailure {
                if (frame == null) failed = true // 尚未出图时提示不可用；已出图则保持最后一帧
            }
        }
        reader.cancel()
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = frame
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Text(
                text = "视频流不可用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Text(
                text = "连接视频流…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 读取 MJPEG 流（阻塞；每解出一帧回调一次，流结束/断开才返回）。整体在 IO 协程内调用。 */
private suspend fun readMjpeg(url: String, onFrame: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        conn.setRequestProperty("Accept", "multipart/x-mixed-replace")
        val boundary = Regex("boundary=(.+?)(;|$)")
            .find(conn.contentType.orEmpty())?.groupValues?.get(1) ?: "frame"
        val boundaryBytes = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val headerEnd = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        conn.inputStream.use { raw ->
            val input = BufferedInputStream(raw, 64 * 1024)
            if (readUntil(input, boundaryBytes, capture = false) == null) return@use // 找首帧分界
            while (true) {
                coroutineContext.ensureActive() // 阻塞读中唯一取消检查点（每帧一次）
                if (readUntil(input, headerEnd, capture = false) == null) return@use // 跳过帧头
                val data = readUntil(input, boundaryBytes, capture = true) ?: return@use // 读到下一分界
                // 帧数据尾部 protocol 会带 \r\n，去掉后再解码
                val size = if (
                    data.size >= 2 &&
                    data[data.size - 2] == '\r'.code.toByte() &&
                    data[data.size - 1] == '\n'.code.toByte()
                ) {
                    data.size - 2
                } else {
                    data.size
                }
                BitmapFactory.decodeByteArray(data, 0, size)?.let(onFrame)
            }
        }
    } finally {
        conn.disconnect()
    }
}

/**
 * 从流中读取，直到出现字节序列 [marker]（消费 marker；读到流结束未命中返回 null）。
 * [capture]=true 时返回 marker 之前的全部字节（不含 marker）；false 时仅丢弃、返回空数组。
 */
private fun readUntil(input: InputStream, marker: ByteArray, capture: Boolean): ByteArray? {
    val out = ByteArrayOutputStream()
    val tail = ByteArray(marker.size)
    var filled = 0
    while (true) {
        val b = input.read()
        if (b == -1) return null
        val byte = b.toByte()
        if (capture) out.write(b)
        if (filled < tail.size) {
            tail[filled++] = byte
        } else {
            System.arraycopy(tail, 1, tail, 0, tail.size - 1)
            tail[tail.size - 1] = byte
        }
        if (filled == tail.size && tail.contentEquals(marker)) {
            return if (capture) out.toByteArray().copyOf(out.size() - marker.size) else ByteArray(0)
        }
    }
}
