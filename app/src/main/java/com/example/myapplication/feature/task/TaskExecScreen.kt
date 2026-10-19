package com.example.myapplication.feature.task

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.core.model.vehicle.CommandKind
import com.example.myapplication.core.model.vehicle.CommandPhase
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.LaneRange
import com.example.myapplication.core.model.vehicle.SegmentRunState
import com.example.myapplication.core.model.vehicle.TaskMode
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.model.vehicle.VehicleMode
import com.example.myapplication.core.model.vehicle.WorkRecord
import com.example.myapplication.core.ui.ConfirmDialog
import com.example.myapplication.core.ui.DangerActionButton
import com.example.myapplication.core.ui.DeviceStatusStrip
import com.example.myapplication.core.ui.map.MapPanel
import com.example.myapplication.core.ui.MjpegStreamView
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.RemoteControlPad
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.di.DeviceTransport
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.di.UploadMode
import com.example.myapplication.feature.map.MapViewModel
import com.example.myapplication.feature.vehicle.VehicleViewModel
import java.util.Locale
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

/**
 * 任务执行页（取代原「一键布置」）。
 *
 * **页面上没有「启动」按钮 —— 任务在「发布」时已开始**，本页纯监控 + 少量干预。
 * 进度由页面可见期间的 1Hz 状态读取（设备回报）驱动。
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
    // 状态快照可能还没刷新：任务已下发且票据未终态时，也允许用户确认完成
    val localTaskActive = ticket?.let {
        it.kind == CommandKind.TASK_START && !it.phase.isTerminal
    } == true
    val lifecycleOwner = LocalLifecycleOwner.current
    // 页面可见期间 1Hz 读取状态：小车位置、轨迹和任务进度实时更新；切后台/离开页面自动停止
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.acquireLiveStatus()
            try {
                awaitCancellation()
            } finally {
                vm.releaseLiveStatus()
            }
        }
    }
    var showCancelConfirm by remember { mutableStateOf(false) }
    var showBackConfirm by remember { mutableStateOf(false) }
    var showManualPad by remember { mutableStateOf(false) }

    // 打开手动干预时先刷新一次状态，避免旧快照仍显示 AUTO/段运行中而把遥控盘误判为禁用
    LaunchedEffect(showManualPad) {
        if (showManualPad) vm.refreshStatus()
    }

    // 手动干预的三重停车保障（离开页面/切后台）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onLeaveManual() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.onLeaveManual()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
        text = spec?.name ?: "任务执行",
        subtitle = spec?.let { "${it.mode.label} · ${it.laneLabel()}" },
        onBack = { if (task?.state == TaskRunState.RUNNING) showBackConfirm = true else onBack() },
        trailing = {
            IconButton(onClick = onOpenMap) {
                Icon(Icons.Default.Place, contentDescription = "地图")
            }
        },
        )

        DeviceStatusStrip(
            state = state,
            refreshing = state.refreshing,
            onRefresh = vm::refreshStatus,
        )
        MapPanel(
            vm = vm,
            mapVm = mapVm,
            height = 200.dp,
            title = "小车实时位置",
            showLegend = false,
            showPhoneMarker = true,   // 蓝星 = 我的实时位置 A（任务起点）
        )

        // ── 实时视频与快照捕捉 ──
        SectionCard(title = "实时视频") {
            val context = LocalContext.current
            val sl = remember { ServiceLocator.get(context) }
            // 服务端地址可运行时更改（设置 → 服务器地址）：订阅它，换地址后 URL 变化，
            // MjpegStreamView 以 url 为 key → 自动断流重连
            val serverUrl by sl.settingsRepository.serverBaseUrl
                .collectAsStateWithLifecycle(initialValue = sl.serverBaseUrl)
            // 联调模式：播放服务端**该设备的**视频流（按 device_code 各一路；快照即从该流截帧）
            val streamUrl = if (sl.transport == DeviceTransport.SERVER) {
                "$serverUrl/api/v1/device/video/stream?device_code=${state.selectedDeviceCode}"
            } else {
                null
            }
            if (streamUrl != null) {
                MjpegStreamView(
                    url = streamUrl,
                    contentDescription = "实时视频",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "服务端视频流（演示动画帧 · ${state.selectedDeviceCode} 专属一路）",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color(0xFF37474F), Color(0xFF263238)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(42.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "实时视频（演示占位 · 未接入车载摄像头）",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            PrimaryActionButton(
                text = "捕捉快照",
                onClick = { vm.captureSnapshot(currentEventLabel(task?.state)) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "任务开始、结束会自动各抓拍一张并弹窗提示；执行中间可自行补拍（可拍可不拍）。" +
                    "快照上传后将在「风险」页作为作业人员上报展示，用于提醒过往司机。",
                style = MaterialTheme.typography.labelSmall,
                color = NeutralGrey,
            )
        }




        // 任务进度：只有「未完成 / 已完成」两个状态；已完成由用户点击确认
        SectionCard(title = "任务进度") {
            val completed = state.taskCompletedByUser
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(
                    text = "未完成",
                    fg = if (completed) NeutralGrey else WarnOrange,
                    bg = if (completed) NeutralGreyBg else WarnOrangeBg,
                )
                StatusChip(
                    text = "已完成",
                    fg = if (completed) SafeGreen else NeutralGrey,
                    bg = if (completed) SafeGreenBg else NeutralGreyBg,
                )
            }
            if (!completed && (task?.state == TaskRunState.RUNNING || task?.state == TaskRunState.DONE || localTaskActive)) {
                Spacer(Modifier.height(10.dp))
                PrimaryActionButton(
                    text = "任务完成",
                    onClick = vm::completeTask,
                    enabled = state.connection == ConnectionPhase.CONNECTED,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "点击后状态变为「已完成」，并自动上传生成作业记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            } else if (completed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已完成，作业记录已生成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = SafeGreen,
                )
            }
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

        // 手动干预：屏幕按钮，点击后弹出临时窗口使用遥控器
        SecondaryActionButton(
            text = "打开手动干预",
            onClick = { showManualPad = true },
        )

        // 完成 → 核验 → 上传
        if (task?.state == TaskRunState.DONE) {

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
                // 上传卡（核验通过后自动上传，不再需要手动「提交上传」）
                val record = state.uploadRecord
                val autoUploaded = record?.verifyResult == WorkRecord.VERIFY_PASS
                val serverUpload = ServiceLocator.UPLOAD_MODE == UploadMode.HTTP
                // 上传成功后不再保留「任务上传」模块（成功反馈由上传完成弹窗给出）；
                // 未上传/上传失败时保留卡片，供重试。
                if (record?.uploadState != WorkRecord.UPLOAD_UPLOADED) {
                    SectionCard(title = "任务上传") {
                        StatusChip(
                            text = WorkRecord.uploadStateLabel(record?.uploadState ?: WorkRecord.UPLOAD_LOCAL, serverUpload),
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
                            text = when {
                                record?.uploadState == WorkRecord.UPLOAD_UPLOADED ->
                                    if (serverUpload) {
                                        "服务端单号 ${record.serverId ?: "-"}。"
                                    } else {
                                        "演示占位单号 ${record.serverId ?: "-"}，未接入真实服务器。"
                                    }
                                record?.uploadState == WorkRecord.UPLOAD_FAILED ->
                                    "上传失败，记录已保存本机，可重试。"
                                autoUploaded ->
                                    if (serverUpload) {
                                        "核验已通过，记录正在自动上传服务端…"
                                    } else {
                                        "核验已通过，记录正在自动上传（演示占位，未接入真实服务器）。"
                                    }
                                else ->
                                    if (serverUpload) {
                                        "记录已保存本机，等待上传服务端。"
                                    } else {
                                        "记录已保存本机（${WorkRecord.LOCAL_ONLY_NOTICE}），服务器尚未接入。"
                                    }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        // 核验通过 → 已自动上传，无需按钮；上传失败仍可重试；核验异常保留手动提交
                        if (!autoUploaded || record?.uploadState == WorkRecord.UPLOAD_FAILED) {
                            PrimaryActionButton(
                                text = if (record?.uploadState == WorkRecord.UPLOAD_FAILED) {
                                    "重试上传"
                                } else if (serverUpload) {
                                    "提交上传"
                                } else {
                                    "提交上传（演示）"
                                },
                                onClick = vm::uploadActiveRecord,
                                enabled = record?.uploadState != WorkRecord.UPLOAD_UPLOADED,
                            )
                            Spacer(Modifier.height(6.dp))
                            SecondaryActionButton(text = "稍后上传", onClick = onBack)
                        }
                    }
                }

                // 任务完成总结
                SectionCard(title = "任务完成") {
                    Text("任务名：${spec?.name ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                    // 作业车道：以设备回显为准（task.lane），旧固件无回显时回落发布规格
                    Text(
                        "作业车道：${task.lane?.let { LaneRange.label(it) } ?: spec?.laneLabel() ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
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

        Spacer(Modifier.height(24.dp))
    }

    // 手动干预临时窗口：点击按钮才弹出，关闭时自动停车
    if (showManualPad) {
        // 打开弹窗会先刷新状态：刷新期间禁用遥控盘，避免用旧快照误发/误拦
        val canManual = !state.refreshing && state.motionCommandsEnabled

        AlertDialog(
            onDismissRequest = {
                vm.onLeaveManual()
                showManualPad = false
            },
            title = { Text("手动干预") },
            text = {
                Column {
                    RemoteControlPad(
                        enabled = canManual,
                        holdingDir = state.holdingDir,
                        onHoldStart = vm::onHoldStart,
                        onHoldEnd = vm::onHoldEnd,
                        onStopClick = vm::stopNormal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            state.refreshing -> "正在读取最新状态…"
                            state.connection != ConnectionPhase.CONNECTED ->
                                "设备未连接或状态未确认。"
                            state.snapshot?.estopLocked == true ||
                                state.snapshot?.mode == VehicleMode.SAFE_STOP ->
                                "急停锁定/安全停止中，禁止手动运动。"
                            else ->
                                "按住方向键微调车辆位置，松手停车；手动微调不会取消任务，自动任务会在松开后继续。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canManual) NeutralGrey else WarnOrange,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.onLeaveManual()
                        showManualPad = false
                    },
                ) { Text("关闭") }
            },
        )
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

/** 当前事件类型（写入快照，用于预警信息） */
private fun currentEventLabel(state: TaskRunState?): String = when (state) {
    TaskRunState.RUNNING -> "任务执行中"
    TaskRunState.DONE -> "设备已到位"
    TaskRunState.FAILED -> "任务异常"
    TaskRunState.CANCELED -> "任务已中止"
    else -> "现场抓拍"
}
