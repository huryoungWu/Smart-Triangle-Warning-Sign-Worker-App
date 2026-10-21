package com.example.myapplication.feature.deployment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.DeploymentConeMapPin
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.geo.resolveDeploymentConePins
import com.example.myapplication.core.model.deployment.CommandResultState
import com.example.myapplication.core.model.deployment.DeploymentDeviceState
import com.example.myapplication.core.model.deployment.DeploymentEvent
import com.example.myapplication.core.model.deployment.DeploymentPhase
import com.example.myapplication.core.model.deployment.DeploymentResult
import com.example.myapplication.core.model.deployment.DeploymentDeviceType
import com.example.myapplication.core.model.deployment.DeploymentState
import com.example.myapplication.core.model.deployment.ReportResultState
import com.example.myapplication.core.model.deployment.SkipDisposition
import com.example.myapplication.core.model.deployment.VerifyResultState
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.core.ui.ConfirmDialog
import com.example.myapplication.core.ui.DangerActionButton
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.RemoteControlPad
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.VideoStreamView
import com.example.myapplication.core.ui.map.MapPin
import com.example.myapplication.core.ui.map.MapPolyline
import com.example.myapplication.core.ui.map.PhoneMapPanel
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.core.util.udpVideoUrl
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.feature.map.MapViewModel
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
import kotlinx.coroutines.awaitCancellation
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 锥桶协同布设执行页：1Hz 聚合状态、逐设备卡片、异常处置、逐只核验。
 *
 * 页面上的「人工跳过」只在异常阻断时出现，且必须完成现场确认与二次确认。
 */
@Composable
fun DeploymentExecScreen(
    vm: DeploymentViewModel,
    mapVm: MapViewModel,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val event = state.event
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val serverUrl by sl.settingsRepository.serverBaseUrl
        .collectAsStateWithLifecycle(initialValue = sl.serverBaseUrl)

    LaunchedEffect(Unit) {
        if (vm.uiState.value.event == null) vm.loadActiveEvent()
    }

    // 页面可见时 1Hz 聚合状态；切后台/离开停止
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.startPolling()
            try {
                awaitCancellation()
            } finally {
                // 切后台/离开页面：停止手动保活（事件保持暂停，不自动 resume）
                vm.onExecScreenStopped()
                vm.stopPolling()
            }
        }
    }

    // ── 锥桶地图锚点：优先取事件开始时刻附近的手机定位，其次实时手机位置 ──
    val mapState by mapVm.uiState.collectAsStateWithLifecycle()
    val phoneFix by mapVm.phoneFix.collectAsStateWithLifecycle()
    var coneAnchor by remember(event?.deploymentId) { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(event?.deploymentId, event?.startedAt, phoneFix, mapState.phoneTrail) {
        if (coneAnchor == null && event != null) {
            val startedAt = event.startedAt ?: 0L
            val nearest = if (startedAt > 0) {
                mapState.phoneTrail.minByOrNull { abs(it.at - startedAt) }
            } else {
                null
            }
            // 进行中事件要求轨迹点足够新，避免用很久以前的点当起点；历史事件可放宽
            val window = if (event.terminal) ANCHOR_HISTORY_WINDOW_MS else ANCHOR_LIVE_WINDOW_MS
            val nearEnough = nearest != null && abs(nearest.at - startedAt) <= window
            coneAnchor = when {
                nearEnough -> nearest!!.point
                // 历史事件没有当时的手机定位时，不拿当前位置硬凑
                !event.terminal -> phoneFix?.point
                else -> null
            }
        }
    }
    val conePositions = remember(event, coneAnchor) {
        val anchor = coneAnchor
        if (event == null || anchor == null) {
            emptyList()
        } else {
            resolveDeploymentConePins(event, anchor)
        }
    }
    val conePins = remember(conePositions) {
        conePositions.map { pin ->
            MapPin(
                id = "cone:${pin.deviceCode}",
                point = pin.point,
                title = if (pin.isMaster) "主桶 ${pin.deviceCode}" else "从 ${pin.order} ${pin.deviceCode}",
                snippet = buildString {
                    append("已行进 ${fmt(pin.traveledM)} m / 目标 ${fmt(pin.targetM)} m")
                    if (pin.arrived) append(" · 已到位")
                    if (pin.skipped) append(" · 已跳过")
                    append(" · 朝向 ${pin.headingDeg.roundToInt()}°（位置为估算）")
                },
                colorArgb = conePinColor(pin),
            )
        }
    }

    // 运行轨迹：按 1Hz 状态采样各桶位置，过滤静止抖动并限制长度
    val coneTrails = remember(event?.deploymentId) {
        mutableStateMapOf<String, List<GeoPoint>>()
    }
    LaunchedEffect(event?.deploymentId, coneAnchor, conePositions) {
        if (event == null || coneAnchor == null) return@LaunchedEffect
        conePositions.forEach { pin ->
            val current = coneTrails[pin.deviceCode].orEmpty()
            val last = current.lastOrNull()
            if (last == null ||
                CoordTransform.distanceM(last, pin.point) >= CONE_TRAIL_MIN_STEP_M
            ) {
                coneTrails[pin.deviceCode] = (current + pin.point).takeLast(CONE_TRAIL_MAX_POINTS)
            }
        }
    }
    val coneTrailLines = conePositions.mapNotNull { pin ->
        val points = coneTrails[pin.deviceCode].orEmpty()
        if (points.size < 2) {
            null
        } else {
            MapPolyline(
                id = "cone-trail:${pin.deviceCode}",
                points = points,
                colorArgb = conePinColor(pin),
                width = 10f,
            )
        }
    }

    // 手指按在地图上时暂停整页滚动，避免滑地图变成滑页面
    var mapTouchActive by remember { mutableStateOf(false) }
    var showPauseConfirm by remember { mutableStateOf(false) }
    var showAbortConfirm by remember { mutableStateOf(false) }
    var showRetryConfirm by remember { mutableStateOf(false) }
    var showManualConfirm by remember { mutableStateOf(false) }
    var showResumeConfirm by remember { mutableStateOf(false) }
    var skipTarget by remember { mutableStateOf<DeploymentDeviceState?>(null) }
    var verifyTarget by remember { mutableStateOf<DeploymentDeviceState?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = !mapTouchActive)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            text = event?.name ?: "布设执行",
            subtitle = event?.let {
                "L=${fmt(it.params.L)} m · d=${fmt(it.params.d)} m · N=${it.params.N}" +
                    (it.lane?.let { lane -> " · 第 $lane 车道" } ?: "")
            },
            onBack = onBack,
        )

        if (event == null) {
            SectionCard(title = "暂无布设事件") {
                Text("当前没有可展示的布设事件。", color = NeutralGrey)
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton("刷新", onClick = vm::refreshEvent)
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        EventOverviewCard(event)

        // 紧急操作：放在首屏显眼位置，避免现场着急时还要向下翻找
        if (event.state == DeploymentState.RUNNING || event.state == DeploymentState.PAUSED) {
            Button(
                onClick = {
                    if (event.state == DeploymentState.RUNNING) {
                        showManualConfirm = true
                    } else {
                        vm.enterManualMode()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WarnOrange,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = "单桶手动干预",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 主/从桶实时位置（无逐桶 GPS，按各桶 traveledM 沿正北方向估算）
        if (coneAnchor != null || !event.terminal) {
            key(event.deploymentId) {
                PhoneMapPanel(
                    mapVm = mapVm,
                    height = 220.dp,
                    title = "主/从桶实时位置",
                    extraMarkers = conePins,
                    extraPolylines = coneTrailLines,
                    showPhoneMarker = false,
                    cameraCenter = coneAnchor,
                    followPhone = false,
                    onTouchActiveChange = { mapTouchActive = it },
                    caption = if (coneAnchor == null) {
                        "暂无手机定位锚点，无法估算锥桶位置；请允许定位后重试。"
                    } else {
                        "蓝点=主桶，橙点=从桶，绿点=已到位，灰点=已跳过；连线为运行轨迹；按各桶位置/朝向估算（旧数据回退正北投影，非 GPS）。"
                    },
                )
            }
        }

        // 主桶实时视频：复用三角架同一套 UDP 视频接口（逻辑接口不变）
        val masterCode = event.master?.deviceCode
        if (!event.terminal && masterCode != null) {
            val token = sl.authRepository.currentToken
            val target = buildString {
                append("/api/v1/device/video/stream?device_code=").append(masterCode)
                if (!token.isNullOrBlank()) {
                    append("&token=").append(URLEncoder.encode(token, "UTF-8"))
                }
            }
            val videoUrl = udpVideoUrl(serverUrl, target)
            SectionCard(title = "主桶实时视频") {
                VideoStreamView(
                    url = videoUrl,
                    contentDescription = "主桶实时视频",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "主桶 ${masterCode} 实时视频（UDP，接口同三角架实时视频）",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
        }

        // 异常横幅：一旦出现必须显性展示
        if (event.anomalies.isNotEmpty()) {
            SectionCard(title = "异常（系统不会自动跳过）") {
                event.anomalies.forEach { a ->
                    Text(
                        "• ${a.deviceCode ?: ""} ${a.label}（${a.code}）",
                        color = WarningRed,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "请先暂停/现场处置，再按安全流程重试；无法恢复时可人工跳过当前从桶（仅异常阻断时）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarningRed,
                )
            }
        }

        // 当前链路下一步
        event.nextExpected?.let { next ->
            SectionCard(title = "当前链路") {
                val text = when (next.type) {
                    "trigger" -> "等待主桶自身行程达到 ${fmt(next.thresholdM)} m 后触发 ${next.afterReportTrigger ?: "从 1"}"
                    "report" -> "等待 ${next.deviceCode} 自身行程达到 ${fmt(next.thresholdM)} m 后上报" +
                        (next.afterReportTrigger?.let { "，随后触发 $it" } ?: "")
                    else -> "等待 ${next.deviceCode}"
                }
                Text(text, color = InfoBlue, fontWeight = FontWeight.Bold)
                Text(
                    "注意：是设备自身行程达到 d，不是主桶走到 (k+1)×d；从桶之间不直接发启动指令。",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
        }

        SectionCard(title = "设备状态（主桶在前，从桶按顺序）") {
            event.ordered.forEach { dev ->
                DeviceStateCard(
                    device = dev,
                    eventState = event.state,
                    onVerify = { verifyTarget = dev },
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        // 暂停全部：仅 COMPLETED 不显示；RUNNING/STARTING 二次确认，其余状态直接执行/提示
        if (event.state != DeploymentState.COMPLETED) {
            PrimaryActionButton(
                text = "暂停全部",
                onClick = {
                    if (event.state == DeploymentState.RUNNING || event.state == DeploymentState.STARTING) {
                        showPauseConfirm = true
                    } else {
                        vm.pauseAll()
                    }
                },
                enabled = !state.busy,
            )
            Spacer(Modifier.height(8.dp))
        }

        // 操作区
        when (event.state) {
            DeploymentState.RUNNING -> {
                if (canRetry(event)) {
                    SecondaryActionButton("重试当前步骤", onClick = { showRetryConfirm = true })
                    Spacer(Modifier.height(8.dp))
                }
                val blocked = blockedDevice(event)
                if (blocked != null && blocked.deviceType != DeploymentDeviceType.MASTER_CONE) {
                    DangerActionButton(
                        text = "人工跳过 ${blocked.deviceCode}（受控）",
                        onClick = { skipTarget = blocked },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "人工跳过会绕过逐级启动链路；仅在已现场确认设备停止/移出、下一只路径安全时使用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningRed,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SecondaryActionButton("终止事件", onClick = { showAbortConfirm = true })
            }

            DeploymentState.PAUSED -> {
                PrimaryActionButton("继续", onClick = { showResumeConfirm = true })
                Spacer(Modifier.height(8.dp))
                DangerActionButton("终止事件", onClick = { showAbortConfirm = true })
            }

            DeploymentState.PENDING_VERIFY, DeploymentState.VERIFY_FAILED -> {
                Text(
                    if (event.allVerified) "全部设备核验通过，可以完成布设。" else "请逐只核对实际位置/处置方式并完成核验。",
                    color = if (event.allVerified) SafeGreen else WarnOrange,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                PrimaryActionButton(
                    text = if (event.hasSkip) "完成布设（带异常）" else "完成布设",
                    onClick = vm::complete,
                    enabled = event.allVerified && !state.busy,
                )
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton("终止事件", onClick = { showAbortConfirm = true })
            }

            DeploymentState.STARTING -> {
                SectionCard(title = "启动中") {
                    Text("正在等待主桶确认启动指令。", color = InfoBlue)
                    Text(
                        "若长时间无确认，请刷新状态；系统不会自动重发运动指令。",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralGrey,
                    )
                }
                SecondaryActionButton("刷新状态", onClick = vm::refreshEvent)
                Spacer(Modifier.height(8.dp))
                DangerActionButton("终止事件", onClick = { showAbortConfirm = true })
            }

            DeploymentState.COMPLETED, DeploymentState.ABORTED, DeploymentState.FAILED -> {
                SecondaryActionButton("刷新记录", onClick = vm::refreshEvent)
            }

            else -> {
                SecondaryActionButton("刷新状态", onClick = vm::refreshEvent)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "最后更新：${event.updatedAt.takeIf { it > 0 }?.let { TimeFormat.hhmmss(it) } ?: "-"}",
            style = MaterialTheme.typography.labelSmall,
            color = NeutralGrey,
        )
        if (state.busy) {
            Text("处理中…", style = MaterialTheme.typography.labelSmall, color = InfoBlue)
        }
    }

    val manualEvent = event
    if (showManualConfirm) {
        ConfirmDialog(
            title = "进入手动干预？",
            message = "将先暂停全部锥桶，再对选中的一只进行点动。手动移动计入该桶行程；完成后需手动「继续布设」才恢复自动链路。",
            confirmText = "暂停并进入",
            onConfirm = {
                showManualConfirm = false
                vm.enterManualMode()
            },
            onDismiss = { showManualConfirm = false },
        )
    }
    if (state.manualMode && manualEvent != null) {
        ManualJogDialog(
            state = state,
            event = manualEvent,
            onSelectDevice = vm::selectManualDevice,
            onHoldStart = vm::onHoldStart,
            onHoldEnd = vm::onHoldEnd,
            onStop = vm::onManualStop,
            onDismiss = vm::exitManualMode,
            onResume = {
                vm.exitManualMode()
                showResumeConfirm = true
            },
        )
    }
    if (showResumeConfirm) {
        ConfirmDialog(
            title = "继续布设？",
            message = "所有锥桶将恢复自动移动。请确认手动点动已停止、现场路径安全。",
            confirmText = "继续布设",
            onConfirm = {
                showResumeConfirm = false
                vm.resume()
            },
            onDismiss = { showResumeConfirm = false },
        )
    }

    if (showPauseConfirm) {
        ConfirmDialog(
            title = "暂停全部锥桶？",
            message = "将立即停止所有正在移动的锥桶，并冻结逐级启动。确认现场安全后可继续。",
            confirmText = "暂停全部",
            onConfirm = {
                showPauseConfirm = false
                vm.pauseAll()
            },
            onDismiss = { showPauseConfirm = false },
        )
    }

    if (showAbortConfirm) {
        ConfirmDialog(
            title = "终止本次布设？",
            message = "将停止所有锥桶并结束事件；离线设备需现场处置。事件不会标记为完成。",
            confirmText = "终止事件",
            onConfirm = {
                showAbortConfirm = false
                vm.abort("作业人员终止")
            },
            onDismiss = { showAbortConfirm = false },
        )
    }

    if (showRetryConfirm) {
        ConfirmDialog(
            title = "按安全流程重试？",
            message = "将复用同一指令 ID 重试当前阻断步骤；若设备已执行，不会二次移动。请先确认设备状态与现场安全。",
            confirmText = "重试",
            danger = false,
            onConfirm = {
                showRetryConfirm = false
                vm.retryCurrent()
            },
            onDismiss = { showRetryConfirm = false },
        )
    }

    skipTarget?.let { dev ->
        SkipDeviceDialog(
            device = dev,
            busy = state.busy,
            onDismiss = { skipTarget = null },
            onConfirm = { reason, disposition, note ->
                vm.skip(dev, reason, disposition, note)
                skipTarget = null
            },
        )
    }

    verifyTarget?.let { dev ->
        VerifyDeviceDialog(
            device = dev,
            busy = state.busy,
            onDismiss = { verifyTarget = null },
            onConfirm = { pass, note ->
                vm.verify(dev, pass, note)
                verifyTarget = null
            },
        )
    }

    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("操作未完成") },
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

@Composable
private fun EventOverviewCard(event: DeploymentEvent) {
    val (fg, bg) = when (event.state) {
        DeploymentState.COMPLETED -> SafeGreen to SafeGreenBg
        DeploymentState.ABORTED, DeploymentState.FAILED, DeploymentState.VERIFY_FAILED -> WarningRed to WarningRedBg
        DeploymentState.PAUSED, DeploymentState.PENDING_VERIFY -> WarnOrange to WarnOrangeBg
        else -> InfoBlue to InfoBlueBg
    }
    SectionCard(title = "事件状态与整体进度") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(event.state.label, fg, bg)
            if (event.result == DeploymentResult.COMPLETED_WITH_EXCEPTION) {
                Spacer(Modifier.width(6.dp))
                StatusChip("带异常完成", WarnOrange, WarnOrangeBg)
            }
            Spacer(Modifier.weight(1f))
            Text(event.deploymentId, style = MaterialTheme.typography.labelSmall, color = NeutralGrey)
        }
        Spacer(Modifier.height(10.dp))
        EventProgressBar(event)
        Spacer(Modifier.height(6.dp))
        Text(
            "已到达 ${event.arrivedCount}/${event.devices.size} · 已核验 ${event.verifiedCount}/${event.devices.size}" +
                if (event.hasSkip) " · 人工跳过 ${event.devices.count { it.skipped }}" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "主桶与从桶可同时移动；到达数量与核验数量分开统计。",
            style = MaterialTheme.typography.labelSmall,
            color = NeutralGrey,
        )
    }
}

@Composable
private fun EventProgressBar(event: DeploymentEvent) {
    val total = event.devices.size.coerceAtLeast(1)
    // 人工跳过的设备已完成处置，进度上计入“已结束”，但不计入“已到达”
    val done = event.devices.count { it.arrived || it.skipped }
    val fraction = done.toFloat() / total
    SimpleProgress(fraction, InfoBlue)
}

@Composable
private fun DeviceStateCard(
    device: DeploymentDeviceState,
    eventState: DeploymentState,
    onVerify: () -> Unit,
) {
    val canVerify = (eventState == DeploymentState.PENDING_VERIFY || eventState == DeploymentState.VERIFY_FAILED) &&
        device.verify.state == VerifyResultState.NOT_DONE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (device.anomaly != null) WarningRedBg else NeutralGreyBg.copy(alpha = 0.35f))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(
                if (device.isMaster) "主桶" else "从 ${device.order}",
                if (device.isMaster) InfoBlue else NeutralGrey,
                if (device.isMaster) InfoBlueBg else NeutralGreyBg,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name.ifBlank { device.deviceCode }, fontWeight = FontWeight.Bold)
                Text(device.deviceCode, style = MaterialTheme.typography.labelSmall, color = NeutralGrey)
            }
            StatusChip(
                if (device.online) device.phase.label else "离线",
                when {
                    !device.online || device.phase == DeploymentPhase.FAILED -> WarningRed
                    device.phase == DeploymentPhase.ARRIVED || device.phase == DeploymentPhase.NO_MOVE -> SafeGreen
                    device.phase == DeploymentPhase.SKIPPED_MANUAL -> WarnOrange
                    else -> InfoBlue
                },
                when {
                    !device.online || device.phase == DeploymentPhase.FAILED -> WarningRedBg
                    device.phase == DeploymentPhase.ARRIVED || device.phase == DeploymentPhase.NO_MOVE -> SafeGreenBg
                    device.phase == DeploymentPhase.SKIPPED_MANUAL -> WarnOrangeBg
                    else -> InfoBlueBg
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "目标 ${fmt(device.targetM)} m · 累计 ${fmt(device.traveledM)} m" +
                if (device.targetM <= 0.001) " · 无需移动" else "",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        SimpleProgress(device.progress, if (device.arrived) SafeGreen else InfoBlue)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append("已过启动间隔 d：").append(if (device.reachedInterval) "是" else "否")
                append(" · 已到达：").append(if (device.arrived) "是" else "否")
                if (device.skipped) append(" · 人工跳过：").append(device.skip?.disposition?.label ?: "待处置")
            },
            style = MaterialTheme.typography.labelSmall,
            color = NeutralGrey,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "启动指令：${device.startCommand.state.label}（${device.startCommand.commandId ?: "-"}）",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "上报结果：${if (device.isMaster) "—（主桶）" else device.report.state.label}" +
                if (device.isMaster && device.trigger != null) " · 触发：${device.trigger.state}" else "",
            style = MaterialTheme.typography.labelSmall,
        )
        if (device.verify.state != VerifyResultState.NOT_DONE) {
            Text(
                "核验：${device.verify.state.label}" + (device.verify.note?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (device.verify.state == VerifyResultState.PASS) SafeGreen else WarningRed,
            )
        }
        device.anomaly?.let {
            Text("异常：${it.label}（${it.code}）", style = MaterialTheme.typography.labelSmall, color = WarningRed)
        }
        if (canVerify) {
            Spacer(Modifier.height(6.dp))
            SecondaryActionButton("现场核验", onClick = onVerify)
        }
    }
}

@Composable
private fun SimpleProgress(progress: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(NeutralGreyBg),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color),
        )
    }
}

private fun canRetry(event: DeploymentEvent): Boolean =
    event.devices.any {
        it.startCommand.state == CommandResultState.TIMEOUT ||
            it.startCommand.state == CommandResultState.REJECTED ||
            it.phase == DeploymentPhase.FAILED ||
            it.report.state == ReportResultState.LOST
    }

private fun blockedDevice(event: DeploymentEvent): DeploymentDeviceState? {
    for (device in event.ordered) {
        if (device.phase == DeploymentPhase.FAILED ||
            device.startCommand.state == CommandResultState.TIMEOUT ||
            device.startCommand.state == CommandResultState.REJECTED ||
            device.report.state == ReportResultState.LOST
        ) {
            return device
        }
        if (!device.online &&
            device.phase != DeploymentPhase.ARRIVED &&
            device.phase != DeploymentPhase.SKIPPED_MANUAL &&
            device.phase != DeploymentPhase.NO_MOVE
        ) {
            // 尚未轮到的离线从桶不算“当前阻断”：只有前序已到 d/已到达/已跳过，
            // 或它自己已经启动，才视为当前步骤。
            val prev = event.ordered.firstOrNull { it.order == device.order - 1 }
            val predecessorReady = device.isMaster ||
                prev?.let { it.reachedInterval || it.arrived || it.skipped } == true
            if (device.phase != DeploymentPhase.NOT_STARTED || predecessorReady) return device
        }
    }
    return null
}

@Composable
private fun SkipDeviceDialog(
    device: DeploymentDeviceState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, SkipDisposition, String) -> Unit,
) {
    var stopped by remember { mutableStateOf(false) }
    var nextSafe by remember { mutableStateOf(false) }
    var understood by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var disposition by remember { mutableStateOf(SkipDisposition.REMOVED_FROM_ROUTE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("人工跳过 ${device.deviceCode}？") },
        text = {
            Column {
                Text(
                    "该操作仅在异常阻断时可用，且会绕过逐级启动链路；被跳过设备不计为到达，事件将记录为“带异常完成”。",
                    color = WarningRed,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                CheckboxRow("已确认该设备已停止或已移出运动路径", stopped) { stopped = !stopped }
                CheckboxRow("已确认下一只从桶的启动路径安全", nextSafe) { nextSafe = !nextSafe }
                CheckboxRow("已了解将记录为带异常完成", understood) { understood = !understood }
                Spacer(Modifier.height(6.dp))
                Text("处置方式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkipDisposition.entries.forEach { d ->
                        FilterChip(
                            selected = disposition == d,
                            onClick = { disposition = d },
                            label = { Text(d.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(100) },
                    label = { Text("跳过原因（必填）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(100) },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason.trim(), disposition, note) },
                enabled = !busy && stopped && nextSafe && understood && reason.isNotBlank(),
            ) { Text(if (busy) "处理中…" else "确认人工跳过") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VerifyDeviceDialog(
    device: DeploymentDeviceState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, String) -> Unit,
) {
    var pass by remember { mutableStateOf(true) }
    var note by remember {
        mutableStateOf(
            if (device.skipped) "已核对跳过处置方式" else "位置/朝向/间距/灯光正常",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("现场核验 ${device.deviceCode}") },
        text = {
            Column {
                Text(
                    if (device.skipped) "该设备为人工跳过，请核验其处置方式（人工摆放/移出路线/不参与）。"
                    else "请核对实际位置、朝向、间距、灯光与现场安全条件；App 数字不能替代现场核验。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = pass, onClick = { pass = true }, label = { Text("核验通过") })
                    FilterChip(selected = !pass, onClick = { pass = false }, label = { Text("发现异常") })
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = { Text("核验备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(pass, note) }, enabled = !busy) {
                Text(if (busy) "记录中…" else "提交核验")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ManualJogDialog(
    state: DeploymentUiState,
    event: DeploymentEvent,
    onSelectDevice: (String) -> Unit,
    onHoldStart: (MoveDir) -> Unit,
    onHoldEnd: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val selected = state.manualDeviceCode?.let(event::device)
    val canJog = event.state == DeploymentState.PAUSED &&
        !state.manualEntering && !state.busy && selected?.manualEligible == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("单桶手动干预") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(
                        onClick = { menuExpanded = true },
                        enabled = state.manualHoldingDir == null && !state.manualEntering,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            selected?.let { "${it.orderLabel} · ${it.deviceCode} · ${it.phase.label}" }
                                ?: "请选择要控制的锥桶",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Medium,
                        )
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "选择锥桶")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        event.ordered.forEach { device ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            "${device.orderLabel} · ${device.deviceCode}",
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            buildString {
                                                append(device.phase.label)
                                                append(" · 已行进 ${fmt(device.traveledM)}/${fmt(device.targetM)} m")
                                                if (device.manualActive) append(" · 手动中")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NeutralGrey,
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (device.deviceCode == selected?.deviceCode) {
                                        Text("✓", color = SafeGreen, fontWeight = FontWeight.Bold)
                                    }
                                },
                                onClick = {
                                    onSelectDevice(device.deviceCode)
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }

                selected?.let {
                    Text(
                        "目标 ${fmt(it.targetM)} m · 已行进 ${fmt(it.traveledM)} m · 朝向 ${it.headingDeg.roundToInt()}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                RemoteControlPad(
                    enabled = canJog,
                    holdingDir = state.manualHoldingDir,
                    onHoldStart = onHoldStart,
                    onHoldEnd = onHoldEnd,
                    onStopClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = when {
                        state.manualEntering -> "正在暂停全部锥桶…"
                        event.state != DeploymentState.PAUSED -> "等待全部锥桶暂停…"
                        selected == null -> "请选择要控制的锥桶"
                        !selected.online -> "所选桶离线，不能点动"
                        selected.skipped -> "所选桶已跳过"
                        selected.arrived -> "所选桶已到位"
                        selected.phase != DeploymentPhase.STOPPED -> "所选桶当前状态 ${selected.phase.label}，不能点动"
                        selected.traveledM >= selected.targetM - 1e-9 -> "目标已达成，继续布设后确认"
                        else -> "按住方向键点动，松手停车；仅所选桶动作。手动移动计入该桶行程。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canJog) NeutralGrey else WarnOrange,
                )
                Text(
                    "手动干预期间布设已暂停；关闭后保持暂停，需点「继续布设」恢复自动链路。",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onResume,
                enabled = event.state == DeploymentState.PAUSED && !state.busy,
            ) { Text("继续布设") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭（保持暂停）") }
        },
    )
}

@Composable
private fun CheckboxRow(text: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** 进行中事件：手机轨迹点与开始时刻的匹配窗口；超过则用当前手机位置 */
private const val ANCHOR_LIVE_WINDOW_MS = 2 * 60 * 1000L
/** 历史事件：允许从轨迹里找更早的起点；找不到就不显示估算点位 */
private const val ANCHOR_HISTORY_WINDOW_MS = 30 * 60 * 1000L
/** 锥桶轨迹最小采样距离（米），过滤 1Hz 轮询下的静止抖动 */
private const val CONE_TRAIL_MIN_STEP_M = 0.5
/** 单只桶最多保留的轨迹点数 */
private const val CONE_TRAIL_MAX_POINTS = 500

private fun conePinColor(pin: DeploymentConeMapPin): Int = when {
    pin.skipped -> NeutralGrey.toArgb()
    pin.arrived -> SafeGreen.toArgb()
    pin.isMaster -> InfoBlue.toArgb()
    else -> WarnOrange.toArgb()
}

private fun fmt(v: Double): String =
    if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.1f", v)
