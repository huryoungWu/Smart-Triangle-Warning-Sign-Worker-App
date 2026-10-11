package com.example.myapplication.feature.onetouch

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
import com.example.myapplication.core.model.vehicle.OneTouchStage
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.model.vehicle.TaskSource
import com.example.myapplication.core.ui.CommandTimeline
import com.example.myapplication.core.ui.DangerActionButton
import com.example.myapplication.core.ui.DeviceStatusStrip
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

private val stageLabels = listOf(
    OneTouchStage.LIGHTING,
    OneTouchStage.COUNTDOWN,
    OneTouchStage.MOVING,
    OneTouchStage.PARKED,
    OneTouchStage.WAITING_VERIFY,
)

/**
 * 一键布置（M06）：预检确认 → 灯光双闪 → 提示/倒计时 → 预设距离低速直行 → 自动停车且继续闪灯 → 人工核验。
 * 任一阶段可取消或急停；失败不得继续后续步骤。阶段状态以设备回报为准，不以 APP 计时为准。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneTouchScreen(
    vm: VehicleViewModel,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val task = snapshot?.task
    val ticket = state.activeTicket
    val stage = vm.oneTouchStage()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("一键布置") },
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

            // 任务来源角标（M06 实体按钮联动）
            if (task?.source == TaskSource.BUTTON) {
                Card(colors = CardDefaults.cardColors(containerColor = InfoBlueBg)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(10.dp),
                    ) {
                        StatusChip("来源：车身按钮", InfoBlue, InfoBlueBg)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "APP 仅读取任务进度与结果，可取消或急停",
                            style = MaterialTheme.typography.labelSmall,
                            color = InfoBlue,
                        )
                    }
                }
            }

            // 阶段进度（M06：状态来自设备回报）
            SectionCard(title = "一键布置阶段") {
                stageLabels.forEach { s ->
                    val isCurrent = stage == s
                    val isDone = stage != null && s.step < stage.step
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = if (isDone) "✔" else if (isCurrent) "▶" else "○",
                            color = when {
                                isDone -> SafeGreen
                                isCurrent -> WarnOrange
                                else -> NeutralGrey.copy(alpha = 0.5f)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = s.label,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isDone -> SafeGreen
                                isCurrent -> WarnOrange
                                else -> NeutralGrey
                            },
                        )
                    }
                }

                if (stage == OneTouchStage.COUNTDOWN) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "倒计时中，请确认周边人员已撤离",
                        color = WarnOrange,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Spacer(Modifier.height(8.dp))
                SafetyText("阶段状态以设备回报为准；设备已停而灯仍闪烁属正常状态（灯光与运动分离）。")
            }

            // 命令五层
            SectionCard(title = "命令状态") {
                CommandTimeline(current = ticket?.phase, note = ticket?.note)
                if (task != null && task.state != TaskRunState.IDLE) {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text("目标 ${task.targetM} 米")
                        Spacer(Modifier.weight(1f))
                        Text("已行驶 ${"%.2f".format(task.traveledM)} 米", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
            }

            // 失败熔断提示
            if (task?.state == TaskRunState.FAILED) {
                Card(colors = CardDefaults.cardColors(containerColor = WarningRedBg)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("任务失败，流程已中止", color = WarningRed, fontWeight = FontWeight.Bold)
                        Text(
                            "失败原因：${task.resultNote ?: "设备报告任务未完成"}\n不会继续后续步骤，请现场排查。",
                            color = WarningRed,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // 完成与核验（M06）
            if (task?.state == TaskRunState.DONE) {
                Card(colors = CardDefaults.cardColors(containerColor = SafeGreenBg)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("设备报告已完成", color = SafeGreen, fontWeight = FontWeight.Bold)
                        Text(
                            "车辆已自动停车并保持警示灯闪烁。\n请人工核对实际位置、朝向与灯光。",
                            color = SafeGreen,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (state.pendingVerify != null) {
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

            // 操作
            if (task?.state == TaskRunState.RUNNING) {
                SecondaryActionButton(text = "取消任务（任一阶段可取消）", onClick = vm::cancelTask)
            } else if (ticket == null || ticket.phase == CommandPhase.FAILED || ticket.phase == CommandPhase.UNKNOWN) {
                PrimaryActionButton(
                    text = "启动一键布置",
                    onClick = vm::startOneTouchTask,
                    enabled = state.taskStartEnabled,
                )
                if (!state.taskStartEnabled) {
                    Text(
                        "当前状态不允许启动（急停锁定／任务运行中／命令未确认）",
                        color = WarningRed,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
