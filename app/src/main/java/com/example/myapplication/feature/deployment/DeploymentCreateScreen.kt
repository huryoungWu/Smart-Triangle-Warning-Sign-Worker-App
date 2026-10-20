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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.model.deployment.DeploymentDeviceSummary
import com.example.myapplication.core.model.deployment.DeploymentDraft
import com.example.myapplication.core.model.deployment.DeploymentLimits
import com.example.myapplication.core.model.deployment.DeploymentRole
import com.example.myapplication.core.model.deployment.SelectedSlave
import com.example.myapplication.core.model.deployment.TargetPreview
import com.example.myapplication.core.model.vehicle.LaneRange
import com.example.myapplication.core.ui.ConfirmDialog
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.permission.rememberCameraPermissionState
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
 * 锥桶协同布设向导：①选择设备 ②参数与预览 ③预检并开始。
 *
 * 入口不要求先连接单台设备；设备绑定只进入候选列表，勾选后才加入本次事件。
 */
@Composable
fun DeploymentCreateScreen(
    vm: DeploymentViewModel,
    onBack: () -> Unit,
    onStarted: (String) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val draft = state.draft

    // 进入页面时刷新设备与进行中事件
    LaunchedEffect(Unit) {
        vm.reloadDevices()
        vm.loadActiveEvent(force = true)
    }

    var showBind by remember { mutableStateOf(false) }
    var pendingUnbind by remember { mutableStateOf<DeploymentDeviceSummary?>(null) }
    var showStartConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val hasDraft = draft.name.isNotBlank() || draft.lane != null || draft.note.isNotBlank() ||
        draft.master != null || draft.slaves.isNotEmpty() ||
        draft.lText.isNotBlank() || draft.dText.isNotBlank() ||
        draft.precheckSafety || draft.precheckOrder

    BackHandler(enabled = hasDraft) { showDiscardConfirm = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            text = "锥桶协同布设",
            subtitle = "1 主桶 + N 从桶 · 按自身行程逐级启动",
            onBack = { if (hasDraft) showDiscardConfirm = true else onBack() },
        )

        StepIndicator(state.step)

        when (state.step) {
            1 -> StepDeviceSelection(
                vm = vm,
                draft = draft,
                masterCandidates = state.masterCandidates,
                slaveCandidates = state.slaveCandidates,
                setName = draft.master?.setId ?: state.deviceSet?.setId,
                onBind = { showBind = true },
                onUnbind = { pendingUnbind = it },
            )

            2 -> StepParams(vm = vm, draft = draft)

            else -> StepPrecheck(
                vm = vm,
                draft = draft,
                activeEvent = state.activeEvent,
                busy = state.busy,
                onStart = { showStartConfirm = true },
            )
        }

        // 底部步骤操作
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.step > 1) {
                SecondaryActionButton(
                    text = "上一步",
                    onClick = vm::prevStep,
                    modifier = Modifier.weight(1f),
                )
            }
            when (state.step) {
                1, 2 -> PrimaryActionButton(
                    text = if (state.step == 1) "下一步：设置参数" else "下一步：预检",
                    onClick = vm::nextStep,
                    modifier = Modifier.weight(1f),
                )

                else -> PrimaryActionButton(
                    text = if (state.busy) "正在启动…" else "开始协同布设",
                    onClick = { showStartConfirm = true },
                    modifier = Modifier.weight(1f),
                    enabled = draft.canStart() && state.activeEvent == null && !state.busy,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showBind) {
        BindDeviceDialog(
            busy = state.busy,
            onDismiss = { showBind = false },
            onBind = { code, bindingCode ->
                vm.bindDevice(code, bindingCode)
                showBind = false
            },
        )
    }

    pendingUnbind?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingUnbind = null },
            title = { Text("解绑设备？") },
            text = {
                Text(
                    "将把 ${device.deviceCode} 从当前账号/单位解绑。\n\n" +
                        "正在执行任务或被进行中布设事件占用的设备不能解绑。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.unbindDevice(device.deviceCode)
                        pendingUnbind = null
                    },
                ) { Text("确认解绑") }
            },
            dismissButton = { TextButton(onClick = { pendingUnbind = null }) { Text("取消") } },
        )
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

    if (showDiscardConfirm) {
        ConfirmDialog(
            title = "放弃本次布设？",
            message = "当前已填写的事件信息、设备选择和参数将被清空。",
            confirmText = "放弃并返回",
            onConfirm = {
                showDiscardConfirm = false
                vm.resetDraft()
                onBack()
            },
            onDismiss = { showDiscardConfirm = false },
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
private fun StepIndicator(step: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("1 选择设备", "2 参数预览", "3 预检开始").forEachIndexed { i, label ->
            val active = i + 1 == step
            val done = i + 1 < step
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            active -> InfoBlueBg
                            done -> SafeGreenBg
                            else -> NeutralGreyBg
                        },
                    )
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        active -> InfoBlue
                        done -> SafeGreen
                        else -> NeutralGrey
                    },
                )
            }
        }
    }
}

@Composable
private fun StepDeviceSelection(
    vm: DeploymentViewModel,
    draft: DeploymentDraft,
    masterCandidates: List<DeploymentDeviceSummary>,
    slaveCandidates: List<DeploymentDeviceSummary>,
    setName: String?,
    onBind: () -> Unit,
    onUnbind: (DeploymentDeviceSummary) -> Unit,
) {
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

    SectionCard(title = "选择主桶") {
        SafetyText("只能选择已绑定、在线、空闲、未急停的主桶。")
        Spacer(Modifier.height(6.dp))
        if (masterCandidates.isEmpty()) {
            Text("暂无已绑定且可用的主桶。请先扫码或输入编号绑定主桶。", color = WarnOrange)
        } else {
            masterCandidates.forEach { dev ->
                DeviceSelectRow(
                    device = dev,
                    selected = draft.master?.deviceCode == dev.deviceCode,
                    onClick = { vm.selectMaster(dev.deviceCode) },
                    onUnbind = { onUnbind(dev) },
                    radio = true,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryActionButton("扫码 / 输入编号绑定主桶", onClick = onBind)
    }

    SectionCard(title = "选择从桶（${draft.n}/${DeploymentLimits.N_MAX}）") {
        if (draft.master == null) {
            Text("请先选择主桶。", color = NeutralGrey)
            return@SectionCard
        }
        SafetyText("任意已绑定从桶都可加入任意主桶；套装只用于默认候选。绑定 ≠ 加入，勾选后才参与本次布设。")
        Spacer(Modifier.height(6.dp))
        if (slaveCandidates.isEmpty()) {
            Text("暂无已绑定且可用的从桶。请先扫码或输入编号绑定。", color = WarnOrange)
        } else {
            val setSlaves = slaveCandidates.filter { setName != null && it.setId == setName }
            val otherSlaves = slaveCandidates.filterNot { setName != null && it.setId == setName }
            if (setSlaves.isNotEmpty()) {
                Text("套装从桶（默认候选）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                setSlaves.forEach { dev ->
                    DeviceSelectRow(
                        device = dev,
                        selected = draft.slaves.any { it.device.deviceCode == dev.deviceCode },
                        onClick = { vm.toggleSlave(dev) },
                        onUnbind = { onUnbind(dev) },
                        radio = false,
                    )
                }
            }
            if (otherSlaves.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("其他已绑定从桶", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                otherSlaves.forEach { dev ->
                    DeviceSelectRow(
                        device = dev,
                        selected = draft.slaves.any { it.device.deviceCode == dev.deviceCode },
                        onClick = { vm.toggleSlave(dev) },
                        onUnbind = { onUnbind(dev) },
                        radio = false,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryActionButton("扫码 / 输入编号绑定从桶", onClick = onBind)
    }

    if (draft.slaves.isNotEmpty()) {
        SectionCard(title = "编队顺序（从 1…从 ${draft.n}）") {
            SafetyText("顺序决定目标行程：从 k 目标 = L − k×d。请与实际起始线队形一致。")
            Spacer(Modifier.height(6.dp))
            draft.slaves.sortedBy { it.order }.forEachIndexed { idx, sel ->
                SelectedSlaveRow(
                    sel = sel,
                    isFirst = idx == 0,
                    isLast = idx == draft.slaves.lastIndex,
                    onUp = { vm.moveSlave(sel.device.deviceCode, -1) },
                    onDown = { vm.moveSlave(sel.device.deviceCode, 1) },
                    onRemove = { vm.toggleSlave(sel.device) },
                )
            }
        }
    }
}

@Composable
private fun DeviceSelectRow(
    device: DeploymentDeviceSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onUnbind: () -> Unit,
    radio: Boolean,
) {
    val reason = device.unavailableReason()
    val enabled = device.selectable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) InfoBlueBg else Color.Transparent)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (radio) {
            RadioButton(selected = selected, onClick = { if (enabled) onClick() }, enabled = enabled)
        } else {
            Checkbox(checked = selected, onCheckedChange = { if (enabled) onClick() }, enabled = enabled)
        }
        Column(Modifier.weight(1f)) {
            Text(device.name.ifBlank { device.deviceCode }, fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(
                    if (device.online) "在线" else "离线",
                    if (device.online) SafeGreen else WarningRed,
                    if (device.online) SafeGreenBg else WarningRedBg,
                )
                if (device.occupied) StatusChip("被占用", WarnOrange, WarnOrangeBg)
                if (device.activeTaskId != null) StatusChip("任务中", WarnOrange, WarnOrangeBg)
            }
            Text(
                "${device.deviceCode} · 电量 ${device.batteryText}" +
                    (device.setId?.let { " · 套装 $it" } ?: "") +
                    (reason?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (reason != null) WarningRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUnbind) { Text("解绑", color = WarningRed) }
    }
}

@Composable
private fun SelectedSlaveRow(
    sel: SelectedSlave,
    isFirst: Boolean,
    isLast: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip("从 ${sel.order}", InfoBlue, InfoBlueBg)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(sel.device.name.ifBlank { sel.device.deviceCode }, fontWeight = FontWeight.Bold)
            Text(sel.device.deviceCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onUp, enabled = !isFirst) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
        }
        IconButton(onClick = onDown, enabled = !isLast) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "移除", tint = WarningRed)
        }
    }
}

@Composable
private fun StepParams(vm: DeploymentViewModel, draft: DeploymentDraft) {
    SectionCard(title = "布设参数") {
        OutlinedTextField(
            value = draft.lText,
            onValueChange = vm::updateL,
            label = { Text("主桶目标行程 L（米）") },
            singleLine = true,
            isError = draft.L == null || draft.step2Errors().isNotEmpty(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.dText,
            onValueChange = vm::updateD,
            label = { Text("相邻设备启动间隔 d（米）") },
            singleLine = true,
            isError = draft.d == null || draft.step2Errors().isNotEmpty(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "L、d 都是沿预定路线的累计行程（米），不是地图直线距离，也不是时间。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text("从桶数量 N = ${draft.n}", style = MaterialTheme.typography.bodyMedium)
        if (draft.n == 0) {
            Text("请先在步骤 1 选择从桶。", color = WarningRed, style = MaterialTheme.typography.labelSmall)
        }
    }

    SectionCard(title = "布设预览（沿路线累计行程）") {
        if (draft.preview.isEmpty()) {
            Text("填写 L、d 后显示主桶与各从桶的目标行程。", color = NeutralGrey)
        } else {
            draft.preview.forEach { p -> TargetRow(p) }
            Spacer(Modifier.height(8.dp))
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

    val errors = draft.step2Errors()
    if (errors.isNotEmpty()) {
        SectionCard(title = "参数校验") {
            errors.forEach { Text("• $it", color = WarningRed, style = MaterialTheme.typography.bodySmall) }
        }
    } else if (draft.L != null && draft.d != null) {
        Text("参数校验通过", color = SafeGreen, fontWeight = FontWeight.Bold)
    }

    SafetyBanner()
}

@Composable
private fun TargetRow(p: TargetPreview) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(
            p.orderLabel,
            if (p.role == DeploymentRole.MASTER) InfoBlue else NeutralGrey,
            if (p.role == DeploymentRole.MASTER) InfoBlueBg else NeutralGreyBg,
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
private fun StepPrecheck(
    vm: DeploymentViewModel,
    draft: DeploymentDraft,
    activeEvent: com.example.myapplication.core.model.deployment.DeploymentEvent?,
    busy: Boolean,
    onStart: () -> Unit,
) {
    val errors = draft.validate()
    SectionCard(title = "事件摘要") {
        Text("事件名：${draft.name.ifBlank { "锥桶协同布设" }}", fontWeight = FontWeight.Bold)
        Text("作业车道：${draft.laneLabel()}", style = MaterialTheme.typography.bodySmall)
        Text(
            "主桶：${draft.master?.deviceCode ?: "-"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "从桶：${draft.slaves.sortedBy { it.order }.joinToString(" → ") { "${it.device.deviceCode}(从${it.order})" }}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "L = ${draft.L?.let { DeploymentDraft.fmt(it) } ?: "-"} m · d = ${draft.d?.let { DeploymentDraft.fmt(it) } ?: "-"} m · N = ${draft.n}",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    SectionCard(title = "自动条件") {
        ConditionRow("参数合法", errors.isEmpty(), errors.joinToString("；"))
        ConditionRow("无其他进行中事件", activeEvent == null, activeEvent?.let { "已有 ${it.deploymentId}" } ?: "")
        ConditionRow("主桶已选择", draft.master != null, "")
        ConditionRow("从桶数量 1~${DeploymentLimits.N_MAX}", draft.n in 1..DeploymentLimits.N_MAX, "当前 ${draft.n}")
    }

    SectionCard(title = "人工现场确认") {
        CheckRow("我已现场确认：所有锥桶按编队顺序置于同一起始线，朝向布设方向，路线无障碍物和人员。", draft.precheckSafety, vm::toggleSafety)
        CheckRow("我已核对主桶/从桶编号与 APP 顺序一致。", draft.precheckOrder, vm::toggleOrder)
        if (!draft.precheckDone) {
            Text("请完成以上两项现场确认后才能开始。", color = WarningRed, style = MaterialTheme.typography.labelSmall)
        }
    }

    SafetyBanner()

    if (errors.isNotEmpty()) {
        SectionCard(title = "开始前请修正") {
            errors.forEach { Text("• $it", color = WarningRed, style = MaterialTheme.typography.bodySmall) }
        }
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

@Composable
private fun BindDeviceDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onBind: (String, String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var bindingCode by remember { mutableStateOf("") }
    val cameraPermission = rememberCameraPermissionState()
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        val contents = result.contents?.trim().orEmpty()
        if (contents.isNotEmpty()) {
            code = parseScannedCode(contents)
        }
    }

    fun launchScan() {
        if (cameraPermission.granted) {
            scanLauncher.launch(
                com.journeyapps.barcodescanner.ScanOptions()
                    .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setPrompt("将设备二维码对准取景框"),
            )
        } else {
            cameraPermission.onRequestResult = { granted ->
                if (granted) launchScan()
            }
            cameraPermission.request()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("绑定设备") },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(32) },
                    label = { Text("设备编号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bindingCode,
                    onValueChange = { bindingCode = it.take(32) },
                    label = { Text("绑定码（设备铭牌上）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = ::launchScan, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                    Text("扫码填写设备编号")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "绑定只是加入可选列表；绑定成功后请勾选加入本次布设。",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onBind(code.trim(), bindingCode.trim()) },
                enabled = !busy && code.isNotBlank() && bindingCode.isNotBlank(),
            ) { Text(if (busy) "绑定中…" else "绑定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 二维码内容兼容纯编号与 JSON（取 device_code）；解析失败按纯文本处理 */
private fun parseScannedCode(raw: String): String {
    val text = raw.trim()
    if (!text.startsWith("{")) return text
    return runCatching {
        org.json.JSONObject(text).optString("device_code").takeIf { it.isNotBlank() } ?: text
    }.getOrDefault(text)
}
