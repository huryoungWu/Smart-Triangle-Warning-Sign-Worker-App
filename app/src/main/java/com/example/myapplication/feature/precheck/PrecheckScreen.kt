package com.example.myapplication.feature.precheck

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.myapplication.core.model.vehicle.PrecheckHuman
import com.example.myapplication.core.ui.LOCKED_FEATURE_NOTICE
import com.example.myapplication.core.ui.LockedFeatureCard
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/**
 * 布置前预检（M03）。
 *
 * **人工现场确认**不得描述成系统已完成障碍或道路安全检测；
 * **设备条件**至少检查在线、静止、未急停锁定、无其他运行任务，必需项失败禁用启动并显示原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrecheckScreen(
    vm: VehicleViewModel,
    kind: String,
    onBack: () -> Unit,
    onPassed: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val human = state.precheckHuman
    val deviceOk = state.deviceConditions().all { it.second }
    val targetLabel = if (kind == "onetouch") "一键布置" else "定距布置"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("布置前预检 · $targetLabel") },
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
            SafetyText("以下为人工现场确认项，系统不检测障碍物与道路安全，请作业人员实地核对。")

            // 人工现场确认（M03）
            SectionCard(title = "人工现场确认") {
                CheckRow("车头朝向是否正确（面向布置方向）", human.heading) {
                    vm.togglePrecheck { it.copy(heading = !it.heading) }
                }
                CheckRow("前方通道是否无障碍、可安全行驶", human.passage) {
                    vm.togglePrecheck { it.copy(passage = !it.passage) }
                }
                CheckRow("周边是否有人员、是否已清场", human.bystanders) {
                    vm.togglePrecheck { it.copy(bystanders = !it.bystanders) }
                }
                CheckRow("目标距离是否合适（0.5／1／2 米档位）", human.distance) {
                    vm.togglePrecheck { it.copy(distance = !it.distance) }
                }
                if (!human.allChecked) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "未勾选：${human.uncheckedItems().joinToString("、")}",
                        color = WarningRed,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // 设备条件（M03）
            SectionCard(title = "设备条件（自动判定，以设备返回值为准）") {
                state.deviceConditions().forEach { (label, ok) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (ok) {
                            StatusChip("满足", SafeGreen, SafeGreenBg)
                        } else {
                            StatusChip("不满足", WarningRed, WarningRedBg)
                        }
                    }
                }
                if (!deviceOk) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "不满足原因：${state.deviceConditionFailureReasons().joinToString("；")}",
                        color = WarningRed,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // C 阶段置灰（电量/倾倒/障碍待传感器验收）
            SectionCard(title = "扩展检查（C 阶段）") {
                LockedFeatureCard(
                    title = "电量检查",
                    reason = LOCKED_FEATURE_NOTICE,
                    onClick = { vm.showInfo("电量检查", LOCKED_FEATURE_NOTICE) },
                )
                Spacer(Modifier.height(8.dp))
                LockedFeatureCard(
                    title = "倾倒／障碍检测",
                    reason = LOCKED_FEATURE_NOTICE,
                    onClick = { vm.showInfo("倾倒／障碍检测", LOCKED_FEATURE_NOTICE) },
                )
            }

            PrimaryActionButton(
                text = if (human.allChecked && deviceOk) "预检通过，进入$targetLabel" else "请完成预检",
                onClick = {
                    vm.resetPrecheck()
                    onPassed()
                },
                enabled = human.allChecked && deviceOk,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChange() })
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
