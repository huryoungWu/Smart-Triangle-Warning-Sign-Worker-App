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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    vm: VehicleViewModel,
    onEnterWork: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val connected = state.connection == ConnectionPhase.CONNECTED
    val connecting = state.connection == ConnectionPhase.CONNECTING

    val devices = state.deviceList
    val selected = devices.firstOrNull { it.id == state.selectedDeviceId } ?: devices.firstOrNull()
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设备连接") })
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
                                if (dev.id == state.selectedDeviceId) {
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
                    onClick = vm::connect,
                    enabled = !connecting && selected != null,
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

            if (selected?.available == false) {
                Spacer(Modifier.height(8.dp))
                SafetyText("该设备当前不可用，请选择其他设备后再连接。")
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
                text = "连接成功后方可进入作业",
                style = MaterialTheme.typography.labelSmall,
                color = WarnOrange,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
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
