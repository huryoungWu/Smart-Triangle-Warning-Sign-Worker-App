package com.example.myapplication.feature.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.di.DeviceTransport
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.feature.vehicle.VehicleViewModel
import java.util.Locale

/**
 * 设备信息（M10/M01）：设备编号、固件版本、连接方式、维护提示。
 * 电池百分比、远程升级待验收后开放（C 阶段）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceInfoScreen(
    vm: VehicleViewModel,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val transport = ServiceLocator.get(androidx.compose.ui.platform.LocalContext.current).transport

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("设备信息", onBack = onBack)

        SectionCard(title = "设备") {
            InfoRow("设备编号", snapshot?.deviceCode ?: "未连接")
            InfoRow("固件版本", snapshot?.fw ?: "-")
            InfoRow("连接方式", "本地 Wi-Fi · 192.168.4.1")
            InfoRow("通信模式", if (transport == DeviceTransport.FAKE) "本机模式" else "服务端设备")
            InfoRow(
                "最近状态更新",
                if (state.lastUpdateAt > 0) TimeFormat.format("HH:mm:ss", state.lastUpdateAt) else "-",
            )
        }

        SectionCard(title = "维护提示") {
            SafetyText("设备仅供封闭场地测试。")
            Spacer(Modifier.height(6.dp))
            SafetyText("车身实体急停不由 APP 替代。")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, fontWeight = FontWeight.Medium)
    }
}
