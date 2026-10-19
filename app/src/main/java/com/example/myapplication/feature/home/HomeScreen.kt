package com.example.myapplication.feature.home

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.di.DeviceTransport
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.core.model.vehicle.CommandPhase
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed

/**
 * 开始作业页（主流程第二站）。
 *
 * 页面骨架与「发布任务」页一致：Scaffold + TopAppBar（标题 + ← 返回）+ 16dp 边距。
 * 内容：当前设备 → 正在执行的任务（运行中才显示）→ 开始新任务入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: VehicleViewModel,
    onOpenPublishFixed: () -> Unit,
    onBackToConnect: () -> Unit,
    onOpenExec: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val connected = state.connection == ConnectionPhase.CONNECTED
    val snapshot = state.snapshot
    val task = snapshot?.task
    val spec = state.activeSpec
    val taskRunning = task?.state == TaskRunState.RUNNING

    val context = LocalContext.current
    val sl = remember(context) { ServiceLocator.get(context) }
    val serverUrl by sl.settingsRepository.serverBaseUrl
        .collectAsStateWithLifecycle(initialValue = sl.serverBaseUrl)
    val linkLabel = when (sl.transport) {
        DeviceTransport.SERVER -> "服务端 $serverUrl"
        DeviceTransport.HTTP -> "本地 Wi-Fi 192.168.4.1"
        DeviceTransport.FAKE -> "模拟设备"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("开始作业", onBack = onBackToConnect)

        Text(
            text = "点击「开始新任务」进入任务发布表单，填好路线点「发布」即开始执行",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── 当前设备 ──
        SectionCard(title = "当前设备") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = snapshot?.let { "${it.deviceCode}" } ?: "未读取到设备信息",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        // 链路描述跟随实际传输方式：SERVER 走服务端（地址可运行时改），HTTP 才是直连小车
                        text = snapshot?.let { "固件 ${it.fw} · $linkLabel" } ?: linkLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected) {
                    StatusChip("已连接", SafeGreen, SafeGreenBg)
                } else {
                    StatusChip("设备未连接", WarnOrange, WarnOrangeBg)
                }
            }
            Spacer(Modifier.height(10.dp))
            SecondaryActionButton(
                text = if (state.refreshing) "刷新中…" else "刷新状态",
                onClick = vm::refreshStatus,
                enabled = (connected || state.connection == ConnectionPhase.LOST) &&
                    !state.refreshing,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryActionButton(text = "重新选择设备", onClick = onBackToConnect)
        }

        // ── 正在执行的任务（**仅当任务运行中才显示**，可跳回执行页）──
        if (taskRunning && spec != null) {
            SectionCard(title = "正在执行的任务") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = spec.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip("执行中", InfoBlue, InfoBlueBg)
                }
                Text(
                    text = "${spec.kind.label} · ${spec.mode.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = spec.routeSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                // 进度与执行页「任务进度」同源：同一命令五层步骤与口径
                val phase = state.activeTicket?.phase
                val step = phase?.progressStep ?: 0
                val halted = phase == CommandPhase.FAILED || phase == CommandPhase.UNKNOWN
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        phase == null -> "待下发"
                        halted -> "已中断：${phase.label}"
                        else -> "第 $step/5 步 · ${phase.label}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (halted) WarningRed else InfoBlue,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { step / 5f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (halted) WarningRed else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryActionButton(text = "进入任务执行", onClick = onOpenExec)
            }
        }

        // ── 开始新任务（单一入口，走定距布置 · 全自动驾驶流程）──
        PrimaryActionButton(
            text = "开始新任务",
            onClick = {
                vm.initDraft(TaskKind.FIXED_DISTANCE)
                onOpenPublishFixed()
            },
            enabled = connected && state.taskStartEnabled,
        )
        if (connected && !state.taskStartEnabled) {
            Text(
                text = "当前状态不允许发布（急停锁定／任务运行中／命令未确认）",
                style = MaterialTheme.typography.labelSmall,
                color = WarnOrange,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
