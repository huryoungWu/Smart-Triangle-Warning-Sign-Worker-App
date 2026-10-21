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
import androidx.compose.ui.text.style.TextAlign
import com.example.myapplication.core.ui.permission.rememberLocalNetworkPermissionState
import com.example.myapplication.core.util.parseUdpEndpoint
import com.example.myapplication.core.video.UdpVideoClient
import com.example.myapplication.core.video.UdpVideoError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.URI

/**
 * UDP 实时视频组件（与原 MjpegStreamView 相同的 UI 结构/状态文案）。
 *
 * URL 形如 `udp://host:8080/api/v1/device/video/stream?device_code=TC-001`；
 * 协议/分片/重组见 [com.example.myapplication.core.video.UdpVideoProtocol]。
 *
 * 行为：
 * - 进入页面建立 UDP 订阅；离开页面（Composable 销毁）自动 unsub + 断开；
 * - 断流自动重连（1s 退避）；
 * - Android 17 未授予本地网络权限时显示可读提示，不无限重连。
 */
@Composable
fun UdpVideoStreamView(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var frame by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var errorText by remember(url) { mutableStateOf<String?>(null) }
    val permission = rememberLocalNetworkPermissionState()
    val endpoint = remember(url) { parseUdpEndpoint(url) }
    val target = remember(url) {
        runCatching { URI(url) }.getOrNull()?.let { uri ->
            buildString {
                append(uri.rawPath.orEmpty().ifBlank { "/" })
                uri.rawQuery?.takeIf { it.isNotBlank() }?.let { append('?').append(it) }
            }
        }
    }

    LaunchedEffect(url, permission.granted) {
        if (!permission.granted) {
            errorText = "视频流不可用（需要本地网络权限）"
            failed = true
            return@LaunchedEffect
        }
        val ep = endpoint
        val requestTarget = target
        if (ep == null || requestTarget == null) {
            errorText = "视频流不可用（地址不合法）"
            failed = true
            return@LaunchedEffect
        }

        // 权限刚授予/重连时先回到「连接视频流…」，避免旧错误文案一直挂着
        errorText = null
        failed = frame == null

        val frames = Channel<Bitmap>(Channel.CONFLATED)
        val errors = Channel<String>(Channel.CONFLATED)
        val worker = launch(Dispatchers.IO) {
            UdpVideoClient(
                endpoint = ep,
                requestTarget = requestTarget,
                onFrame = { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { frames.trySend(it) }
                },
                onError = { error ->
                    errors.trySend(
                        when (error) {
                            is UdpVideoError.Permission -> "视频流不可用（需要本地网络权限）"
                            is UdpVideoError.Server -> "视频流不可用（${error.message}）"
                            is UdpVideoError.Network -> "视频流不可用"
                        },
                    )
                },
            ).run()
        }

        try {
            launch {
                for (bmp in frames) {
                    frame = bmp
                    failed = false
                    errorText = null
                }
            }
            for (message in errors) {
                if (frame == null) {
                    failed = true
                    errorText = message
                }
            }
        } finally {
            worker.cancel()
        }
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
                text = errorText ?: "视频流不可用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            else -> Text(
                text = "连接视频流…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
