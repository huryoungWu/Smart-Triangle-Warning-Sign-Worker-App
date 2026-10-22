package com.example.myapplication.feature.deployment

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.model.deployment.DeploymentEvent
import com.example.myapplication.core.model.deployment.DeploymentResult
import com.example.myapplication.core.model.deployment.DeploymentState
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/**
 * 「已有进行中的布设事件」卡片：三角牌首页、锥桶首页、记录页共用。
 */
@Composable
fun DeploymentActiveCard(event: DeploymentEvent, onOpen: () -> Unit) {
    val (fg, bg) = when (event.state) {
        DeploymentState.COMPLETED -> SafeGreen to SafeGreenBg
        DeploymentState.ABORTED, DeploymentState.FAILED, DeploymentState.VERIFY_FAILED ->
            WarningRed to WarningRedBg
        DeploymentState.PAUSED, DeploymentState.PENDING_VERIFY ->
            WarnOrange to WarnOrangeBg
        else -> InfoBlue to InfoBlueBg
    }
    SectionCard(title = "进行中的布设事件") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.name,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            StatusChip(event.state.label, fg, bg)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "L=${fmtDeployment(event.params.L)} m · d=${fmtDeployment(event.params.d)} m · N=${event.params.N}" +
                (event.lane?.let { " · 第 $it 车道" } ?: "") +
                (event.params.effectiveTurns.takeIf { it.isNotEmpty() }
                    ?.let { " · 转弯 ${it.size} 处" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "已到达 ${event.arrivedCount}/${event.devices.size} · 已核验 ${event.verifiedCount}/${event.devices.size}" +
                if (event.hasSkip) " · 人工跳过 ${event.devices.count { it.skipped }}" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        if (event.result == DeploymentResult.COMPLETED_WITH_EXCEPTION) {
            Spacer(Modifier.height(4.dp))
            StatusChip("带异常完成", WarnOrange, WarnOrangeBg)
        }
        Spacer(Modifier.height(10.dp))
        PrimaryActionButton(text = "进入布设执行", onClick = onOpen)
    }
}

internal fun fmtDeployment(v: Double): String =
    if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.1f", v)
