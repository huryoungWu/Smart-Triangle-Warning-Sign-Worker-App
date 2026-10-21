package com.example.myapplication.feature.deployment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.MyAppApplication
import com.example.myapplication.core.data.api.DeploymentRepository
import com.example.myapplication.core.model.deployment.CommandResultState
import com.example.myapplication.core.model.deployment.DeploymentDeviceState
import com.example.myapplication.core.model.deployment.DeploymentDeviceSummary
import com.example.myapplication.core.model.deployment.DeploymentDraft
import com.example.myapplication.core.model.deployment.DeploymentEvent
import com.example.myapplication.core.model.deployment.DeploymentRecordSummary
import com.example.myapplication.core.model.deployment.DeploymentDeviceType
import com.example.myapplication.core.model.deployment.DeviceSet
import com.example.myapplication.core.model.deployment.ReportResultState
import com.example.myapplication.core.model.deployment.SelectedSlave
import com.example.myapplication.core.model.deployment.SkipDisposition
import com.example.myapplication.core.model.deployment.VerifyResultState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class DeploymentUiState(
    val step: Int = 1,
    val draft: DeploymentDraft = DeploymentDraft(),
    val allDevices: List<DeploymentDeviceSummary> = emptyList(),
    val masterCandidates: List<DeploymentDeviceSummary> = emptyList(),
    val slaveCandidates: List<DeploymentDeviceSummary> = emptyList(),
    val deviceSet: DeviceSet? = null,
    val activeEvent: DeploymentEvent? = null,
    val event: DeploymentEvent? = null,
    /** 当前明确选择查看的事件 ID；用于丢弃切换事件后仍在途的旧轮询响应 */
    val selectedEventId: String? = null,
    val records: List<DeploymentRecordSummary> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val lastRefreshAt: Long = 0L,
)

/**
 * 锥桶协同布设 ViewModel（独立于单设备 VehicleViewModel）。
 *
 * - 事件状态以服务端聚合状态为准；执行页可见时 1Hz 轮询；
 * - 参数校验与预览为纯函数（DeploymentDraft）；
 * - 任何异常都展示给用户，不自动跳过/不自动重发运动指令。
 */
class DeploymentViewModel(
    private val repository: DeploymentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeploymentUiState())
    val uiState: StateFlow<DeploymentUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var createClientId: String? = null

    init {
        reloadDevices()
        loadActiveEvent()
    }

    // ────────────────────────── 设备台账 ──────────────────────────

    fun reloadDevices() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            repository.listDevices()
                .onSuccess { devices ->
                    _uiState.update { s ->
                        val masters = devices.filter { it.deviceType == DeploymentDeviceType.MASTER_CONE }
                        val slaves = devices.filter { it.deviceType == DeploymentDeviceType.SLAVE_CONE }
                        val keepMaster = s.draft.master?.deviceCode
                            ?.let { code -> masters.firstOrNull { it.deviceCode == code } }
                        val keptSlaves = s.draft.slaves.mapNotNull { sel ->
                            slaves.firstOrNull { it.deviceCode == sel.device.deviceCode }
                                ?.let { SelectedSlave(it, sel.order) }
                        }
                        s.copy(
                            loading = false,
                            allDevices = devices,
                            masterCandidates = masters,
                            slaveCandidates = mergeSlaveCandidates(slaves, s.deviceSet),
                            draft = s.draft.copy(
                                master = keepMaster ?: s.draft.master?.let { old -> masters.firstOrNull { it.deviceCode == old.deviceCode } },
                                slaves = renumber(keptSlaves),
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(loading = false, error = e.message ?: "无法获取设备列表") }
                }
        }
    }

    private fun mergeSlaveCandidates(
        allSlaves: List<DeploymentDeviceSummary>,
        set: DeviceSet?,
    ): List<DeploymentDeviceSummary> {
        val setSlaves = set?.slaves.orEmpty()
        val setCodes = setSlaves.map { it.deviceCode }.toSet()
        // 套装接口只返回精简字段；优先用设备台账的完整摘要（含 setId/电量/占用），
        // 否则会把套装从桶误判成“其他从桶”，电量也丢失。
        val fullByCode = allSlaves.associateBy { it.deviceCode }
        val mergedSet = setSlaves.map { s -> fullByCode[s.deviceCode] ?: s }
        return (mergedSet + allSlaves.filterNot { it.deviceCode in setCodes })
            .distinctBy { it.deviceCode }
    }

    fun selectMaster(code: String) {
        createClientId = null
        val master = _uiState.value.allDevices.firstOrNull { it.deviceCode == code } ?: return
        if (!master.online || !master.available || master.occupied || master.estopLocked) {
            _uiState.update { it.copy(error = master.unavailableReason() ?: "该主桶不可用") }
            return
        }
        _uiState.update {
            it.copy(
                draft = it.draft.copy(master = master, slaves = emptyList(), lText = it.draft.lText, dText = it.draft.dText),
                deviceSet = null,
                slaveCandidates = it.allDevices.filter { d -> d.deviceType == DeploymentDeviceType.SLAVE_CONE && d.deviceCode != code },
            )
        }
        viewModelScope.launch {
            repository.getDeviceSet(code)
                .onSuccess { set ->
                    _uiState.update { s ->
                        s.copy(
                            deviceSet = set,
                            slaveCandidates = mergeSlaveCandidates(
                                s.allDevices.filter { d -> d.deviceType == DeploymentDeviceType.SLAVE_CONE },
                                set,
                            ),
                        )
                    }
                }
                .onFailure {
                    // 主桶可能没有套装；这不是致命错误，仍可选其他已绑定从桶
                    _uiState.update { s -> s.copy(deviceSet = DeviceSet(null, master, emptyList())) }
                }
        }
    }

    fun toggleSlave(device: DeploymentDeviceSummary) {
        createClientId = null
        _uiState.update { s ->
            val selected = s.draft.slaves.firstOrNull { it.device.deviceCode == device.deviceCode }
            val newSlaves = if (selected != null) {
                s.draft.slaves.filterNot { it.device.deviceCode == device.deviceCode }
            } else {
                if (!device.selectable) {
                    return@update s.copy(error = device.unavailableReason() ?: "该从桶不可用")
                }
                if (s.draft.slaves.size >= com.example.myapplication.core.model.deployment.DeploymentLimits.N_MAX) {
                    return@update s.copy(error = "从桶数量不能超过 ${com.example.myapplication.core.model.deployment.DeploymentLimits.N_MAX}")
                }
                s.draft.slaves + SelectedSlave(device, s.draft.slaves.size + 1)
            }
            s.copy(draft = s.draft.copy(slaves = renumber(newSlaves)), error = null)
        }
    }

    fun moveSlave(code: String, delta: Int) {
        createClientId = null
        _uiState.update { s ->
            val list = s.draft.slaves.sortedBy { it.order }.toMutableList()
            val idx = list.indexOfFirst { it.device.deviceCode == code }
            if (idx < 0) return@update s
            val target = (idx + delta).coerceIn(0, list.lastIndex)
            if (target == idx) return@update s
            val tmp = list[idx]
            list[idx] = list[target]
            list[target] = tmp
            s.copy(draft = s.draft.copy(slaves = renumber(list)))
        }
    }

    private fun renumber(list: List<SelectedSlave>): List<SelectedSlave> =
        list.mapIndexed { i, s -> s.copy(order = i + 1) }

    // ────────────────────────── 草稿字段 ──────────────────────────

    fun updateName(v: String) = updateDraft { it.copy(name = v.take(40)) }
    fun updateLane(v: Int?) = updateDraft { it.copy(lane = v) }
    fun updateNote(v: String) = updateDraft { it.copy(note = v.take(120)) }
    fun updateL(v: String) = updateDraft { it.copy(lText = v.filter { c -> c.isDigit() || c == '.' || c == '-' }) }
    fun updateD(v: String) = updateDraft { it.copy(dText = v.filter { c -> c.isDigit() || c == '.' || c == '-' }) }
    fun toggleSafety() = updateDraft { it.copy(precheckSafety = !it.precheckSafety) }
    fun toggleOrder() = updateDraft { it.copy(precheckOrder = !it.precheckOrder) }

    private fun updateDraft(block: (DeploymentDraft) -> DeploymentDraft) {
        createClientId = null // 草稿变化后重新创建，避免复用旧事件的幂等键
        _uiState.update { it.copy(draft = block(it.draft), error = null) }
    }

    // ────────────────────────── 绑定 / 解绑 ──────────────────────────

    fun bindDevice(deviceCode: String, bindingCode: String, deviceType: DeploymentDeviceType) {
        val code = deviceCode.trim()
        if (code.isBlank()) {
            _uiState.update { it.copy(error = "设备编号不能为空") }
            return
        }
        if (bindingCode.isBlank()) {
            _uiState.update { it.copy(error = "请输入设备铭牌上的绑定码") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            repository.bindDevice(code, bindingCode.trim(), deviceType)
                .onSuccess { dev ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = "设备 ${dev.deviceCode}（${dev.deviceType?.token ?: deviceType.token}）已绑定，请勾选加入本次布设",
                        )
                    }
                    reloadDevices()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "绑定失败") }
                }
        }
    }

    fun unbindDevice(deviceCode: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            repository.unbindDevice(deviceCode)
                .onSuccess {
                    _uiState.update { s ->
                        val slaves = s.draft.slaves.filterNot { it.device.deviceCode == deviceCode }
                        s.copy(busy = false, message = "设备 $deviceCode 已解绑", draft = s.draft.copy(slaves = renumber(slaves)))
                    }
                    reloadDevices()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "解绑失败") }
                }
        }
    }

    // ────────────────────────── 步骤 ──────────────────────────

    fun nextStep() {
        val s = _uiState.value
        when (s.step) {
            1 -> {
                val errors = s.draft.step1Errors()
                if (errors.isNotEmpty()) {
                    _uiState.update { it.copy(error = errors.joinToString("\n")) }
                    return
                }
                _uiState.update { it.copy(step = 2, error = null) }
            }
            2 -> {
                val errors = s.draft.step2Errors()
                if (errors.isNotEmpty()) {
                    _uiState.update { it.copy(error = errors.joinToString("\n")) }
                    return
                }
                _uiState.update { it.copy(step = 3, error = null) }
            }
        }
    }

    fun prevStep() {
        _uiState.update { it.copy(step = (it.step - 1).coerceAtLeast(1), error = null) }
    }

    // ────────────────────────── 启动 / 查询 ──────────────────────────

    fun start(onStarted: (String) -> Unit) {
        val s = _uiState.value
        val errors = s.draft.validate()
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(error = errors.joinToString("\n")) }
            return
        }
        val clientId = createClientId ?: UUID.randomUUID().toString().also { createClientId = it }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            repository.createDeployment(s.draft, clientId)
                .onSuccess { created ->
                    repository.startDeployment(created.deploymentId, UUID.randomUUID().toString())
                        .onSuccess { running ->
                            _uiState.update {
                                it.copy(
                                    busy = false,
                                    event = running,
                                    selectedEventId = running.deploymentId,
                                    activeEvent = if (running.terminal) null else running,
                                    step = 1,
                                )
                            }
                            onStarted(running.deploymentId)
                        }
                        .onFailure { e ->
                            _uiState.update { it.copy(busy = false, error = e.message ?: "启动布设失败") }
                        }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "创建布设事件失败") }
                }
        }
    }

    /**
     * 查询当前进行中事件。
     * [force]：向导入口需要强制检查；执行页在用户已明确选择某事件时不应被“当前活动事件”覆盖。
     */
    fun loadActiveEvent(force: Boolean = false) {
        if (!force && _uiState.value.selectedEventId != null) return
        viewModelScope.launch {
            repository.activeDeployment()
                .onSuccess { event ->
                    _uiState.update { s ->
                        if (!force && s.selectedEventId != null) s
                        else s.copy(
                            activeEvent = event,
                            event = event ?: s.event,
                            selectedEventId = event?.deploymentId ?: s.selectedEventId,
                        )
                    }
                }
                .onFailure {
                    // 入口检查失败不阻塞向导；真正创建/启动时服务端会再次校验
                }
        }
    }

    fun openEvent(deploymentId: String) {
        // 先清空旧事件，避免执行页轮询把旧事件响应写回；selectedEventId 用于丢弃过期响应
        _uiState.update {
            it.copy(
                selectedEventId = deploymentId,
                event = null,
                loading = true,
                error = null,
                message = null,
            )
        }
        viewModelScope.launch {
            repository.getDeployment(deploymentId)
                .onSuccess { event ->
                    _uiState.update { s ->
                        if (s.selectedEventId != deploymentId) s
                        else s.copy(
                            loading = false,
                            event = event,
                            activeEvent = if (event.terminal) null else event,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { s ->
                        if (s.selectedEventId != deploymentId) s
                        else s.copy(loading = false, error = e.message ?: "加载布设事件失败")
                    }
                }
        }
    }

    fun refreshEvent() {
        val id = _uiState.value.event?.deploymentId ?: return
        viewModelScope.launch {
            repository.getDeployment(id)
                .onSuccess { event ->
                    var applied = false
                    _uiState.update { s ->
                        // 事件已切换：丢弃在途的旧响应，避免把新事件覆盖回旧事件
                        if (s.event?.deploymentId != id) s
                        else {
                            applied = true
                            s.copy(
                                event = event,
                                activeEvent = if (event.terminal) null else event,
                                lastRefreshAt = System.currentTimeMillis(),
                            )
                        }
                    }
                    if (applied && event.terminal) stopPolling()
                }
                .onFailure { e ->
                    _uiState.update { s ->
                        if (s.event?.deploymentId == id) {
                            s.copy(error = e.message ?: "刷新布设状态失败")
                        } else s
                    }
                }
        }
    }

    /** 执行页可见时启动 1Hz 轮询（与服务端聚合状态一致） */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshEvent()
                delay(1000L)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ────────────────────────── 控制动作 ──────────────────────────

    fun pause(reason: String) = simpleAction { id -> repository.pause(id, reason) }
    fun resume() = simpleAction { id -> repository.resume(id) }
    fun abort(reason: String) = simpleAction { id -> repository.abort(id, reason) }

    fun retryCurrent() {
        val event = _uiState.value.event ?: return
        val startTarget = event.devices.firstOrNull {
            it.startCommand.state == CommandResultState.TIMEOUT ||
                it.startCommand.state == CommandResultState.REJECTED ||
                it.phase == com.example.myapplication.core.model.deployment.DeploymentPhase.FAILED
        }
        val reportTarget = event.devices.firstOrNull { it.report.state == ReportResultState.LOST }
        val target = startTarget ?: reportTarget
        if (target == null) {
            _uiState.update { it.copy(error = "当前没有可重试的步骤") }
            return
        }
        val type = if (startTarget != null) "start_command" else "report"
        val targetType = target.deviceType
        if (targetType == null) {
            _uiState.update { it.copy(error = "设备 ${target.deviceCode} 缺少 device_type，无法重试") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            repository.retry(
                deploymentId = event.deploymentId,
                type = type,
                deviceCode = target.deviceCode,
                deviceType = targetType,
                originalCommandId = target.startCommand.commandId,
                reportId = if (type == "report") "${event.deploymentId}:${target.deviceCode}:interval:${target.order}" else null,
            ).onSuccess {
                _uiState.update { it.copy(busy = false, message = "已按安全流程重试；请确认设备状态") }
                refreshEvent()
            }.onFailure { e ->
                _uiState.update { it.copy(busy = false, error = e.message ?: "重试失败") }
            }
        }
    }

    fun skip(device: DeploymentDeviceState, reason: String, disposition: SkipDisposition, note: String = "") {
        val id = _uiState.value.event?.deploymentId ?: return
        if (reason.isBlank()) {
            _uiState.update { it.copy(error = "请填写人工跳过原因") }
            return
        }
        val deviceType = device.deviceType
        if (deviceType == null) {
            _uiState.update { it.copy(error = "设备 ${device.deviceCode} 缺少 device_type，无法跳过") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            repository.skip(id, device.deviceCode, deviceType, reason.trim(), disposition, note)
                .onSuccess {
                    _uiState.update { it.copy(busy = false, message = "已人工跳过 ${device.deviceCode}；事件将记录为带异常完成") }
                    refreshEvent()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "人工跳过失败") }
                }
        }
    }

    fun verify(device: DeploymentDeviceState, pass: Boolean, note: String) {
        val id = _uiState.value.event?.deploymentId ?: return
        val deviceType = device.deviceType
        if (deviceType == null) {
            _uiState.update { it.copy(error = "设备 ${device.deviceCode} 缺少 device_type，无法核验") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            repository.verify(id, device.deviceCode, deviceType, pass, note)
                .onSuccess {
                    _uiState.update { it.copy(busy = false, message = "已记录核验结果") }
                    refreshEvent()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "核验失败") }
                }
        }
    }

    fun complete() {
        val id = _uiState.value.event?.deploymentId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            repository.complete(id)
                .onSuccess {
                    _uiState.update { it.copy(busy = false, message = "布设事件已完成") }
                    refreshEvent()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "完成布设失败") }
                }
        }
    }

    fun loadRecords() {
        viewModelScope.launch {
            repository.listDeployments()
                .onSuccess { list -> _uiState.update { it.copy(records = list) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "加载布设记录失败") } }
        }
    }

    private fun simpleAction(block: suspend (String) -> Result<Unit>) {
        val id = _uiState.value.event?.deploymentId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            block(id)
                .onSuccess {
                    _uiState.update { it.copy(busy = false) }
                    refreshEvent()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "操作失败") }
                }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    fun resetDraft() {
        createClientId = null
        _uiState.update {
            it.copy(
                step = 1,
                draft = DeploymentDraft(),
                deviceSet = null,
                event = null,
                selectedEventId = null,
                error = null,
                message = null,
            )
        }
        reloadDevices()
        loadActiveEvent()
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyAppApplication
                DeploymentViewModel(app.serviceLocator.deploymentRepository)
            }
        }
    }
}
