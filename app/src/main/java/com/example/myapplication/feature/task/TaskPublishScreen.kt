package com.example.myapplication.feature.task

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.vehicle.LaneRange
import com.example.myapplication.core.model.vehicle.RouteSegment
import com.example.myapplication.core.model.vehicle.SegmentAction
import com.example.myapplication.core.model.vehicle.SegmentRange
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg
import kotlin.math.abs

/**
 * 任务发布表单（手动遥控 / 定距布置 共用）。
 *
 * 字段：任务名 → **路线设计** → 备注 → 预检区 → 发布。
 * **发布即代表任务开始**，点击后直接跳转执行页（执行页无启动按钮）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPublishScreen(
    vm: VehicleViewModel,
    mapVm: com.example.myapplication.feature.map.MapViewModel,
    kind: TaskKind,
    onBack: () -> Unit,
    onPublished: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val draft = state.draft
    val errors = draft.validate()
    val deviceOk = state.deviceConditions().all { it.second }
    // 作业车道未填写或越界（必填项，随任务下发/上传）。
    // 初始空白不报红：用户输入过、或输入了越界值后才显示错误。
    var laneTouched by remember { mutableStateOf(false) }
    val laneMissing = draft.lane == null
    val laneInvalid = laneMissing || draft.lane !in LaneRange.MIN..LaneRange.MAX
    val showLaneError = laneTouched && laneInvalid
    // 未触碰车道前，先不把“请填写作业车道”展示在顶部错误卡里
    val visibleErrors = if (laneTouched) errors else errors.filterNot { it.contains("作业车道") }

    // 进入发布页先读一次状态，保证「现场位置」不是旧快照
    LaunchedEffect(Unit) { vm.refreshStatus() }
    LaunchedEffect(kind) {
        vm.initDraft(kind)
        laneTouched = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("三角架任务发布", onBack = onBack)

        // ① 小车与作业人员位置（发布前确认现场）
        com.example.myapplication.core.ui.map.MapPanel(
            vm = vm,
            mapVm = mapVm,
            height = 220.dp,
            title = "现场位置",
            showLegend = false,
            showPhoneMarker = true,   // 蓝星 = 我的实时位置 A
        )

        // ② 任务名 + 作业车道
        SectionCard(title = "任务信息") {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { vm.updateDraft { s -> s.copy(name = it) } },
                label = { Text("任务名（留空自动生成）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // 作业车道（第几车道，1~8）：**必填**，随任务下发/上传，风险事件同步标注
            OutlinedTextField(
                value = draft.lane?.toString() ?: "",
                onValueChange = { text ->
                    laneTouched = true
                    val digits = text.filter { it.isDigit() }.take(2)
                    vm.updateDraft { s -> s.copy(lane = digits.toIntOrNull()) }
                },
                label = { Text("作业车道（第几车道）") },
                placeholder = { Text("${LaneRange.MIN}~${LaneRange.MAX}") },
                singleLine = true,
                isError = showLaneError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "填写 ${LaneRange.MIN}~${LaneRange.MAX} 的整数（如 2 = 第 2 车道）；发布任务必填，随任务一并上报。",
                style = MaterialTheme.typography.labelSmall,
                color = if (showLaneError) WarningRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.note,
                onValueChange = { vm.updateDraft { s -> s.copy(note = it) } },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ③ 路线设计（核心）
        SectionCard(title = "路线设计（${draft.segments.size} 段 · 行驶合计 ${"%.1f".format(draft.totalM)} 米）") {
            SafetyText("路线各段将按顺序执行；每段动作与参数如下。")

            draft.segments.forEachIndexed { index, seg ->
                SegmentCard(
                    index = index,
                    segment = seg,
                    isFirst = index == 0,
                    isLast = index == draft.segments.lastIndex,
                    onActionChange = { action ->
                        vm.updateDraft { s ->
                            val defaults = when (action) {
                                SegmentAction.FWD -> 20.0
                                SegmentAction.TURN -> 90.0
                            }
                            s.copy(
                                segments = s.segments.mapIndexed { i, x ->
                                    if (i == index) x.copy(action = action, value = defaults) else x
                                },
                            )
                        }
                    },
                    onValueChange = { v ->
                        vm.updateDraft { s ->
                            s.copy(segments = s.segments.mapIndexed { i, x ->
                                if (i == index) x.copy(value = v) else x
                            })
                        }
                    },
                    onMoveUp = {
                        vm.updateDraft { s ->
                            val list = s.segments.toMutableList()
                            if (index > 0) {
                                val t = list[index - 1]
                                list[index - 1] = list[index]
                                list[index] = t
                            }
                            s.copy(segments = list)
                        }
                    },
                    onMoveDown = {
                        vm.updateDraft { s ->
                            val list = s.segments.toMutableList()
                            if (index < list.size - 1) {
                                val t = list[index + 1]
                                list[index + 1] = list[index]
                                list[index] = t
                            }
                            s.copy(segments = list)
                        }
                    },
                    onDelete = {
                        vm.updateDraft { s ->
                            s.copy(segments = s.segments.filterIndexed { i, _ -> i != index })
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            SecondaryActionButton(
                text = "添加路段",
                onClick = {
                    vm.updateDraft { s ->
                        s.copy(segments = s.segments + RouteSegment(action = SegmentAction.FWD, value = 20.0))
                    }
                },
                enabled = draft.segments.size < SegmentRange.MAX_SEGMENTS,
            )
            if (draft.segments.size >= SegmentRange.MAX_SEGMENTS) {
                Text(
                    "最多 ${SegmentRange.MAX_SEGMENTS} 段",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }

        }

        // ⑤ 预检区（原独立预检页并入）
        SectionCard(title = "布置前预检") {
            Spacer(Modifier.height(8.dp))
            PrecheckRow(
                com.example.myapplication.core.model.vehicle.PrecheckHuman.CONFIRM_ITEM,
                draft.precheck.confirmed,
            ) {
                vm.togglePrecheck { it.copy(confirmed = !it.confirmed) }
            }
            if (!draft.precheck.allChecked) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "请勾选现场安全确认后才能发布",
                    color = WarningRed,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("设备条件（自动判定）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            state.deviceConditions().forEach { (label, ok) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (ok) StatusChip("满足", SafeGreen, SafeGreenBg)
                    else StatusChip("不满足", WarningRed, WarningRedBg)
                }
            }
            if (!deviceOk) {
                Text(
                    "不满足原因：${state.deviceConditionFailureReasons().joinToString("；")}",
                    color = WarningRed,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

        }

        // ⑥ 发布（表单校验错误统一在发布按钮上方列出：任务信息 / 路线设计）
        val canPublish = errors.isEmpty() && draft.precheck.allChecked && deviceOk && state.taskStartEnabled
        val formError = visibleErrors.isNotEmpty() || (laneTouched && laneInvalid)
        if (visibleErrors.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = WarningRedBg)) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "请先修正以下错误项",
                        color = WarningRed,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    visibleErrors.forEach {
                        Text("• $it", color = WarningRed, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        PrimaryActionButton(
            text = "发布任务（立即执行）",
            onClick = {
                vm.publishDraft()
                onPublished()
            },
            enabled = canPublish,
        )
        if (!canPublish) {
            Text(
                text = when {
                    visibleErrors.isNotEmpty() -> "请先修正表单中的错误项"
                    laneMissing && !laneTouched -> "请填写作业车道（第几车道）"
                    !draft.precheck.allChecked -> "请完成预检勾选"
                    !deviceOk -> "设备条件未满足，无法发布"
                    else -> "当前状态不允许发布（急停锁定／任务运行中／命令未确认）"
                },
                color = if (formError) WarningRed else WarnOrange,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PrecheckRow(label: String, checked: Boolean, onChange: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChange() })
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 单个路段的行内编辑卡 */
@Composable
private fun SegmentCard(
    index: Int,
    segment: RouteSegment,
    isFirst: Boolean,
    isLast: Boolean,
    onActionChange: (SegmentAction) -> Unit,
    onValueChange: (Double) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 ${index + 1} 段",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMoveUp, enabled = !isFirst) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(onClick = onMoveDown, enabled = !isLast) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = WarningRed)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SegmentAction.entries.forEach { a ->
                    FilterChip(
                        selected = segment.action == a,
                        onClick = { onActionChange(a) },
                        label = { Text(a.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            if (segment.action == SegmentAction.TURN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = segment.value < 0,
                        onClick = { onValueChange(-abs(segment.value)) },
                        label = { Text("左转") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = segment.value >= 0,
                        onClick = { onValueChange(abs(segment.value)) },
                        label = { Text("右转") },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = formatValue(segment.value),
                onValueChange = { txt ->
                    txt.replace("−", "-").toDoubleOrNull()?.let { onValueChange(it) }
                },
                label = { Text("参数（${segment.action.unit}）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = segment.summary(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatValue(v: Double): String =
    if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.1f", v)
