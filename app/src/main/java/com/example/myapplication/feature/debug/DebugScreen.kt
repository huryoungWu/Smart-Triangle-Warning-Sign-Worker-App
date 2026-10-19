package com.example.myapplication.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.data.device.FakeDeviceRepository
import com.example.myapplication.core.data.device.FakeFault
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.ui.DeviceStatusStrip
import com.example.myapplication.core.ui.LOCKED_FEATURE_NOTICE
import com.example.myapplication.core.ui.LockedFeatureCard
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatRow
import com.example.myapplication.core.ui.StatTextTileRow
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed

/**
 * 调试数据（M09）：左右轮脉冲、行程、速度、行程差；
 * 数据过期标注"非实时"；**自动运动中禁止计数清零**。
 * 附带 Fake 异常注入面板（仅模拟设备模式），供无真机验证异常分支。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    vm: VehicleViewModel,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val odo = state.snapshot?.odo
    val running = state.snapshot?.task?.state == TaskRunState.RUNNING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("调试数据", onBack = onBack)

        DeviceStatusStrip(
            state = state,
            refreshing = state.refreshing,
            onRefresh = vm::refreshStatus,
        )

        SectionCard(title = "实时数据（车轮编码器）") {
            if (state.isStale()) {
                StatusChip("非实时", WarnOrange, WarnOrangeBg)
                Spacer(Modifier.height(8.dp))
            }
            StatRow {
                StatTextTileRow("左轮脉冲", odo?.leftPulse?.toString() ?: "-", InfoBlue)
                StatTextTileRow("右轮脉冲", odo?.rightPulse?.toString() ?: "-", InfoBlue)
            }
            StatRow {
                StatTextTileRow("行程", "${"%.2f".format(odo?.traveledM ?: 0.0)} m", InfoBlue)
                StatTextTileRow("速度", "${"%.2f".format(odo?.speedMps ?: 0.0)} m/s", NeutralGrey)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("行程差", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    text = "${odo?.diffPulse ?: 0} pulse",
                    color = if ((odo?.diffPulse ?: 0) > 50) WarningRed else NeutralGrey,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            SafetyText("行程由车轮编码器估算，不代表地图定位、障碍检测或道路安全评分。")
        }

        SectionCard(title = "计数维护") {
            PrimaryActionButton(
                text = "计数清零",
                onClick = vm::resetOdo,
                enabled = !running && state.snapshot != null,
            )
            if (running) {
                Text(
                    "自动运动中禁止计数清零",
                    color = WarningRed,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // Fake 异常注入（仅模拟设备模式显示）
        FakeFaultPanel(vm)

        SectionCard(title = "传感器原始数据（C 阶段）") {
            LockedFeatureCard(
                title = "倾倒／障碍／边缘传感器",
                reason = LOCKED_FEATURE_NOTICE,
                onClick = { vm.showInfo("传感器原始数据", LOCKED_FEATURE_NOTICE) },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FakeFaultPanel(vm: VehicleViewModel) {
    val repo = com.example.myapplication.di.ServiceLocator.get(
        androidx.compose.ui.platform.LocalContext.current,
    ).deviceRepository

    if (repo !is FakeDeviceRepository) return

    SectionCard(title = "模拟设备异常注入（仅演示模式）") {
        Text(
            "用于无真机验证失联、超时、结果未知、编码器偏差等分支。",
            style = MaterialTheme.typography.bodySmall,
            color = NeutralGrey,
        )
        Spacer(Modifier.height(8.dp))
        val options = listOf(
            "正常" to FakeFault.None,
            "失联" to FakeFault.LinkLoss,
            "慢应答(超时)" to FakeFault.SlowAck(2500),
            "结果未知" to FakeFault.ResultUnknown,
            "编码器偏差" to FakeFault.OdoDeviation(80),
            "命令被拒" to FakeFault.CommandRejected,
        )
        var selected by remember { mutableStateOf("正常") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.take(3).forEach { (label, fault) ->
                FilterChip(
                    selected = selected == label,
                    onClick = {
                        selected = label
                        repo.fault = fault
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.drop(3).forEach { (label, fault) ->
                FilterChip(
                    selected = selected == label,
                    onClick = {
                        selected = label
                        repo.fault = fault
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}
