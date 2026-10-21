package com.example.myapplication.feature.deployment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.deployment.DeploymentRecordSummary
import com.example.myapplication.core.model.deployment.DeploymentResult
import com.example.myapplication.core.model.deployment.DeploymentState
import com.example.myapplication.core.ui.ContentCard
import com.example.myapplication.core.ui.EmptyStateCard
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.SkeletonList
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.NeutralGreyBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/** 布设事件记录列表：服务端为权威，点击查看详情（复用执行页只读展示）。 */
@Composable
fun DeploymentRecordsScreen(
    vm: DeploymentViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadRecords() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("布设事件记录", onBack = onBack)
        SecondaryActionButton(
            text = "刷新",
            onClick = vm::loadRecords,
            icon = Icons.Default.Refresh,
            loading = state.loading,
        )

        when {
            state.loading && state.records.isEmpty() -> {
                SkeletonList(count = 4, itemHeight = 96.dp)
            }

            state.error != null && state.records.isEmpty() -> {
                SectionCard(title = "加载失败") {
                    Text(state.error ?: "加载布设记录失败", color = WarningRed)
                }
            }

            state.records.isEmpty() -> {
                EmptyStateCard(
                    title = "暂无布设事件",
                    message = "完成或终止一次锥桶协同布设后，事件记录会显示在这里。",
                )
            }

            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.records, key = { it.deploymentId }) { record ->
                        DeploymentRecordCard(record, onClick = { onOpen(record.deploymentId) })
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun DeploymentRecordCard(record: DeploymentRecordSummary, onClick: () -> Unit) {
    val (fg, bg) = when (record.state) {
        DeploymentState.COMPLETED -> SafeGreen to SafeGreenBg
        DeploymentState.ABORTED, DeploymentState.FAILED, DeploymentState.VERIFY_FAILED -> WarningRed to WarningRedBg
        DeploymentState.PAUSED, DeploymentState.PENDING_VERIFY -> WarnOrange to WarnOrangeBg
        else -> InfoBlue to InfoBlueBg
    }
    ContentCard(onClick = onClick) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusChip(record.state.label, fg, bg)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "L=${fmt(record.L)} m · d=${fmt(record.d)} m · N=${record.N}" +
                    (record.lane?.let { " · 第 $it 车道" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "主桶 ${record.masterCode ?: "-"} · 创建 ${TimeFormat.mmddHHmm(record.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = NeutralGrey,
            )
            if (record.result == DeploymentResult.COMPLETED_WITH_EXCEPTION || record.hasSkip) {
                Spacer(Modifier.height(4.dp))
                StatusChip("带异常完成", WarnOrange, WarnOrangeBg)
            }
        }
    }
}

private fun fmt(v: Double): String =
    if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.1f", v)
