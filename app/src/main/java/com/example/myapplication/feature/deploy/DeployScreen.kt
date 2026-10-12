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
import androidx.compose.material3.Slider
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
import com.example.myapplication.core.model.vehicle.DistanceRange
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.ui.CommandTimeline
import com.example.myapplication.core.ui.DangerActionButton
import com.example.myapplication.core.ui.DeviceStatusStrip
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.myapplication.core.ui.map.MapPanel
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/**
 * 定距布置（M05）：距离 10~100 米自由输入；启动与进度按命令五层展示；
 * **重复点击不得启动第二项运动任务**；设备报告完成后人工现场核验；
 * 完成后可一键自动回收，随即布置下一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeployScreen(
    vm: VehicleViewModel,
    mapVm: com.example.myapplication.feature.map.MapViewModel,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val task = snapshot?.task
    val ticket = state.activeTicket
    var distanceText by remember { mutableStateOf(state.targetDistanceM.toString()) }

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

            // 小车实时位置（作业操作时同步展示）
            MapPanel(
                vm = vm,
                mapVm = mapVm,
                height = 220.dp,
                title = "小车实时位置",
                showLegend = false,
            )

            // 目标距离（10-100 米，滑块 + 自由输入）
            SectionCard(title = "目标距离（${DistanceRange.MIN_M}~${DistanceRange.MAX_M} 米）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${state.targetDistanceM} 米",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = distanceText,
                        onValueChange = { txt ->
                            distanceText = txt
                            txt.toIntOrNull()?.let { vm.setTargetDistance(it) }
                        },
                        label = { Text("自定义米数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Slider(
                    value = state.targetDistanceM.toFloat().coerceIn(10f, 100f),
                    onValueChange = { v ->
                        val m = v.toInt().coerceIn(DistanceRange.MIN_M, DistanceRange.MAX_M)
                        vm.setTargetDistance(m)
                        distanceText = m.toString()
                    },
                    valueRange = DistanceRange.MIN_M.toFloat()..DistanceRange.MAX_M.toFloat(),
                    steps = 89,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SafetyText("可自由输入 ${DistanceRange.MIN_M}~${DistanceRange.MAX_M} 米；车端仍会校验参数范围与启动条件。")
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

            // 操作区：任务运行中可停；完成后可回收或直接布置下一次
            val isRunning = task?.state == TaskRunState.RUNNING
            val canStart = state.taskStartEnabled && !isRunning

            if (isRunning) {
                DangerActionButton(text = "停止并取消任务", onClick = vm::cancelTask)
            } else {
                // 自动回收：小车返回作业人员位置，之后即可布置下一次
                if ((task?.traveledM ?: 0.0) > 0.01) {
                    PrimaryActionButton(text = "自动回收（小车回到我这里）", onClick = vm::startRecoverTask)
                    Spacer(Modifier.height(8.dp))
                }
                PrimaryActionButton(
                    text = "启动定距直行（${state.targetDistanceM} 米）",
                    onClick = vm::startFixedDistanceTask,
                    enabled = canStart,
                )
                if (!canStart) {
                    Text(
                        "当前状态不允许启动（急停锁定／命令未确认）",
                        color = WarningRed,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
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
