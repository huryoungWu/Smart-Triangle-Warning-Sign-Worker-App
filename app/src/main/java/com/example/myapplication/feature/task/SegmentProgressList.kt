package com.example.myapplication.feature.task

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.model.vehicle.SegmentRunState
import com.example.myapplication.core.model.vehicle.SegmentState
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.NeutralGreyBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/**
 * 分段进度列表：每段一行 —— 序号、路线摘要、状态标签、段内进度。
 * 段状态以设备回报为准（规格第 4 节）。
 */
@Composable
fun SegmentProgressList(
    segments: List<SegmentState>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        segments.forEachIndexed { i, seg ->
            SegmentRow(index = i, segment = seg, isLast = i == segments.lastIndex)
        }
    }
}

@Composable
private fun SegmentRow(index: Int, segment: SegmentState, isLast: Boolean) {
    val (fg, bg) = when (segment.state) {
        SegmentRunState.PENDING -> NeutralGrey to NeutralGreyBg
        SegmentRunState.WAITING_CONFIRM -> WarnOrange to WarnOrangeBg
        SegmentRunState.RUNNING -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        SegmentRunState.DONE -> SafeGreen to SafeGreenBg
        SegmentRunState.FAILED -> WarningRed to WarningRedBg
        SegmentRunState.SKIPPED -> NeutralGrey to NeutralGreyBg
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (segment.state) {
                SegmentRunState.RUNNING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                SegmentRunState.FAILED -> WarningRedBg
                SegmentRunState.DONE -> SafeGreenBg.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (segment.state) {
                        SegmentRunState.DONE -> "✔"
                        SegmentRunState.RUNNING -> "▶"
                        SegmentRunState.FAILED -> "✖"
                        SegmentRunState.SKIPPED -> "－"
                        else -> "○"
                    },
                    color = fg,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${index + 1}. ${segment.summary()}",
                    fontWeight = if (segment.state == SegmentRunState.RUNNING) FontWeight.Bold
                    else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                StatusChip(segment.state.label, fg, bg)
            }

            if (segment.state == SegmentRunState.RUNNING) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { segment.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    segment.progressText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (!isLast) Spacer(Modifier.height(4.dp))
}
