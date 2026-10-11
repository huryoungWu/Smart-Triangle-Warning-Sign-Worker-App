package com.example.myapplication.feature.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.ui.DeviceStatusStrip
import com.example.myapplication.core.ui.LOCKED_FEATURE_NOTICE
import com.example.myapplication.core.ui.LightModeChip
import com.example.myapplication.core.ui.LockedFeatureCard
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.ScopeSafetyBanner
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange

/**
 * 作业首页（M01/M02/M08 + C 置灰）：
 * 连接卡、状态总览、作业入口、数据真实性提示、C 阶段置灰卡。
 */
@Composable
fun HomeScreen(
    vm: VehicleViewModel,
    onOpenManual: () -> Unit,
    onOpenPrecheckDeploy: () -> Unit,
    onOpenPrecheckOneTouch: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenDeviceInfo: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val connected = state.connection == ConnectionPhase.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("智能三角警示牌作业", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // 连接卡（M01）
        SectionCard(title = "设备连接") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = snapshot?.let { "${it.deviceId} · 固件 ${it.fw}" } ?: "未读取到设备信息",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "本地 Wi-Fi · 192.168.4.1",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected) {
                    StatusChip("已连接", SafeGreen, SafeGreenBg)
                } else {
                    Button(onClick = vm::connect) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("连接设备")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SecondaryActionButton(text = "设备信息", onClick = onOpenDeviceInfo)
        }

        // 状态总览（M02）
        SectionCard(title = "设备状态") {
            DeviceStatusStrip(state)
            snapshot?.let { s ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("灯光模式", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    LightModeChip(s.light)
                }
                if (s.task.state != TaskRunState.IDLE) {
                    Spacer(Modifier.height(10.dp))
                    Text("自动任务进度", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row {
                        Text("目标 ${s.task.targetM} 米", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text("已行驶 ${"%.2f".format(s.task.traveledM)} 米", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { s.task.progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "结果：${s.task.state.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (s.task.state == TaskRunState.DONE) SafeGreen else NeutralGrey,
                    )
                }
            }
        }

        // 作业入口
        SectionCard(title = "作业操作") {
            PrimaryActionButton(
                text = "手动遥控",
                onClick = onOpenManual,
                enabled = connected,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryActionButton(
                text = "定距布置（0.5／1／2 米）",
                onClick = onOpenPrecheckDeploy,
                enabled = connected && state.taskStartEnabled,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryActionButton(
                text = "一键布置",
                onClick = onOpenPrecheckOneTouch,
                enabled = connected && state.taskStartEnabled,
            )
            if (!connected) {
                Spacer(Modifier.height(6.dp))
                Text("请先连接设备", color = NeutralGrey, style = MaterialTheme.typography.labelSmall)
            } else if (!state.taskStartEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前状态不允许启动布置任务（急停锁定／任务运行中／命令未确认）",
                    color = WarnOrange,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            SecondaryActionButton(text = "调试数据", onClick = onOpenDebug)
        }

        // 数据真实性（规格第 4 节）
        SectionCard(title = "能力说明") {
            SafetyText("当前位置仅由车轮编码器估算行驶距离，不代表地图定位。")
            Spacer(Modifier.height(6.dp))
            SafetyText("样机不具备障碍、来车、道路安全检测能力；不具备自动寻找合适位置能力。")
            Spacer(Modifier.height(6.dp))
            ScopeSafetyBanner()
        }

        // C 阶段置灰（用户拍板：A+B+C UI 全做，C 置灰）
        SectionCard(title = "待验收能力（C 阶段）") {
            LockedFeatureCard(
                title = "地图位置",
                reason = LOCKED_FEATURE_NOTICE,
                onClick = { vm.showInfo("地图位置", LOCKED_FEATURE_NOTICE) },
            )
            Spacer(Modifier.height(8.dp))
            LockedFeatureCard(
                title = "事件编号",
                reason = LOCKED_FEATURE_NOTICE,
                onClick = { vm.showInfo("事件编号", LOCKED_FEATURE_NOTICE) },
            )
            Spacer(Modifier.height(8.dp))
            LockedFeatureCard(
                title = "云端记录",
                reason = LOCKED_FEATURE_NOTICE,
                onClick = { vm.showInfo("云端记录", LOCKED_FEATURE_NOTICE) },
            )
            Spacer(Modifier.height(8.dp))
            LockedFeatureCard(
                title = "多设备管理",
                reason = LOCKED_FEATURE_NOTICE,
                onClick = { vm.showInfo("多设备管理", LOCKED_FEATURE_NOTICE) },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
