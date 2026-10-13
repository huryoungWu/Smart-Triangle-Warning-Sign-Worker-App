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
import androidx.compose.material3.TextButton
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
import com.example.myapplication.core.model.vehicle.DistanceRange
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
    onOpenAbout: () -> Unit,
    onBackToLogin: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { ServiceLocator.get(context).settingsRepository }

    val savedDistance by settings.defaultDistanceM.collectAsStateWithLifecycle(initialValue = DistanceRange.DEFAULT_M)
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


        // ── 更多（APP 一切入口）──
        SectionCard(title = "更多") {
            MenuRow("关于我们", onClick = onOpenAbout)
            MenuRow("帮助与反馈", onClick = {
                vm.showInfo(
                    "帮助与反馈",
                    "常见问题：\n\n" +
                        "· 连不上设备：确认手机已连上小车热点，且设备已上电\n" +
                        "· 任务发不出：检查是否已连接、是否勾选现场安全确认\n" +
                        "· 遥控不响应：确认当前非任务执行中（自动任务禁止抢占）\n\n" +
                        "如需反馈问题，请联系项目组。",
                )
            })
            MenuRow("检查更新", onClick = {
                vm.showInfo("检查更新", "当前已是最新版本 V1.0（演示）。")
            })
            MenuRow("切换账号", onClick = onBackToLogin)
            MenuRow("退出登录", onClick = onBackToLogin, danger = true)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MenuRow(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) com.example.myapplication.ui.theme.WarningRed
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClick) {
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = if (danger) com.example.myapplication.ui.theme.WarningRed
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
