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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
            subtitle = "事件参数与现场预检",
            onBack = onBack,
        )

        // 发布前确认现场：当前高德地图位置（与三角架发布页一致）
        PhoneMapPanel(
            mapVm = mapVm,
            height = 220.dp,
            title = "现场位置",
            showPhoneMarker = true,
            caption = "蓝星为当前作业人员位置（高德定位）。",
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
                    "L、d 都是沿预定路线的累计行程（米），不是地图直线距离，也不是时间。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "未选择从桶：无需填写启动间隔 d，仅主桶执行到 L。",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (draft.n == 0) "从桶数量 N = 0（仅主桶执行）" else "从桶数量 N = ${draft.n}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionCard(title = "布设预览（沿路线累计行程）") {
            if (draft.preview.isEmpty()) {
                Text(
                    if (draft.n == 0) {
                        "填写 L 后显示主桶目标行程。"
                    } else {
                        "填写 L、d 后显示主桶与各从桶的目标行程。"
                    },
                    color = NeutralGrey,
                )
            } else {
                draft.preview.forEach { p -> TargetRow(p) }
                Spacer(Modifier.height(8.dp))
                if (draft.n == 0) {
                    Text(
                        "仅主桶执行到 L，没有从桶启动链路。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "启动链路：主桶自身到 d → 启动从 1；从 1 自身到 d → 上报主桶 → 启动从 2；依此类推。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "注意：从 k 移动到 d 只是达到启动间隔，不是到达最终位置；后续桶不会停在 d 等待。",
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
                    "我已现场确认：主桶置于起始线，朝向布设方向，路线无障碍物和人员。"
                } else {
                    "我已现场确认：所有锥桶按编队顺序置于同一起始线，朝向布设方向，路线无障碍物和人员。"
                },
                draft.precheckSafety,
                vm::toggleSafety,
            )
            CheckRow(
                if (draft.n == 0) "我已核对主桶编号与 APP 一致。" else "我已核对主桶/从桶编号与 APP 顺序一致。",
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
                Text(
                    "开始后主桶将首先移动；从桶会按“前一只自身行程达到 d”逐级启动。\n\n" +
                        "请确认：所有锥桶已按编队顺序置于同一起始线，现场安全条件已满足。",
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
            "系统按设备累计行程触发逐级启动。行程反馈不代表精确间距，也不替代现场安全验收。" +
                "异常时系统不会自动跳过，也不会自动重发运动/启动指令。",
            color = WarnOrange,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
