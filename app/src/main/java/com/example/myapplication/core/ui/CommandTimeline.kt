package com.example.myapplication.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.model.vehicle.CommandPhase
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarningRed

private data class TimelineStep(val phase: CommandPhase, val label: String)

private val steps = listOf(
    TimelineStep(CommandPhase.SENDING, "已发出"),
    TimelineStep(CommandPhase.ACCEPTED, "设备已接收"),
    TimelineStep(CommandPhase.EXECUTING, "正在执行"),
    TimelineStep(CommandPhase.DEVICE_DONE, "设备报告已完成"),
    TimelineStep(CommandPhase.VERIFIED, "人工现场核验"),
)

/**
 * 命令状态分层指示条（规格第 4 节）：
 * 已发出 → 设备已接收 → 正在执行 → 设备报告完成 → 人工现场核验。
 * 超时/失联时显示"结果未知"，提示先查询设备状态，**不自动重复运动命令**。
 */
@Composable
fun CommandTimeline(
    current: CommandPhase?,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (current == CommandPhase.UNKNOWN || current == CommandPhase.FAILED) {
            Text(
                text = "⚠ ${current.label}：${note ?: "请查询设备状态后现场确认，系统不会自动重发运动命令"}",
                color = WarningRed,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            steps.forEachIndexed { index, step ->
                val state = stepState(step.phase, current)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = if (state == StepState.DONE) "●" else if (state == StepState.ACTIVE) "◉" else "○",
                        color = when (state) {
                            StepState.DONE -> SafeGreen
                            StepState.ACTIVE -> WarnOrange
                            StepState.IDLE -> NeutralGrey.copy(alpha = 0.5f)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = step.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (state == StepState.ACTIVE) FontWeight.Bold else FontWeight.Normal,
                        color = when (state) {
                            StepState.DONE -> SafeGreen
                            StepState.ACTIVE -> WarnOrange
                            StepState.IDLE -> NeutralGrey.copy(alpha = 0.6f)
                        },
                    )
                    if (index < steps.lastIndex) {
                        Text(
                            text = "→",
                            color = NeutralGrey.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

private enum class StepState { IDLE, ACTIVE, DONE }

private fun stepState(step: CommandPhase, current: CommandPhase?): StepState {
    if (current == null) return StepState.IDLE
    val order = steps.map { it.phase }
    val stepIndex = order.indexOf(step)
    val currentIndex = order.indexOf(
        when (current) {
            CommandPhase.FAILED, CommandPhase.UNKNOWN -> return StepState.IDLE
            else -> current
        },
    )
    return when {
        currentIndex < 0 -> StepState.IDLE
        stepIndex < currentIndex -> StepState.DONE
        stepIndex == currentIndex -> StepState.ACTIVE
        else -> StepState.IDLE
    }
}

/** 小圆点指示（列表/状态卡内联使用） */
@Composable
fun PhaseDot(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = if (active) "●" else "○",
        color = color,
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(Color.Transparent),
    )
}
