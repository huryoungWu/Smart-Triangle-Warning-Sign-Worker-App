package com.example.myapplication.core.ui

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.NeutralGrey

/**
 * 四方向按住遥控（M04）：**按住才移动；松手、触控取消、退出页面或失联后停止**。
 *
 * 手势用 `try/finally` 保证任何结束路径（松手/手势被父级取消/协程取消）
 * 都会回调 [onHoldEnd] → 发送停车指令。
 */
@Composable
fun HoldDirectionPad(
    enabled: Boolean,
    onHoldStart: (MoveDir) -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HoldDirectionButton(MoveDir.FWD, Icons.Default.KeyboardArrowUp, enabled, onHoldStart, onHoldEnd)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HoldDirectionButton(MoveDir.LEFT, Icons.Default.KeyboardArrowLeft, enabled, onHoldStart, onHoldEnd)
            HoldDirectionButton(MoveDir.BACK, Icons.Default.KeyboardArrowDown, enabled, onHoldStart, onHoldEnd)
            HoldDirectionButton(MoveDir.RIGHT, Icons.Default.KeyboardArrowRight, enabled, onHoldStart, onHoldEnd)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "按住移动 · 松手停车",
            color = NeutralGrey,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun HoldDirectionButton(
    dir: MoveDir,
    icon: ImageVector,
    enabled: Boolean,
    onHoldStart: (MoveDir) -> Unit,
    onHoldEnd: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bg = when {
        !enabled -> NeutralGrey.copy(alpha = 0.15f)
        pressed -> InfoBlue
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        !enabled -> NeutralGrey.copy(alpha = 0.4f)
        pressed -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(92.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .background(bg, RoundedCornerShape(16.dp))
            .pointerInput(enabled, dir) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
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
            Icon(icon, contentDescription = dir.label, tint = fg)
            Text(dir.label, color = fg, style = MaterialTheme.typography.labelSmall)
        }
    }
}
