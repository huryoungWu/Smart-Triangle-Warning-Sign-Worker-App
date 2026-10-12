package com.example.myapplication.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.core.model.vehicle.MoveDir

/**
 * 遥控器式方向控制盘（M04）。
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
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ),
                    ),
                )
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HoldButton(
                    dir = MoveDir.FWD,
                    icon = Icons.Default.KeyboardArrowUp,
                    label = "前进",
                    enabled = enabled,
                    active = holdingDir == MoveDir.FWD,
                    onHoldStart = onHoldStart,
                    onHoldEnd = onHoldEnd,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HoldButton(
                        dir = MoveDir.LEFT,
                        icon = Icons.Default.KeyboardArrowLeft,
                        label = "左转",
                        enabled = enabled,
                        active = holdingDir == MoveDir.LEFT,
                        onHoldStart = onHoldStart,
                        onHoldEnd = onHoldEnd,
                    )
                    // 中央急停/停止按钮
                    StopButton(enabled = enabled, onClick = onStopClick)
                    HoldButton(
                        dir = MoveDir.RIGHT,
                        icon = Icons.Default.KeyboardArrowRight,
                        label = "右转",
                        enabled = enabled,
                        active = holdingDir == MoveDir.RIGHT,
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
                    onHoldStart = onHoldStart,
                    onHoldEnd = onHoldEnd,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = if (holdingDir != null) "运行中 · 松手停车" else "按住方向键持续运行 · 松手即停",
            color = if (holdingDir != null) {
                androidx.compose.ui.graphics.Color(0xFF1565C0)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
    onHoldStart: (MoveDir) -> Unit,
    onHoldEnd: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.92f else 1f, label = "btnScale")

    val bg = when {
        !enabled -> Color(0xFFBDBDBD)
        active || pressed -> Color(0xFF1565C0)
        else -> Color(0xFF5C6BC0)
    }
    val fg = when {
        !enabled -> Color.White.copy(alpha = 0.6f)
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .size(88.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
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
                        // 松手/取消/协程取消 —— 一律停车
                        onHoldEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(34.dp))
            Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StopButton(enabled: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.92f else 1f, label = "stopScale")

    Box(
        modifier = Modifier
            .size(72.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> Color(0xFFE0E0E0)
                    pressed -> Color(0xFFB71C1C)
                    else -> Color(0xFFD32F2F)
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
            // 标准停止符号：方块（core 图标集无 Stop）
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (enabled) Color.White else Color.White.copy(alpha = 0.6f),
                    ),
            )
            Text(
                "停止",
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
