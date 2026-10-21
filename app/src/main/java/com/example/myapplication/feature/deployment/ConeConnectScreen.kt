package com.example.myapplication.feature.deployment

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.deployment.DeploymentDeviceSummary
import com.example.myapplication.core.model.deployment.DeploymentDeviceType
import com.example.myapplication.core.model.deployment.DeploymentLimits
import com.example.myapplication.core.model.deployment.SelectedSlave
import com.example.myapplication.core.ui.EmptyState
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.SkeletonDeviceRows
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.permission.rememberCameraPermissionState
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
 * 锥桶流程第一页：设备连接/绑定。
 *
 * 锥桶不需要三角牌那样的单设备连接会话；这里的“连接”指把主桶/从桶加入当前账号的候选列表，
 * 并选定本次布设的主桶与从桶顺序。事件信息、L/d、预检都在下一页「布设任务发布」完成。
 */
@Composable
fun ConeConnectScreen(
    vm: DeploymentViewModel,
    onBack: () -> Unit,
    onDevicesReady: () -> Unit,
    onOpenActiveDeployment: (String) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val draft = state.draft

    LaunchedEffect(Unit) {
        vm.reloadDevices()
        vm.loadActiveEvent(force = true)
    }

    var bindType by remember { mutableStateOf<DeploymentDeviceType?>(null) }
    var pendingUnbind by remember { mutableStateOf<DeploymentDeviceSummary?>(null) }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            text = "锥桶设备连接",
            subtitle = "1 主桶 + 0~${DeploymentLimits.N_MAX} 从桶 · 绑定后选择本次编队",
            onBack = onBack,
        )

        state.activeEvent?.let { event ->
            DeploymentActiveCard(
                event = event,
                onOpen = { onOpenActiveDeployment(event.deploymentId) },
            )
        }

        SectionCard(title = "选择主桶") {
            if (state.loading && state.masterCandidates.isEmpty()) {
                SkeletonDeviceRows()
            } else if (state.masterCandidates.isEmpty()) {
                EmptyState("暂无已绑定且可用的主桶。请先扫码或输入编号绑定主桶。")
            } else {
                state.masterCandidates.forEach { dev ->
                    DeviceSelectRow(
                        device = dev,
                        selected = draft.master?.deviceCode == dev.deviceCode,
                        onClick = { vm.selectMaster(dev.deviceCode) },
                        onUnbind = { pendingUnbind = dev },
                        radio = true,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryActionButton(
                "扫码 / 输入编号绑定主桶",
                onClick = { bindType = DeploymentDeviceType.MASTER_CONE },
            )
        }

        SectionCard(title = "选择从桶（${draft.n}/${DeploymentLimits.N_MAX}）") {
            if (draft.master == null) {
                Text("请先选择主桶。", color = NeutralGrey)
                return@SectionCard
            }
            if (state.loading && state.slaveCandidates.isEmpty()) {
                SkeletonDeviceRows()
            } else if (state.slaveCandidates.isEmpty()) {
                EmptyState("暂无已绑定且可用的从桶。请先扫码或输入编号绑定。")
            } else {
                val setName = draft.master?.setId ?: state.deviceSet?.setId
                val setSlaves = state.slaveCandidates.filter { setName != null && it.setId == setName }
                val otherSlaves = state.slaveCandidates.filterNot { setName != null && it.setId == setName }
                if (setSlaves.isNotEmpty()) {
                    Text("套装从桶（默认候选）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    setSlaves.forEach { dev ->
                        DeviceSelectRow(
                            device = dev,
                            selected = draft.slaves.any { it.device.deviceCode == dev.deviceCode },
                            onClick = { vm.toggleSlave(dev) },
                            onUnbind = { pendingUnbind = dev },
                            radio = false,
                        )
                    }
                }
                if (otherSlaves.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("其他已绑定从桶", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    otherSlaves.forEach { dev ->
                        DeviceSelectRow(
                            device = dev,
                            selected = draft.slaves.any { it.device.deviceCode == dev.deviceCode },
                            onClick = { vm.toggleSlave(dev) },
                            onUnbind = { pendingUnbind = dev },
                            radio = false,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryActionButton(
                "扫码 / 输入编号绑定从桶",
                onClick = { bindType = DeploymentDeviceType.SLAVE_CONE },
            )
        }

        if (draft.slaves.isNotEmpty()) {
            SectionCard(title = "编队顺序（从 1…从 ${draft.n}）") {
                draft.slaves.sortedBy { it.order }.forEachIndexed { idx, sel ->
                    SelectedSlaveRow(
                        sel = sel,
                        isFirst = idx == 0,
                        isLast = idx == draft.slaves.lastIndex,
                        onUp = { vm.moveSlave(sel.device.deviceCode, -1) },
                        onDown = { vm.moveSlave(sel.device.deviceCode, 1) },
                        onRemove = { vm.toggleSlave(sel.device) },
                    )
                }
            }
        }

        // 只要求主桶；从桶可为 0（仅主桶执行）
        val ready = draft.master != null
        PrimaryActionButton(
            text = "完成设备连接",
            onClick = { if (vm.validateDevices()) onDevicesReady() },
            enabled = ready && !state.busy,
            icon = Icons.Default.Check,
            loading = state.busy,
        )

        Spacer(Modifier.height(16.dp))
        SecondaryActionButton("刷新设备列表", onClick = vm::reloadDevices, enabled = !state.loading)

        Spacer(Modifier.height(24.dp))
    }

    bindType?.let { type ->
        BindDeviceDialog(
            deviceType = type,
            busy = state.busy,
            onDismiss = { bindType = null },
            onBind = { code, bindingCode ->
                vm.bindDevice(code, bindingCode, type)
                bindType = null
            },
        )
    }

    pendingUnbind?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingUnbind = null },
            title = { Text("解绑设备？") },
            text = {
                Text(
                    "将把 ${device.deviceCode} 从当前账号/单位解绑。\n\n" +
                        "正在执行任务或被进行中布设事件占用的设备不能解绑。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.unbindDevice(device.deviceCode)
                        pendingUnbind = null
                    },
                ) { Text("确认解绑") }
            },
            dismissButton = { TextButton(onClick = { pendingUnbind = null }) { Text("取消") } },
        )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceSelectRow(
    device: DeploymentDeviceSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onUnbind: () -> Unit,
    radio: Boolean,
) {
    val reason = device.unavailableReason()
    val enabled = device.selectable
    val battery = device.batteryPercent.coerceIn(0, 100)
    val batteryColor = when {
        battery >= 50 -> SafeGreen
        battery >= 20 -> WarnOrange
        else -> WarningRed
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (radio) {
                RadioButton(selected = selected, onClick = { if (enabled) onClick() }, enabled = enabled)
            } else {
                Checkbox(checked = selected, onCheckedChange = { if (enabled) onClick() }, enabled = enabled)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name.ifBlank { device.deviceCode },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = onUnbind) {
                        Text("解绑", color = WarningRed, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    text = device.deviceCode,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusChip(
                        if (device.online) "在线" else "离线",
                        if (device.online) SafeGreen else WarningRed,
                        if (device.online) SafeGreenBg else WarningRedBg,
                    )
                    device.setId?.let { setId ->
                        StatusChip("套装 $setId", InfoBlue, InfoBlueBg)
                    }
                    if (device.occupied) StatusChip("被占用", WarnOrange, WarnOrangeBg)
                    if (device.activeTaskId != null) StatusChip("任务中", WarnOrange, WarnOrangeBg)
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "电量 ${device.batteryText}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = batteryColor,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(NeutralGreyBg),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(battery / 100f)
                                .fillMaxHeight()
                                .background(batteryColor),
                        )
                    }
                }
                if (reason != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningRed,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedSlaveRow(
    sel: SelectedSlave,
    isFirst: Boolean,
    isLast: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip("从 ${sel.order}", InfoBlue, InfoBlueBg)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(sel.device.name.ifBlank { sel.device.deviceCode }, fontWeight = FontWeight.Bold)
            Text(sel.device.deviceCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onUp, enabled = !isFirst) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
        }
        IconButton(onClick = onDown, enabled = !isLast) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "移除", tint = WarningRed)
        }
    }
}

@Composable
private fun BindDeviceDialog(
    deviceType: DeploymentDeviceType,
    busy: Boolean,
    onDismiss: () -> Unit,
    onBind: (String, String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var bindingCode by remember { mutableStateOf("") }
    val cameraPermission = rememberCameraPermissionState()
    val scanLauncher = rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        val contents = result.contents?.trim().orEmpty()
        if (contents.isNotEmpty()) {
            code = parseScannedCode(contents)
        }
    }

    fun launchScan() {
        if (cameraPermission.granted) {
            scanLauncher.launch(
                com.journeyapps.barcodescanner.ScanOptions()
                    .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setPrompt("将设备二维码对准取景框"),
            )
        } else {
            cameraPermission.onRequestResult = { granted ->
                if (granted) launchScan()
            }
            cameraPermission.request()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("绑定${deviceType.label}") },
        text = {
            Column {
                Text(
                    "device_type = ${deviceType.token}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(32) },
                    label = { Text("设备编号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bindingCode,
                    onValueChange = { bindingCode = it.take(32) },
                    label = { Text("绑定码（设备铭牌上）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = ::launchScan, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                    Text("扫码填写设备编号")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "绑定只是加入可选列表；绑定成功后请勾选加入本次布设。",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onBind(code.trim(), bindingCode.trim()) },
                enabled = !busy && code.isNotBlank() && bindingCode.isNotBlank(),
            ) { Text(if (busy) "绑定中…" else "绑定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 二维码内容兼容纯编号与 JSON（取 device_code）；解析失败按纯文本处理 */
private fun parseScannedCode(raw: String): String {
    val text = raw.trim()
    if (!text.startsWith("{")) return text
    return runCatching {
        org.json.JSONObject(text).optString("device_code").takeIf { it.isNotBlank() } ?: text
    }.getOrDefault(text)
}
