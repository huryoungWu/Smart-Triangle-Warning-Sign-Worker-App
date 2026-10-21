package com.example.myapplication.feature.deployment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg

/**
 * 锥桶流程主页：底栏「作业」Tab 在锥桶模式下的目标页。
 *
 * 只展示锥桶设备就绪情况与进行中事件；设备连接/绑定在 [ConeConnectScreen]，
 * 事件参数与发布在 [DeploymentPublishScreen]。
 */
@Composable
fun ConeHomeScreen(
    vm: DeploymentViewModel,
    onOpenDeviceConnect: () -> Unit,
    onOpenCreate: () -> Unit,
    onOpenExec: (String) -> Unit,
    onSwitchWorkType: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val draft = state.draft

    LaunchedEffect(Unit) {
        vm.reloadDevices()
        vm.loadActiveEvent(force = true)
    }

    val hasMaster = draft.master != null
    val hasSlaves = draft.slaves.isNotEmpty()
    // 主桶必选；从桶可为 0（仅主桶执行）
    val ready = hasMaster
    val active = state.activeEvent

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            text = "锥桶协同布设",
            subtitle = "1 主桶 + 0~5 从桶 · 按自身行程逐级启动",
            onBack = onSwitchWorkType,
        )

        SafetyText("锥桶设备由服务端统一编排；发布任务前请确认主桶/从桶已绑定并处于同一在线状态；未选择从桶时可仅主桶执行。")

        active?.let { event ->
            DeploymentActiveCard(
                event = event,
                onOpen = { onOpenExec(event.deploymentId) },
            )
        }

        SectionCard(title = "设备就绪") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (hasMaster) "主桶：${draft.master?.deviceCode}" else "主桶：未选择",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (hasSlaves) {
                            "从桶：${draft.slaves.sortedBy { it.order }.joinToString(" → ") { it.device.deviceCode }}"
                        } else {
                            "从桶：无（仅主桶执行）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "已绑定主桶 ${state.masterCandidates.size} · 已绑定从桶 ${state.slaveCandidates.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralGrey,
                    )
                }
                if (ready) {
                    StatusChip("设备已就绪", SafeGreen, SafeGreenBg)
                } else {
                    StatusChip("待选择设备", WarnOrange, WarnOrangeBg)
                }
            }
            Spacer(Modifier.height(10.dp))
            SecondaryActionButton(
                text = "设备连接 / 管理绑定设备",
                onClick = onOpenDeviceConnect,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (active != null) {
            Text(
                "已有进行中的布设事件。请进入执行页完成或终止后再发布新任务。",
                color = WarnOrange,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (!ready) {
            Text(
                "请先进入「设备连接」选择主桶；从桶可选（不选则仅主桶执行）。",
                color = WarnOrange,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        PrimaryActionButton(
            text = if (state.busy) "处理中…" else "发布协同布设任务",
            onClick = onOpenCreate,
            enabled = ready && active == null && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))
        SecondaryActionButton(
            text = "切换作业类型",
            onClick = onSwitchWorkType,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.loading) {
            Text("正在加载设备与事件…", style = MaterialTheme.typography.labelSmall, color = InfoBlue)
        }

        Spacer(Modifier.height(24.dp))
    }

    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("无法继续") },
            text = { Text(msg) },
            confirmButton = { Button(onClick = vm::dismissError) { Text("知道了") } },
        )
    }
    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { Button(onClick = vm::dismissMessage) { Text("知道了") } },
        )
    }
}
