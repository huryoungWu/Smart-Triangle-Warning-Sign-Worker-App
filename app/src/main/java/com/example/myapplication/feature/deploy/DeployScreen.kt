package com.example.myapplication.feature.deploy

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.vehicle.CommandPhase
import com.example.myapplication.core.model.vehicle.DistancePreset
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.ui.CommandTimeline
import com.example.myapplication.core.ui.DangerActionButton
import com.example.myapplication.core.ui.DeviceStatusStrip
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/**
 * 定距布置（M05）：仅 0.5／1／2 米三档；启动与进度按命令五层展示；
 * **重复点击不得启动第二项运动任务**；设备报告完成后人工现场核验（与设备完成分开记录）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeployScreen(
    vm: VehicleViewModel,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val task = snapshot?.task
    val ticket = state.activeTicket

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("定距布置") },
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

            // 目标距离（M05：仅三档，无输入框/滑块）
            SectionCard(title = "目标距离（首版仅提供已验证档位）") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DistancePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = state.deployPreset == preset,
                            onClick = { vm.setDeployPreset(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SafetyText("仅提供已验证的 0.5／1／2 米档位；不开放任意距离，不自动寻找合适位置。")
            }

            // 命令五层（规格第 4 节）
            SectionCard(title = "任务进度") {
                CommandTimeline(
                    current = ticket?.phase,
                    note = ticket?.note,
                )

                Spacer(Modifier.height(12.dp))
                if (task != null && task.state != TaskRunState.IDLE) {
                    Row {
                        Text("目标 ${task.targetM} 米", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "已行驶 ${"%.2f".format(task.traveledM)} 米",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "行驶距离由车轮编码器估算，非地图定位",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralGrey,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "结果：${task.state.label}",
                        color = if (task.state == TaskRunState.DONE) SafeGreen else NeutralGrey,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (task.state == TaskRunState.DONE) {
                        Spacer(Modifier.height(8.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = SafeGreenBg)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "设备报告已完成",
                                    color = SafeGreen,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "请人工现场核对：实际位置、车辆朝向、警示灯光是否正常。\n" +
                                        "现场核验结果将与设备完成分开记录。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SafeGreen,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "尚未启动任务。启动后此处显示「已发出 → 设备已接收 → 正在执行 → 设备报告已完成 → 人工现场核验」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralGrey,
                    )
                }
            }

            // 操作区
            val canStart = state.taskStartEnabled
            if (ticket == null || ticket.phase == CommandPhase.FAILED || ticket.phase == CommandPhase.UNKNOWN) {
                PrimaryActionButton(
                    text = "启动定距直行（${state.deployPreset.label}）",
                    onClick = vm::startFixedDistanceTask,
                    enabled = canStart,
                )
                if (!canStart) {
                    Text(
                        "禁止重复启动：已有命令或任务进行中（重复点击不会启动第二项运动任务）",
                        color = WarningRed,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (task?.state == TaskRunState.RUNNING || state.holdingMove) {
                DangerActionButton(text = "停止并取消任务", onClick = vm::cancelTask)
            }

            if (state.pendingVerify != null) {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = WarningRedBg)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("待人工现场核验", color = WarningRed, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryActionButton(
                                text = "核验通过",
                                onClick = { vm.confirmVerify(true, "位置/朝向/灯光正常") },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            DangerActionButton(
                                text = "发现异常",
                                onClick = { vm.confirmVerify(false, "现场异常，需处置") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
