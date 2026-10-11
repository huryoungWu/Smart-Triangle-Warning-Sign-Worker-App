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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.vehicle.DistancePreset
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.data.prefs.SettingsRepository
import com.example.myapplication.core.ui.LOCKED_FEATURE_NOTICE
import com.example.myapplication.core.ui.LockedFeatureCard
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.permission.rememberLocationPermissionState
import com.example.myapplication.di.LocationSource
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.NeutralGreyBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.feature.vehicle.VehicleViewModel
import kotlinx.coroutines.launch

/**
 * 设置（M10）：默认距离、启动提示时间、默认灯光模式。
 * **修改设置不立即启动车辆**，车端仍校验参数范围及启动条件。
 */
@Composable
fun SettingsScreen(
    vm: VehicleViewModel,
    onOpenDeviceInfo: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { ServiceLocator.get(context).settingsRepository }

    val savedDistance by settings.defaultDistance.collectAsStateWithLifecycle(initialValue = DistancePreset.M1)
    val savedPrompt by settings.promptSeconds.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_PROMPT_SECONDS)
    val savedLight by settings.defaultLight.collectAsStateWithLifecycle(initialValue = LightMode.OFF)

    var distance by remember(savedDistance) { mutableStateOf(savedDistance) }
    var prompt by remember(savedPrompt) { mutableStateOf(savedPrompt) }
    var light by remember(savedLight) { mutableStateOf(savedLight) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionCard(title = "默认参数") {
            Text("默认距离档", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DistancePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = distance == preset,
                        onClick = { distance = preset },
                        label = { Text(preset.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("启动提示时间（秒）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10).forEach { sec ->
                    FilterChip(
                        selected = prompt == sec,
                        onClick = { prompt = sec },
                        label = { Text("$sec 秒") },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("默认灯光模式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LightMode.entries.forEach { mode ->
                    FilterChip(
                        selected = light == mode,
                        onClick = { light = mode },
                        label = { Text(mode.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            PrimaryActionButton(
                text = "保存设置",
                onClick = {
                    scope.launch {
                        settings.saveDefaultDistance(distance)
                        settings.savePromptSeconds(prompt)
                        settings.saveDefaultLight(light)
                        vm.showInfo(
                            "设置已保存",
                            "设置已保存到本机。\n\n注意：**保存设置不会启动车辆**，车端仍会校验参数范围与启动条件。",
                        )
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            SafetyText("保存设置不会启动车辆；车端仍校验参数范围及启动条件。")
        }

        SectionCard(title = "设备与调试") {
            SecondaryActionButton(text = "设备信息", onClick = onOpenDeviceInfo)
            Spacer(Modifier.height(8.dp))
            SecondaryActionButton(text = "调试数据", onClick = onOpenDebug)
        }

        // 权限与定位（FR-502 对应：真实权限状态，不再是模拟）
        SectionCard(title = "权限与定位") {
            val permission = rememberLocationPermissionState()
            val locationSource = ServiceLocator.get(LocalContext.current).locationSource

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("定位权限", modifier = Modifier.weight(1f))
                when {
                    permission.granted && permission.coarseOnly ->
                        StatusChip("已授权 · 仅大致位置", WarnOrange, WarnOrangeBg)

                    permission.granted -> StatusChip("已授权", SafeGreen, SafeGreenBg)
                    else -> StatusChip("未授权", WarnOrange, WarnOrangeBg)
                }
            }

            Spacer(Modifier.height(8.dp))
            if (permission.granted) {
                SecondaryActionButton(text = "去系统设置", onClick = permission::openAppSettings)
            } else {
                PrimaryActionButton(text = "申请定位权限", onClick = permission::request)
                Spacer(Modifier.height(6.dp))
                SecondaryActionButton(text = "去系统设置", onClick = permission::openAppSettings)
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("定位数据来源", modifier = Modifier.weight(1f))
                Text(
                    text = if (locationSource == LocationSource.AMAP) "真实高德定位" else "模拟轨迹（演示）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(8.dp))
            SafetyText("定位仅用于地图显示作业人员位置，不用于任何安全判断。")
        }

        SectionCard(title = "待验收能力（C 阶段）") {
            LockedFeatureCard(
                title = "电量显示",
                reason = "待可靠数据来源及车端能力验收后开放",
                onClick = { vm.showInfo("电量显示", "电池百分比须待可靠数据来源及车端能力验收后开放。") },
            )
            Spacer(Modifier.height(8.dp))
            LockedFeatureCard(
                title = "远程升级",
                reason = "待车端能力验收后开放",
                onClick = { vm.showInfo("远程升级", "远程升级须待车端能力验收后开放。") },
            )
            Spacer(Modifier.height(8.dp))
            LockedFeatureCard(
                title = "云端记录 / 多设备管理 / 远程调度",
                reason = LOCKED_FEATURE_NOTICE,
                onClick = { vm.showInfo("待验收能力", LOCKED_FEATURE_NOTICE) },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
