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
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 简单的远端图片组件（不引第三方图片库）：HTTP 拉取 + Bitmap 解码 + 三态展示（加载中/成功/失败）。
 *
 * 用途：事故现场照片（作业人员上报的快照图片由服务端 /media 回放）等；
 * 带内存命中（同一 URL 组合内不重复请求）。接入真实媒体后可直接沿用。
 */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (bitmap == null) {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 5000
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            bitmap = bmp
            failed = bmp == null
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Text(
                text = "图片加载失败",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Text(
                text = "加载中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
