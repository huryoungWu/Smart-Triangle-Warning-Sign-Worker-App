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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.core.ui.LOCKED_FEATURE_NOTICE
import com.example.myapplication.core.ui.LockedFeatureCard
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            SectionCard(title = "设备") {
                InfoRow("设备编号", snapshot?.deviceId ?: "未连接")
                InfoRow("固件版本", snapshot?.fw ?: "-")
                InfoRow("连接方式", "本地 Wi-Fi · 192.168.4.1")
                InfoRow("通信模式", if (transport == DeviceTransport.FAKE) "模拟设备（演示）" else "真实设备（HTTP）")
                InfoRow(
                    "最近状态更新",
                    if (state.lastUpdateAt > 0) TimeFormat.format("HH:mm:ss", state.lastUpdateAt) else "-",
                )
            }

            SectionCard(title = "维护提示") {
                SafetyText("样机仅供有人看护的室内或封闭场地测试。")
                Spacer(Modifier.height(6.dp))
                SafetyText("不具备自动识别道路安全位置、障碍或来车的能力；作业前须人工现场确认。")
                Spacer(Modifier.height(6.dp))
                SafetyText("车身实体急停不由 APP 软件按钮替代。")
            }

            SectionCard(title = "待验收能力（C 阶段）") {
                LockedFeatureCard(
                    title = "电池百分比",
                    reason = "待可靠数据来源验收后开放",
                    onClick = { vm.showInfo("电池百分比", "电池百分比须待可靠数据来源及车端能力验收后开放。") },
                )
                Spacer(Modifier.height(8.dp))
                LockedFeatureCard(
                    title = "远程升级",
                    reason = "待车端能力验收后开放",
                    onClick = { vm.showInfo("远程升级", "远程升级须待车端能力验收后开放。") },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
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
