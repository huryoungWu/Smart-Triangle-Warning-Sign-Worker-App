package com.example.myapplication.feature.deployment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.deployment.DeploymentDeviceType
import com.example.myapplication.core.model.deployment.DeploymentDraft
import com.example.myapplication.core.model.deployment.DeploymentLimits
import com.example.myapplication.core.model.deployment.DeploymentTurnDir
import com.example.myapplication.core.model.deployment.TargetPreview
import com.example.myapplication.core.model.vehicle.LaneRange
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.map.PhoneMapPanel
import com.example.myapplication.feature.map.MapViewModel
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.NeutralGreyBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/**
 * 锥桶流程第二页：布设任务发布。
 *
 * 设备连接/绑定已在 [ConeConnectScreen] 完成；本页只负责事件信息、L/d 参数、预检与开始。
 */
@Composable
fun DeploymentPublishScreen(
    vm: DeploymentViewModel,
    mapVm: MapViewModel,
    onBack: () -> Unit,
    onEditDevices: () -> Unit,
    onStarted: (String) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val draft = state.draft

    LaunchedEffect(Unit) {
        vm.reloadDevices()
        vm.loadActiveEvent(force = true)
    }

    // 手指按在地图上时暂停整页滚动，避免滑地图变成滑页面
    var mapTouchActive by remember { mutableStateOf(false) }
    var showStartConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = !mapTouchActive)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            text = "布设任务发布",
            subtitle = "参数与预检",
            onBack = onBack,
        )

        // 发布前确认现场：当前高德地图位置（与三角架发布页一致）
        PhoneMapPanel(
            mapVm = mapVm,
            height = 220.dp,
            title = "现场位置",
            showPhoneMarker = true,
            caption = "蓝星=我的位置",
            onTouchActiveChange = { mapTouchActive = it },
        )

        SectionCard(title = "事件信息") {
            OutlinedTextField(
                value = draft.name,
                onValueChange = vm::updateName,
                label = { Text("事件名（留空自动生成）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.lane?.toString() ?: "",
                onValueChange = { text ->
                    vm.updateLane(text.filter { it.isDigit() }.take(2).toIntOrNull())
                },
                label = { Text("作业车道（第几车道）") },
                placeholder = { Text("${LaneRange.MIN}~${LaneRange.MAX}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.note,
                onValueChange = vm::updateNote,
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = "本次布设设备") {
            Text(
                "主桶：${draft.master?.deviceCode ?: "未选择"}",
                fontWeight = FontWeight.Bold,
            )
            Text(
                "从桶：${
                    draft.slaves.sortedBy { it.order }
                        .joinToString(" → ") { "${it.device.deviceCode}(从${it.order})" }
                        .ifBlank { "无（仅主桶执行）" }
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryActionButton("修改设备", onClick = onEditDevices)
        }

        SectionCard(title = "布设参数") {
            OutlinedTextField(
                value = draft.lText,
                onValueChange = vm::updateL,
                label = { Text("主桶目标行程 L（米）") },
                singleLine = true,
                isError = draft.lText.isNotBlank() && (draft.L == null || draft.step2Errors().isNotEmpty()),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (draft.n > 0) {
                OutlinedTextField(
                    value = draft.dText,
                    onValueChange = vm::updateD,
                    label = { Text("相邻设备启动间隔 d（米）") },
                    singleLine = true,
                    isError = draft.dText.isNotBlank() && (draft.d == null || draft.step2Errors().isNotEmpty()),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "按沿路线累计行程计算，不是地图直线距离。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "仅主桶执行到 L。",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }

            // 路线转弯（可选）：可添加多处 90° 转弯，转弯点按沿路线累计行程填写
            Spacer(Modifier.height(12.dp))
            Text("路线转弯（可选，每处固定 90°）", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            if (draft.turns.isEmpty()) {
                Text(
                    "当前为直行。需要转弯时点击下方「添加转弯」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            draft.turns.forEachIndexed { index, row ->
                key(row.id) {
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    val rowError = draft.turnErrors()
                        .firstOrNull { it.startsWith("第 ${index + 1} 个") }
                    val at = row.atM
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = row.dir == DeploymentTurnDir.LEFT,
                                onClick = { vm.updateTurnDir(index, DeploymentTurnDir.LEFT) },
                                label = { Text("左转") },
                            )
                            FilterChip(
                                selected = row.dir == DeploymentTurnDir.RIGHT,
                                onClick = { vm.updateTurnDir(index, DeploymentTurnDir.RIGHT) },
                                label = { Text("右转") },
                            )
                        }
                        TextButton(onClick = { vm.removeTurn(index) }) {
                            Text("删除", color = WarningRed)
                        }
                    }
                    val prevAt = draft.turns.take(index).mapNotNull { it.atM }.lastOrNull() ?: 0.0
                    OutlinedTextField(
                        value = row.atText,
                        onValueChange = { vm.updateTurnAt(index, it) },
                        label = { Text("第 ${index + 1} 处转弯点（米，从起点累计）") },
                        singleLine = true,
                        isError = rowError != null,
                        supportingText = {
                            when {
                                rowError != null -> Text(rowError, color = WarningRed)
                                at != null -> Text(
                                    "本段直行 ${DeploymentDraft.fmt(at - prevAt)} m",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryActionButton(
                text = "添加转弯（${draft.turns.size}/${DeploymentLimits.MAX_TURNS}）",
                onClick = vm::addTurn,
                enabled = draft.turns.size < DeploymentLimits.MAX_TURNS,
            )
            draft.turnErrors()
                .filterNot { it.startsWith("第 ") || it.startsWith("请先填写主桶目标行程") }
                .forEach { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(err, color = WarningRed, style = MaterialTheme.typography.labelSmall)
                }
            Spacer(Modifier.height(10.dp))
            Text(
                if (draft.n == 0) "从桶数量 N = 0（仅主桶执行）" else "从桶数量 N = ${draft.n}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionCard(title = "布设预览（沿路线累计行程）") {
            val l = draft.L
            val turns = draft.parsedTurns
            val turnValid = turns.size == draft.turns.size && draft.turnErrors().isEmpty()
            val turnSummary = if (l != null && l > 0.0 && turnValid) {
                val parts = buildList {
                    var prev = 0.0
                    turns.forEach { t ->
                        add("直行 ${DeploymentDraft.fmt(t.atM - prev)} m")
                        add("${t.dir.label} ${DeploymentDraft.fmt(t.deg)}°")
                        prev = t.atM
                    }
                    add("直行 ${DeploymentDraft.fmt(l - prev)} m")
                }
                "路线：${parts.joinToString(" → ")}"
            } else {
                null
            }
            if (turnSummary != null) {
                Text(
                    text = turnSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (turns.isEmpty()) NeutralGrey else WarnOrange,
                )
                turns.firstOrNull()?.let { firstTurn ->
                    val beforeTurn = draft.preview.count { it.targetM <= firstTurn.atM + 1e-9 }
                    if (beforeTurn > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "其中 $beforeTurn 只桶的目标在第 1 个转弯点之前，不会过弯。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (draft.preview.isEmpty()) {
                Text("填写参数后显示目标行程。", color = NeutralGrey)
            } else {
                draft.preview.forEach { p -> TargetRow(p) }
                if (draft.n > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "主桶到 d 启动从 1；从 k 到 d 上报后启动从 k+1。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "d 是启动间隔，不是最终位置。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnOrange,
                    )
                }
            }
        }

        val errors = draft.validate()
        SectionCard(title = "自动条件") {
            ConditionRow("参数合法", errors.isEmpty(), errors.joinToString("；"))
            ConditionRow(
                "无其他进行中事件",
                state.activeEvent == null,
                state.activeEvent?.let { "已有 ${it.deploymentId}" } ?: "",
            )
            ConditionRow("主桶已选择", draft.master != null, "")
            ConditionRow(
                "从桶数量 0~${DeploymentLimits.N_MAX}",
                draft.n in 0..DeploymentLimits.N_MAX,
                if (draft.n == 0) "当前 0（仅主桶）" else "当前 ${draft.n}",
            )
        }

        SectionCard(title = "人工现场确认") {
            CheckRow(
                if (draft.n == 0) {
                    "已确认主桶位于起始线、朝向正确、路线无障碍。"
                } else {
                    "已确认锥桶位于起始线、朝向正确、路线无障碍。"
                },
                draft.precheckSafety,
                vm::toggleSafety,
            )
            CheckRow(
                if (draft.n == 0) "已核对主桶编号与 APP 一致。" else "已核对锥桶编号与 APP 顺序一致。",
                draft.precheckOrder,
                vm::toggleOrder,
            )
            if (!draft.precheckDone) {
                Text(
                    "请完成以上两项现场确认后才能开始。",
                    color = WarningRed,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        SafetyBanner()

        if (errors.isNotEmpty()) {
            SectionCard(title = "开始前请修正") {
                errors.forEach { Text("• $it", color = WarningRed, style = MaterialTheme.typography.bodySmall) }
            }
        }

        PrimaryActionButton(
            text = if (state.busy) "正在启动…" else "发布并开始协同布设",
            onClick = { showStartConfirm = true },
            enabled = draft.canStart() && state.activeEvent == null && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
    }

    if (showStartConfirm) {
        AlertDialog(
            onDismissRequest = { showStartConfirm = false },
            title = { Text("开始协同布设？") },
            text = {
                val turns = draft.parsedTurns
                val turnHint = if (turns.isEmpty()) {
                    ""
                } else {
                    val detail = turns.mapIndexed { i, t ->
                        "第 ${i + 1} 处在 ${DeploymentDraft.fmt(t.atM)} m ${t.dir.label} ${DeploymentDraft.fmt(t.deg)}°"
                    }.joinToString("；")
                    "\n\n本次路线含 ${turns.size} 处转弯：$detail。" +
                        "请确认各转弯点及转弯后的路线无障碍。"
                }
                Text(
                    "开始后主桶将首先移动；从桶会按“前一只自身行程达到 d”逐级启动。\n\n" +
                        "请确认：所有锥桶已按编队顺序置于同一起始线，现场安全条件已满足。" +
                        turnHint,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStartConfirm = false
                        vm.start(onStarted)
                    },
                ) { Text("确认开始") }
            },
            dismissButton = { TextButton(onClick = { showStartConfirm = false }) { Text("取消") } },
        )
    }

    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("无法继续") },
            text = { Text(msg) },
            confirmButton = { Button(onClick = vm::dismissError) { Text("知道了") } },
        )
    }
    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { Button(onClick = vm::dismissMessage) { Text("知道了") } },
        )
    }
}

@Composable
private fun TargetRow(p: TargetPreview) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(
            p.orderLabel,
            if (p.deviceType == DeploymentDeviceType.MASTER_CONE) InfoBlue else NeutralGrey,
            if (p.deviceType == DeploymentDeviceType.MASTER_CONE) InfoBlueBg else NeutralGreyBg,
        )
        Spacer(Modifier.width(8.dp))
        Text(p.deviceCode, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(
            if (p.targetM <= 0.001) "0.0 m · 无需移动" else "${DeploymentDraft.fmt(p.targetM)} m",
            fontWeight = FontWeight.Bold,
            color = if (p.targetM <= 0.001) NeutralGrey else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConditionRow(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = NeutralGrey, modifier = Modifier.padding(end = 6.dp))
        }
        if (ok) StatusChip("满足", SafeGreen, SafeGreenBg) else StatusChip("不满足", WarningRed, WarningRedBg)
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun SafetyBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(WarnOrangeBg)
            .padding(10.dp),
    ) {
        Text(
            "系统不会自动跳过或重发指令；请以现场安全验收为准。",
            color = WarnOrange,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
