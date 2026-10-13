package com.example.myapplication.feature.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.vehicle.CommandPhase
import com.example.myapplication.core.model.vehicle.SegmentRunState
import com.example.myapplication.core.model.vehicle.TaskMode
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.model.vehicle.WorkRecord
import com.example.myapplication.core.ui.CommandTimeline
import com.example.myapplication.core.ui.ConfirmDialog
import com.example.myapplication.core.ui.DangerActionButton
import com.example.myapplication.core.ui.DeviceStatusStrip
import com.example.myapplication.core.ui.map.MapPanel
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.RemoteControlPad
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.feature.map.MapViewModel
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/**
 * 任务执行页（取代原「一键布置」）。
 *
 * **页面上没有「启动」按钮 —— 任务在「发布」时已开始**，本页纯监控 + 少量干预。
 * 进度完全由 1Hz 轮询 + 设备回报的分段状态驱动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskExecScreen(
    vm: VehicleViewModel,
    mapVm: MapViewModel,
    onBack: () -> Unit,
    onOpenMap: () -> Unit,
    onRepublish: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val task = snapshot?.task
    val spec = state.activeSpec
    val ticket = state.activeTicket
    val lifecycleOwner = LocalLifecycleOwner.current
    var showCancelConfirm by remember { mutableStateOf(false) }
    var showBackConfirm by remember { mutableStateOf(false) }

    // 手动干预的三重停车保障（离开页面/切后台）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onLeaveManual() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.onLeaveManual()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(spec?.name ?: "任务执行", style = MaterialTheme.typography.titleMedium)
                        if (spec != null) {
                            Text(
                                spec.mode.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (task?.state == TaskRunState.RUNNING) showBackConfirm = true
                            else onBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenMap) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = "地图",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceStatusStrip(state)
            MapPanel(vm = vm, mapVm = mapVm, height = 200.dp, title = "小车实时位置", showLegend = false)

            // 任务状态头卡
            SectionCard(title = "任务进度") {
                val segIdx = task?.segmentIndex ?: 0
                val total = task?.segments?.size ?: spec?.segments?.size ?: 0
                val currentSeg = task?.segments?.getOrNull(segIdx)
                Text(
                    text = when {
                        task == null -> "任务尚未下发"
                        task.state == TaskRunState.RUNNING && total > 0 ->
                            "正在执行第 ${segIdx + 1}/$total 段：${currentSeg?.summary() ?: ""}"

                        task.state == TaskRunState.DONE -> "全部路段执行完成"
                        task.state == TaskRunState.FAILED -> "任务失败，已停止后续路段"
                        task.state == TaskRunState.CANCELED -> "任务已取消"
                        else -> "任务状态：${task.state.label}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task?.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "行驶距离由车轮编码器估算，非地图定位",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }

            // 分段进度列表
            val segs = task?.segments ?: spec?.segments?.map {
                com.example.myapplication.core.model.vehicle.SegmentState(action = it.action, value = it.value)
            } ?: emptyList()
            if (segs.isNotEmpty()) {
                SectionCard(title = "路线分段") {
                    SegmentProgressList(segments = segs)
                }
            }

            // 命令五层
            SectionCard(title = "命令状态") {
                CommandTimeline(current = ticket?.phase, note = ticket?.note)
            }

            // 失败熔断
            if (task?.state == TaskRunState.FAILED) {
                Card(colors = CardDefaults.cardColors(containerColor = WarningRedBg)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("任务失败，已熔断", color = WarningRed, fontWeight = FontWeight.Bold)
                        Text(
                            "失败原因：${task.resultNote ?: "设备报告任务未完成"}\n" +
                                "不会继续后续路段，剩余段已标「未执行」。",
                            color = WarningRed,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // 下发失败 → 重新下发（不是"启动"，语义是重试发布命令）
            if (task == null || task.state == TaskRunState.IDLE) {
                if (ticket?.phase == CommandPhase.FAILED || ticket?.phase == CommandPhase.UNKNOWN) {
                    Card(colors = CardDefaults.cardColors(containerColor = WarningRedBg)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("任务下发失败", color = WarningRed, fontWeight = FontWeight.Bold)
                            Text(
                                ticket.note ?: "命令未被设备接受",
                                color = WarningRed,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            PrimaryActionButton(text = "重新下发", onClick = vm::retryPublish)
                            Spacer(Modifier.height(6.dp))
                            SecondaryActionButton(text = "返回首页", onClick = onBack)
                        }
                    }
                }
            }

            // 操作区（按状态切换）
            if (task?.state == TaskRunState.RUNNING) {
                val waiting = task.waitingConfirm
                if (spec?.mode == TaskMode.SEGMENT_CONFIRM && waiting) {
                    val seg = task.segments.firstOrNull { it.state == SegmentRunState.WAITING_CONFIRM }
                    PrimaryActionButton(
                        text = "执行本段（${seg?.summary() ?: ""}）",
                        onClick = vm::confirmCurrentSegment,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                DangerActionButton(text = "停止并取消任务", onClick = { showCancelConfirm = true })
            }

            // 手动干预（遥控器）
            if (task?.state == TaskRunState.RUNNING || task == null || task.state != TaskRunState.DONE) {
                SectionCard(title = "手动干预") {
                    val segRunning = task?.segments?.any { it.state == SegmentRunState.RUNNING } == true
                    val autoMode = spec?.mode == TaskMode.AUTO
                    val canManual = state.motionCommandsEnabled && !segRunning &&
                        !(autoMode && task?.state == TaskRunState.RUNNING)

                    RemoteControlPad(
                        enabled = canManual,
                        holdingDir = state.holdingDir,
                        onHoldStart = vm::onHoldStart,
                        onHoldEnd = vm::onHoldEnd,
                        onStopClick = vm::stopNormal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            autoMode && task?.state == TaskRunState.RUNNING ->
                                "全自动驾驶执行中，禁止手动抢占。"
                            segRunning -> "本段执行中，禁止抢占；段间可微调。"
                            else -> "按住方向键微调车辆位置，松手停车；微调不会推进或取消任务。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canManual) NeutralGrey else WarnOrange,
                    )
                }
            }

            // 完成 → 核验 → 上传
            if (task?.state == TaskRunState.DONE) {
                Card(colors = CardDefaults.cardColors(containerColor = SafeGreenBg)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("设备报告已完成", color = SafeGreen, fontWeight = FontWeight.Bold)
                        Text(
                            "请人工现场核对实际位置、朝向与灯光。\n核验结果与设备完成分开记录。",
                            color = SafeGreen,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (state.pendingVerify != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryActionButton(
                            text = "核验通过",
                            onClick = { vm.confirmVerify(true, "位置/朝向/灯光正常") },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        DangerActionButton(
                            text = "发现异常",
                            onClick = { vm.confirmVerify(false, "现场异常，需处置") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    // 上传卡
                    val record = state.uploadRecord
                    SectionCard(title = "任务上传") {
                        StatusChip(
                            text = WorkRecord.uploadStateLabel(record?.uploadState ?: WorkRecord.UPLOAD_LOCAL),
                            fg = when (record?.uploadState) {
                                WorkRecord.UPLOAD_UPLOADED -> SafeGreen
                                WorkRecord.UPLOAD_FAILED -> WarningRed
                                else -> WarnOrange
                            },
                            bg = when (record?.uploadState) {
                                WorkRecord.UPLOAD_UPLOADED -> SafeGreenBg
                                WorkRecord.UPLOAD_FAILED -> WarningRedBg
                                else -> WarnOrangeBg
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when (record?.uploadState) {
                                WorkRecord.UPLOAD_UPLOADED ->
                                    "演示占位单号 ${record.serverId ?: "-"}，未接入真实服务器。"
                                WorkRecord.UPLOAD_FAILED -> "上传失败，记录已保存本机，可重试。"
                                else -> "记录已保存本机（${WorkRecord.LOCAL_ONLY_NOTICE}），服务器尚未接入。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        PrimaryActionButton(
                            text = if (record?.uploadState == WorkRecord.UPLOAD_FAILED) "重试上传" else "提交上传（演示）",
                            onClick = vm::uploadActiveRecord,
                            enabled = record?.uploadState != WorkRecord.UPLOAD_UPLOADED,
                        )
                        Spacer(Modifier.height(6.dp))
                        SecondaryActionButton(text = "稍后上传", onClick = onBack)
                    }

                    // 任务完成总结
                    SectionCard(title = "任务完成") {
                        Text("任务名：${spec?.name ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                        Text("路线：${spec?.routeSummary() ?: "-"}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "行驶合计 ${"%.1f".format(task.traveledM)} 米",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        if ((snapshot?.odo?.traveledM ?: 0.0) > 0.01) {
                            PrimaryActionButton(text = "回收小车（回到我这里）", onClick = vm::startRecoverTask)
                            Spacer(Modifier.height(8.dp))
                        }
                        SecondaryActionButton(text = "返回首页", onClick = onBack)
                        Spacer(Modifier.height(6.dp))
                        SecondaryActionButton(text = "再次发布本路线", onClick = onRepublish)
                    }
                }
            }

            SafetyText("如需紧急停车请使用遥控器中央「停止」或直接断电；车身实体急停不由 APP 软件按钮替代。")
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCancelConfirm) {
        ConfirmDialog(
            title = "停止并取消任务？",
            message = "将取消当前任务，剩余路段标为「未执行」。\n\n小车会立即停车，请确认现场安全。",
            confirmText = "确认取消",
            onConfirm = {
                showCancelConfirm = false
                vm.cancelTask()
            },
            onDismiss = { showCancelConfirm = false },
        )
    }

    if (showBackConfirm) {
        ConfirmDialog(
            title = "任务仍在执行",
            message = "返回首页**不会停止任务**，小车将继续按路线行驶。\n\n" +
                "需要紧急停车时，可在执行页点「停止并取消任务」。",
            confirmText = "返回首页",
            danger = false,
            onConfirm = {
                showBackConfirm = false
                onBack()
            },
            onDismiss = { showBackConfirm = false },
        )
    }
}
