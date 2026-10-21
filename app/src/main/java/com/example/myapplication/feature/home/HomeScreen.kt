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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.di.DeviceTransport
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.core.model.vehicle.CommandKind
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
import com.example.myapplication.ui.theme.WarningRedBg

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
    onSwitchWorkType: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val connected = state.connection == ConnectionPhase.CONNECTED
    val snapshot = state.snapshot
    val task = snapshot?.task
    val spec = state.activeSpec
    // 首页不参与实时轮询：发布任务后快照可能仍是 IDLE。
    // 因此这里同时参考本地命令票据 —— 任务已下发且票据未进入终态，也算「正在执行」。
    val localTaskActive = state.activeTicket?.let {
        it.kind == CommandKind.TASK_START && !it.phase.isTerminal
    } == true
    val taskRunning = task?.state == TaskRunState.RUNNING || localTaskActive
    // 设备已报告 done 但用户尚未点「任务完成」时，仍保留入口，避免离开执行页后无法确认
    val taskNeedsAttention = spec != null && !state.taskCompletedByUser &&
        (taskRunning || task?.state == TaskRunState.DONE)

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
        PageTitle("三脚架布设", subtitle = "单设备 · 定距路线", onBack = onSwitchWorkType)

        Text(
            text = "连接单台三角警示牌设备后发布定距路线任务。",
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

        // ── 当前任务（执行中，或设备完成但用户尚未确认；可跳回执行页）──
        if (taskNeedsAttention) {
            SectionCard(title = "当前任务") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = spec.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        if (task?.state == TaskRunState.DONE) "待确认完成" else "执行中",
                        InfoBlue,
                        InfoBlueBg,
                    )
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
                // 进度与执行页同源：只显示「未完成 / 已完成」，已完成由用户确认
                val completed = state.taskCompletedByUser
                Spacer(Modifier.height(8.dp))
                StatusChip(
                    text = if (completed) "已完成" else "未完成",
                    fg = if (completed) SafeGreen else InfoBlue,
                    bg = if (completed) SafeGreenBg else InfoBlueBg,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryActionButton(text = "进入任务执行", onClick = onOpenExec)
            }
        }

        // ── 开始新任务（本流程只有三角牌定距布置）──
        PrimaryActionButton(
            text = "开始新任务（三脚架布设）",
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

        Spacer(Modifier.height(8.dp))
        SecondaryActionButton(
            text = "切换作业类型",
            onClick = onSwitchWorkType,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
    }
}
