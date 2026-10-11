package com.example.myapplication.feature.manual

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.VehicleMode
import com.example.myapplication.core.ui.DangerActionButton
import com.example.myapplication.core.ui.DeviceStatusStrip
import com.example.myapplication.core.ui.HoldDirectionPad
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatRow
import com.example.myapplication.core.ui.StatTextTileRow
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.WarningRed

/**
 * 手动遥控（M04）：按住才移动；松手/触控取消/退出页面/失联后停止。
 * 自动任务执行时禁止手动方向指令抢占。
 *
 * 三重停车保障：① DisposableEffect（返回/导航离开）② ON_STOP（切后台）③ 失联时 ViewModel 清"按住中"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScreen(
    vm: VehicleViewModel,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val lifecycleOwner = LocalLifecycleOwner.current

    // 保障 ①：离开本页（返回键/导航离开/重组销毁）→ 强制停车
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.onLeaveManual()   // 保障 ②：切后台
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.onLeaveManual()                                          // 保障 ①
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手动遥控") },
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
            DeviceStatusStrip(state)

            // 运行数据（首页只突出目标/进度，调试页看全量；此处给少量实时值）
            SectionCard(title = "实时数据") {
                StatRow {
                    StatTextTileRow(
                        label = "已行驶",
                        value = "${"%.2f".format(snapshot?.odo?.traveledM ?: 0.0)} m",
                        valueColor = InfoBlue,
                    )
                    StatTextTileRow(
                        label = "速度",
                        value = "${"%.2f".format(snapshot?.odo?.speedMps ?: 0.0)} m/s",
                        valueColor = NeutralGrey,
                    )
                }
                Text(
                    text = "数据由车轮编码器估算，非地图定位；过期时标注「非实时」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }

            // 方向盘（M04）
            SectionCard(title = "方向控制") {
                val autoRunning = snapshot?.mode == VehicleMode.AUTO
                val locked = snapshot?.estopLocked == true || snapshot?.mode == VehicleMode.SAFE_STOP
                val enabled = state.motionCommandsEnabled && !autoRunning

                HoldDirectionPad(
                    enabled = enabled,
                    onHoldStart = vm::onHoldStart,
                    onHoldEnd = vm::onHoldEnd,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.connection != ConnectionPhase.CONNECTED) {
                    Warning("设备未连接或状态未确认，运动操作已禁用")
                } else if (locked) {
                    Warning("急停锁定中，请现场核查后恢复再操作")
                } else if (autoRunning) {
                    Warning("自动任务执行中，禁止手动抢占")
                }

                Spacer(Modifier.height(12.dp))
                DangerActionButton(
                    text = "停止（普通停车）",
                    onClick = vm::stopNormal,
                    enabled = state.connection == ConnectionPhase.CONNECTED,
                )
                Text(
                    text = "普通停车会取消自动任务并回到待命；不锁定后续操作，不自动关闭警示灯。",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            SafetyText("急停入口在页面底部常驻；车身实体急停不由 APP 软件按钮替代。")

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Warning(text: String) {
    Text(
        text = "⚠ $text",
        color = WarningRed,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 8.dp),
    )
}
