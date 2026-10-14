package com.example.myapplication.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.core.model.vehicle.MoveDir

private val AccentStart = Color(0xFF5B8DEF)
private val AccentEnd = Color(0xFF3D6BE5)
private val StopStart = Color(0xFFEF5B5B)
private val StopEnd = Color(0xFFD93A3A)
private val IdleBg = Color(0xFFF1F4F9)

/** 圆内图形区占按键直径的比例；箭头与停止符号共用，保证五个按键的文字基线一致 */
private const val GlyphRatio = 0.40f

/**
 * 遥控器式方向控制盘（M04）。
 *
 * 布局：前进 / 左转·停止·右转 / 后退 构成等距十字 —— 五键统一尺寸、同间距，
 * 圆内「图形 + 文字」结构一致，并按可用宽度自适应收缩，弹窗或窄屏下不会挤压错位。
 *
 * **按住即持续运行**：按下发送方向指令，期间每 300ms 自动补发保持包
 * （设备侧有断流看门狗，只发一条会被判失联自动停车），松手立即停车。
 * 手势用 `try/finally` 保证松手/触控取消/协程取消都会停车。
 */
@Composable
fun RemoteControlPad(
    enabled: Boolean,
    holdingDir: MoveDir?,
    onHoldStart: (MoveDir) -> Unit,
    onHoldEnd: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 面板底座
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                        ),
                    ),
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints {
                // 十字盘：五键同尺寸、同间距；宽度不足时整体等比收缩
                val gap = 12.dp
                val btn = minOf(84.dp, (maxWidth - gap * 2) / 3)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    HoldButton(
                        dir = MoveDir.FWD,
                        icon = Icons.Default.KeyboardArrowUp,
                        label = "前进",
                        enabled = enabled,
                        active = holdingDir == MoveDir.FWD,
                        size = btn,
                        onHoldStart = onHoldStart,
                        onHoldEnd = onHoldEnd,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        HoldButton(
                            dir = MoveDir.LEFT,
                            icon = Icons.Default.KeyboardArrowLeft,
                            label = "左转",
                            enabled = enabled,
                            active = holdingDir == MoveDir.LEFT,
                            size = btn,
                            onHoldStart = onHoldStart,
                            onHoldEnd = onHoldEnd,
                        )
                        StopButton(enabled = enabled, size = btn, onClick = onStopClick)
                        HoldButton(
                            dir = MoveDir.RIGHT,
                            icon = Icons.Default.KeyboardArrowRight,
                            label = "右转",
                            enabled = enabled,
                            active = holdingDir == MoveDir.RIGHT,
                            size = btn,
                            onHoldStart = onHoldStart,
                            onHoldEnd = onHoldEnd,
                        )
                    }
                    HoldButton(
                        dir = MoveDir.BACK,
                        icon = Icons.Default.KeyboardArrowDown,
                        label = "后退",
                        enabled = enabled,
                        active = holdingDir == MoveDir.BACK,
                        size = btn,
                        onHoldStart = onHoldStart,
                        onHoldEnd = onHoldEnd,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = if (holdingDir != null) "运行中 · 松手停车" else "按住方向键持续运行 · 松手即停",
            color = if (holdingDir != null) AccentStart else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun HoldButton(
    dir: MoveDir,
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean,
    size: Dp,
    onHoldStart: (MoveDir) -> Unit,
    onHoldEnd: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.90f else 1f, label = "btnScale")
    val contentColor = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .shadow(
                elevation = if (enabled) 6.dp else 0.dp,
                shape = CircleShape,
                clip = false,
            )
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> Brush.linearGradient(listOf(IdleBg, IdleBg))
                    active || pressed -> Brush.linearGradient(listOf(AccentStart, AccentEnd))
                    else -> Brush.linearGradient(
                        listOf(AccentStart.copy(alpha = 0.85f), AccentEnd.copy(alpha = 0.85f)),
                    )
                },
            )
            .pointerInput(enabled, dir) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHoldStart(dir)
                    try {
                        var stillPressed = true
                        while (stillPressed) {
                            val event = awaitPointerEvent()
                            stillPressed = event.changes.any { change -> change.pressed }
                        }
                    } finally {
                        onHoldEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(size * GlyphRatio),
            )
            Text(
                label,
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StopButton(enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.90f else 1f, label = "stopScale")
    val contentColor = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .shadow(
                elevation = if (enabled) 6.dp else 0.dp,
                shape = CircleShape,
                clip = false,
            )
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> Brush.linearGradient(listOf(IdleBg, IdleBg))
                    pressed -> Brush.linearGradient(listOf(StopEnd, StopEnd))
                    else -> Brush.linearGradient(listOf(StopStart, StopEnd))
                },
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    try {
                        var stillPressed = true
                        while (stillPressed) {
                            val event = awaitPointerEvent()
                            stillPressed = event.changes.any { change -> change.pressed }
                        }
                    } finally {
                        onClick()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 标准停止符号：圆角方块；图形区与方向键等高，文字才能同行对齐
            Box(Modifier.size(size * GlyphRatio), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(size * 0.27f)
                        .clip(RoundedCornerShape(size * 0.065f))
                        .background(contentColor),
                )
            }
            Text(
                "停止",
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
