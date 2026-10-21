package com.example.myapplication.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.DeviceSummary
import com.example.myapplication.core.ui.AppIcons
import androidx.compose.runtime.LaunchedEffect
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.permission.rememberCameraPermissionState
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.NeutralGreyBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/**
 * 设备连接页（独立一页，主流程第一站）。
 *
 * 默认**断开状态**；需从下拉菜单**选择自己的设备**（可能一台或多台），
 * 每台显示状态：是否开始、是否可用、电量。只有连接成功后才能进入「开始作业」页。
 *
 * **连接即绑定**：点击「连接设备」时会一并上报登录账号/密码（登录后自动带入）、设备编号
 * （`device_code`）、**用户输入的绑定码**（`binding_code`）与设备类型（`device_type`，
 * 默认常量 `triangle`）；绑定码为空时按钮置灰，校验失败保持未连接并原样提示原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    vm: VehicleViewModel,
    onBack: () -> Unit,
    onEnterWork: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val connected = state.connection == ConnectionPhase.CONNECTED
    val connecting = state.connection == ConnectionPhase.CONNECTING

    // 进入三角牌流程时按当前登录身份刷新一次设备列表
    LaunchedEffect(Unit) { vm.reloadDevices() }

    val devices = state.deviceList
    val selected = devices.firstOrNull { it.id == state.selectedDeviceCode } ?: devices.firstOrNull()
    // 用户添加的设备（本机登记，可在「添加设备」弹窗内手动删除）
    val customDevices by vm.customDevices.collectAsStateWithLifecycle(initialValue = emptyList())
    var menuExpanded by remember { mutableStateOf(false) }

    // 绑定码（binding_code）：连接设备的必填项，由用户输入（设备铭牌上）
    var bindingCode by remember { mutableStateOf("") }

    // ── 添加设备（手动编号 / 扫码）──
    var showAddDevice by remember { mutableStateOf(false) }
    var addByScan by remember { mutableStateOf(false) }
    var newDeviceCode by remember { mutableStateOf("") }
    val cameraPermission = rememberCameraPermissionState()
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        val contents = result.contents?.trim()
        if (!contents.isNullOrEmpty()) {
            // 识别成功：扫码相机界面由扫码库自动关闭，这里同步收起「添加设备」弹窗，全流程自动退出
            vm.addDevice(contents)
            newDeviceCode = ""
            showAddDevice = false
        }
    }

    // 打开扫码相机（识别成功后相机界面自动关闭、弹窗自动收起，无需手动退出）
    fun launchScan() {
        scanLauncher.launch(
            com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setBeepEnabled(false)
                .setPrompt("将设备二维码对准取景框"),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("设备连接", subtitle = "三脚架布设", onBack = onBack)

        Text(
            "请选择设备并连接，连接成功后开始作业",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard(title = "选择设备") {
            // 下拉菜单选择设备
            Box {
                OutlinedButton(
                    onClick = { if (!connected) menuExpanded = true },
                    enabled = !connected && devices.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = selected?.let { "${it.name}（${it.id}）" } ?: "暂无可用设备",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "展开设备列表")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    devices.forEach { dev ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(dev.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${dev.id} · 电量 ${dev.batteryText}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingIcon = {
                                if (dev.id == state.selectedDeviceCode) {
                                    Text("✓", color = SafeGreen, fontWeight = FontWeight.Bold)
                                }
                            },
                            onClick = {
                                vm.selectDevice(dev.id)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }

            // 所选设备的状态
            if (selected != null) {
                Spacer(Modifier.height(14.dp))
                DeviceStatusRow("是否开始", selected.started, if (selected.started) "已开始" else "未开始")
                DeviceStatusRow("是否可用", selected.available, if (selected.available) "可用" else "不可用")
                DeviceStatusRow(
                    "电量",
                    ok = selected.batteryPercent >= 20,
                    text = selected.batteryText,
                )
            }

            // 绑定码（binding_code）：**连接设备必需**，由用户输入（设备铭牌上）
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = bindingCode,
                onValueChange = { bindingCode = it },
                label = { Text("绑定码") },
                singleLine = true,
                enabled = !connected,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "请输入设备铭牌上的绑定码；连接时与登录账号、设备编号一并校验。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            if (connected) {
                OutlinedButton(
                    onClick = vm::disconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Disconnect, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("断开连接")
                }
            } else {
                Button(
                    onClick = { vm.connect(bindingCode) },
                    enabled = !connecting && selected != null && bindingCode.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (connecting) Icons.Default.Refresh else AppIcons.Connect,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (connecting) "连接中…" else "连接设备")
                }
            }

            // 手动刷新设备状态：已连接或失联（状态未确认）时可用。
            // 初次状态读取失败后，可在这里重试，无需重新绑定。
            if (state.connection == ConnectionPhase.CONNECTED ||
                state.connection == ConnectionPhase.LOST
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::refreshStatus,
                    enabled = !state.refreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.refreshing) "刷新中…" else "刷新状态")
                }
            }

            if (!connected && bindingCode.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "请先输入绑定码，再连接设备",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnOrange,
                )
            }

            if (selected?.available == false) {
                Spacer(Modifier.height(8.dp))
                SafetyText("该设备当前不可用，请选择其他设备后再连接。")
            }

            // 添加设备：设备编号 或 扫码
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { showAddDevice = true },
                enabled = !connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加设备（编号／扫码）")
            }
        }

        Spacer(Modifier.height(28.dp))

        // 连接门：只有连接成功才能进入下一页
        PrimaryActionButton(
            text = "进入作业",
            onClick = onEnterWork,
            enabled = connected,
        )
        if (!connected) {
            Text(
                text = "连接成功后方可进入单设备作业",
                style = MaterialTheme.typography.labelSmall,
                color = WarnOrange,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    // ── 添加设备对话框：手动输入设备编号 / 扫码（二选一）──
    if (showAddDevice) {
        AlertDialog(
            onDismissRequest = { showAddDevice = false },
            title = { Text("添加设备") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !addByScan,
                            onClick = { addByScan = false },
                            label = { Text("输入编号") },
                        )
                        FilterChip(
                            selected = addByScan,
                            onClick = { addByScan = true },
                            label = { Text("扫码") },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (addByScan) {
                        Text(
                            "用相机扫描设备机身上的二维码，识别后自动加入设备列表。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        PrimaryActionButton(
                            text = "打开扫码相机",
                            onClick = {
                                if (cameraPermission.granted) {
                                    launchScan()
                                } else {
                                    // 首次授权后无需再点一次：拿到相机权限即自动进入扫码相机
                                    cameraPermission.onRequestResult = { ok -> if (ok) launchScan() }
                                    cameraPermission.request()
                                }
                            },
                        )
                        if (!cameraPermission.granted) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "扫码需要相机权限；未授权时可切到「输入编号」手动添加。",
                                style = MaterialTheme.typography.labelSmall,
                                color = WarnOrange,
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = newDeviceCode,
                            onValueChange = { newDeviceCode = it },
                            label = { Text("设备编号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "输入设备铭牌上的编号，或用「扫码」方式读取。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 已添加的设备：手动编号/扫码登记在本机，可逐台手动删除
                    if (customDevices.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "已添加的设备（本机）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        customDevices.forEach { dev ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 2.dp),
                                ) {
                                    Text(
                                        dev.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "本机添加 · 可手动删除",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { vm.removeDevice(dev.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = WarningRed,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("删除", color = WarningRed)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addDevice(newDeviceCode)
                        newDeviceCode = ""
                        showAddDevice = false
                    },
                    enabled = newDeviceCode.isNotBlank(),
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDevice = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DeviceStatusRow(label: String, ok: Boolean, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
        )
        StatusChip(
        text = text,
        fg = if (ok) SafeGreen else WarnOrange,
        bg = if (ok) SafeGreenBg else WarnOrangeBg,
        )
    }
}
