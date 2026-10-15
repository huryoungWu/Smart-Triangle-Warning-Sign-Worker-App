package com.example.myapplication.core.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * 实时视频统一入口（按 URL 形态**智能分发**）：
 * - **编码视频流**（`.m3u8` HLS / `.flv` HTTP-FLV / `.mp4`）→ ExoPlayer（media3，硬解播放）；
 * - 其余（Mock 服务端的 `multipart/x-mixed-replace` MJPEG 帧流）→ 自研 [MjpegStreamView] 逐帧解码。
 *
 * 对接真实后端时：后端按《后端接口设计文档》第 11 节提供**编码流**地址
 * （推荐 HTTP-FLV 低延迟 1~3s / HLS 兼容好 5~10s），App 侧仅 URL 变化，本组件无需改动。
 */
@Composable
fun VideoStreamView(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (isEncodedStreamUrl(url)) {
        ExoPlayerStreamView(url = url, contentDescription = contentDescription, modifier = modifier)
    } else {
        MjpegStreamView(url = url, contentDescription = contentDescription, modifier = modifier)
    }
}

/** 是否编码视频流（按 URL 路径后缀判断，忽略查询参数；MJPEG 端点无后缀 → 走逐帧解码） */
private fun isEncodedStreamUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".flv") || path.endsWith(".mp4")
}

/**
 * ExoPlayer 播放（HLS / HTTP-FLV / MP4 通吃）：
 * 断流 2s 自动重连；页面离开（Composable 销毁）自动释放；切后台暂停、回前台恢复。
 */
@Composable
private fun ExoPlayerStreamView(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ready by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var retryTick by remember(url) { mutableIntStateOf(0) }

    val playerOrNull = remember(url) {
        // 防御：不支持的流形态（如缺少对应解码/解析模块）时不应崩溃整个界面——降级为「不可用」提示
        runCatching {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
        }.getOrNull()
    }

    if (playerOrNull == null) {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "视频流不可用（不支持该流格式）",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
        return
    }
    val player = playerOrNull

    // 播放事件：就绪 → 出图；出错 → 标记失败并触发重连
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                retryTick++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    ready = true
                    failed = false
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 断流重连：2s 退避后重新 prepare（直播从最新画面续播）
    LaunchedEffect(retryTick) {
        if (retryTick > 0) {
            delay(2000)
            failed = false
            player.prepare()
        }
    }

    // 生命周期：切后台暂停、回前台恢复；页面销毁时释放播放器
    DisposableEffect(lifecycleOwner, player) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                player.play()
            }

            override fun onPause(owner: LifecycleOwner) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // 实时监控：无控制条；音量随系统
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        when {
            failed -> Text(
                text = "视频流不可用（重连中…）",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )

            !ready -> Text(
                text = "连接视频流…",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}
