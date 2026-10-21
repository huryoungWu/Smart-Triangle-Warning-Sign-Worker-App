package com.example.myapplication.feature.deployment

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.MyAppApplication
import com.example.myapplication.core.data.api.DeploymentRepository
import com.example.myapplication.core.geo.GeoPoint
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
import com.example.myapplication.core.model.deployment.DeploymentState
import com.example.myapplication.core.model.deployment.VerifyResultState
import com.example.myapplication.core.model.vehicle.MoveDir
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class DeploymentUiState(
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
    /** 单桶手动干预对话框是否打开 */
    val manualMode: Boolean = false,
    /** 从 RUNNING 进入时正在 pause，pad 先禁用 */
    val manualEntering: Boolean = false,
    /** 当前选中的手动控制桶（默认主桶） */
    val manualDeviceCode: String? = null,
    /** 当前按住的方向（用于 pad 高亮） */
    val manualHoldingDir: MoveDir? = null,
)

/**
 * 执行页退出/重进时需要保留的锥桶轨迹与起点锚点。
 *
 * 轨迹原本放在 Compose `remember` 里，离开页面就会被销毁；改为按 deploymentId 缓存在
 * ViewModel 中，返回执行页时可以继续画同一条轨迹。
 */
class DeploymentConeTrailMemory internal constructor() {
    var anchor: GeoPoint? = null
    val points: SnapshotStateMap<String, List<GeoPoint>> = mutableStateMapOf()
}

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

    /** 按事件缓存轨记忆，最多保留 [MAX_TRAIL_EVENTS] 个，避免长时间使用无限增长。 */
    private val coneTrailMemories = mutableMapOf<String, DeploymentConeTrailMemory>()

    // ── 单桶手动干预 ──
    private var manualJob: Job? = null
    private var manualSessionId: String? = null
    private var manualSeq: Long = 0
    /** 进入/退出/离开页面的代数：pause 响应回来时若代数变化，不得重新打开手动框 */
    private var manualEpoch = 0

    /**
     * 会话代号：登录/切换服务器/登出时递增。
     * 异步响应回来时若代号已变化，说明是旧会话的请求，直接丢弃，避免旧数据覆盖新会话。
     */
    private var sessionEpoch = 0

    // ────────────────────────── 执行页轨迹缓存 ──────────────────────────

    fun coneTrailMemoryFor(deploymentId: String): DeploymentConeTrailMemory {
        coneTrailMemories[deploymentId]?.let { return it }
        if (coneTrailMemories.size >= MAX_TRAIL_EVENTS) {
            coneTrailMemories.keys.firstOrNull()?.let(coneTrailMemories::remove)
        }
        return DeploymentConeTrailMemory().also { coneTrailMemories[deploymentId] = it }
    }

    // ────────────────────────── 设备台账 ──────────────────────────

    fun reloadDevices() {
        val epoch = sessionEpoch
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            repository.listDevices()
                .onSuccess { devices ->
                    if (epoch != sessionEpoch) return@onSuccess
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
                            error = null,
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
                    if (epoch != sessionEpoch) return@onFailure
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

    // ────────────────────────── 设备连接页校验 ──────────────────────────

    /** 设备连接页「完成设备连接」：只校验设备，不校验事件名/车道/L/d。 */
    fun validateDevices(): Boolean {
        val errors = _uiState.value.draft.deviceErrors()
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(error = errors.joinToString("\n")) }
            return false
        }
        _uiState.update { it.copy(error = null) }
        return true
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
        val epoch = sessionEpoch
        viewModelScope.launch {
            repository.activeDeployment()
                .onSuccess { event ->
                    if (epoch != sessionEpoch) return@onSuccess
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
        stopJogAsync()
        manualEpoch++
        // 先清空旧事件，避免执行页轮询把旧事件响应写回；selectedEventId 用于丢弃过期响应
        _uiState.update {
            it.copy(
                selectedEventId = deploymentId,
                event = null,
                loading = true,
                error = null,
                message = null,
                manualMode = false,
                manualEntering = false,
                manualDeviceCode = null,
                manualHoldingDir = null,
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
                    if (applied) {
                        reconcileManualAfterPoll()
                        if (event.terminal) stopPolling()
                    }
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
        // 页面离开/事件结束时不再后台保活；HTTP stop 由 onExecScreenStopped/exitManualMode 负责
        manualJob?.cancel()
        manualJob = null
    }

    // ────────────────────────── 控制动作 ──────────────────────────

    fun pause(reason: String) {
        stopJogAsync()
        simpleAction { id -> repository.pause(id, reason) }
    }

    /**
     * 暂停全部（除 COMPLETED 外均可点击）：
     * - 终止态：只提示，不再请求；
     * - running：服务端置 paused 并停止所有运动；
     * - starting / pending_verify / paused 等：服务端只停止残余运动/手动，不强行改状态。
     */
    fun pauseAll() {
        val event = _uiState.value.event ?: return
        if (event.terminal) {
            _uiState.update { it.copy(message = "事件已结束，无需暂停") }
            return
        }
        stopJogAsync()
        val note = if (event.state == DeploymentState.PAUSED) {
            "当前已暂停，已停止残余运动"
        } else {
            "已停止所有正在移动的锥桶"
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            repository.pause(event.deploymentId, "pause_all")
                .onSuccess {
                    _uiState.update { it.copy(busy = false, message = note) }
                    refreshEvent()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "暂停全部失败") }
                }
        }
    }

    /** 先停止手动点动并等待 stop 完成，再恢复自动链路，避免 stop/resume 并发。 */
    fun resume() {
        val id = _uiState.value.event?.deploymentId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            stopJogSuspend()
            _uiState.update {
                it.copy(
                    busy = false,
                    manualMode = false,
                    manualEntering = false,
                    manualHoldingDir = null,
                )
            }
            repository.resume(id)
                .onSuccess { refreshEvent() }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "继续布设失败") }
                }
        }
    }

    fun abort(reason: String) {
        stopJogAsync()
        simpleAction { id -> repository.abort(id, reason) }
    }

    // ────────────────────────── 单桶手动干预 ──────────────────────────

    fun selectManualDevice(code: String) {
        if (_uiState.value.manualHoldingDir != null) return
        val event = _uiState.value.event ?: return
        if (event.device(code) == null) return
        _uiState.update { it.copy(manualDeviceCode = code, error = null) }
    }

    /** 进入手动干预：RUNNING 时先 pause 全部；等轮询确认 PAUSED 后才允许点动。 */
    fun enterManualMode() {
        val event = _uiState.value.event ?: return
        if (event.state == DeploymentState.PAUSED) {
            val selected = _uiState.value.manualDeviceCode
                ?.takeIf { event.jogEligible(it) }
                ?: event.defaultJogDeviceCode()
            _uiState.update {
                it.copy(
                    manualMode = true,
                    manualEntering = false,
                    manualDeviceCode = selected,
                    error = null,
                )
            }
            return
        }
        if (event.state != DeploymentState.RUNNING) {
            _uiState.update { it.copy(error = "当前状态不能手动干预") }
            return
        }
        val epoch = ++manualEpoch
        val selected = event.defaultJogDeviceCode()
        _uiState.update {
            it.copy(
                manualMode = true,
                manualEntering = true,
                manualDeviceCode = selected,
                error = null,
            )
        }
        viewModelScope.launch {
            val r = repository.pause(event.deploymentId, "manual_intervention")
            if (epoch != manualEpoch) return@launch
            if (r.isFailure) {
                _uiState.update {
                    it.copy(
                        manualMode = false,
                        manualEntering = false,
                        error = r.exceptionOrNull()?.message ?: "暂停全部锥桶失败",
                    )
                }
            } else {
                refreshEvent()
            }
        }
    }

    /** 关闭手动干预框：停止点动，事件保持 paused，不自动 resume。 */
    fun exitManualMode() {
        manualEpoch++
        stopJogAsync()
        _uiState.update {
            it.copy(
                manualMode = false,
                manualEntering = false,
                manualHoldingDir = null,
            )
        }
    }

    fun onLeaveManual() = exitManualMode()

    fun onExecScreenStopped() = exitManualMode()

    fun onHoldStart(dir: MoveDir) {
        val s = _uiState.value
        val event = s.event ?: return
        val device = s.manualDeviceCode?.let(event::device) ?: return
        val deviceType = device.deviceType ?: return
        if (!s.manualMode || s.manualEntering || s.manualHoldingDir != null ||
            event.state != DeploymentState.PAUSED || !device.manualEligible
        ) {
            if (s.manualHoldingDir == null) {
                _uiState.update { it.copy(error = "当前不能手动点动：请确认已暂停全部且所选桶可点动") }
            }
            return
        }

        val sessionId = UUID.randomUUID().toString()
        manualSessionId = sessionId
        manualSeq = 0
        _uiState.update { it.copy(manualHoldingDir = dir, error = null) }
        manualJob?.cancel()
        manualJob = viewModelScope.launch {
            while (isActive && _uiState.value.manualHoldingDir == dir) {
                val seq = ++manualSeq
                launch {
                    val result = repository.manualMove(
                        deploymentId = event.deploymentId,
                        deviceCode = device.deviceCode,
                        deviceType = deviceType,
                        dir = dir,
                        sessionId = sessionId,
                        seq = seq,
                    )
                    if (result.isFailure && isManualRejection(result.exceptionOrNull()?.message)) {
                        _uiState.update {
                            it.copy(
                                error = result.exceptionOrNull()?.message ?: "手动点动被拒绝",
                                manualHoldingDir = null,
                            )
                        }
                        stopJogAsync()
                    }
                }
                delay(MANUAL_KEEPALIVE_MS)
            }
        }
    }

    fun onHoldEnd() {
        if (_uiState.value.manualHoldingDir == null) return
        stopJogAsync()
        refreshEvent()
    }

    fun onManualStop() {
        stopJogAsync()
        refreshEvent()
    }

    private fun stopJogAsync() {
        val job = manualJob

        manualJob = null
        job?.cancel()
        val s = _uiState.value
        val event = s.event
        val device = s.manualDeviceCode?.let { event?.device(it) }
        val deviceType = device?.deviceType
        val sessionId = manualSessionId
        val seq = manualSeq
        manualSessionId = null
        if (event != null && device != null && deviceType != null && sessionId != null) {
            viewModelScope.launch {
                repository.manualStop(event.deploymentId, device.deviceCode, deviceType, sessionId, seq)
            }
        }
        _uiState.update { it.copy(manualHoldingDir = null) }
    }

    /** resume 前串行使用：取消保活并等待 manual/stop 完成。 */
    private suspend fun stopJogSuspend() {
        val job = manualJob
        manualJob = null
        job?.cancelAndJoin()
        val s = _uiState.value
        val event = s.event
        val device = s.manualDeviceCode?.let { event?.device(it) }
        val deviceType = device?.deviceType
        val sessionId = manualSessionId
        val seq = manualSeq
        manualSessionId = null
        _uiState.update { it.copy(manualHoldingDir = null) }
        if (event != null && device != null && deviceType != null && sessionId != null) {
            repository.manualStop(event.deploymentId, device.deviceCode, deviceType, sessionId, seq)
        }
    }

    /** 轮询后对账：事件离开 PAUSED / 所选设备失效 / 暂停完成。 */
    private fun reconcileManualAfterPoll() {
        val s = _uiState.value
        val event = s.event ?: return
        if (!s.manualMode) return

        if (event.terminal) {
            manualEpoch++
            stopJogAsync()
            _uiState.update {
                it.copy(manualMode = false, manualEntering = false, manualHoldingDir = null)
            }
            return
        }
        if (event.state != DeploymentState.PAUSED) {
            // RUNNING 且正在 pause：等下一拍；其他状态（starting 等）先保持框但禁用 pad
            if (!s.manualEntering) {
                manualEpoch++
                stopJogAsync()
                _uiState.update {
                    it.copy(manualMode = false, manualEntering = false, manualHoldingDir = null)
                }
            }
            return
        }

        var holding = s.manualHoldingDir
        var selectedCode = s.manualDeviceCode
        val selected = selectedCode?.let(event::device)
        if (selected == null || !selected.manualEligible) {
            if (holding != null) {
                stopJogAsync()
                holding = null
            }
            selectedCode = event.defaultJogDeviceCode()
        }
        _uiState.update {
            it.copy(
                manualEntering = false,
                manualHoldingDir = holding,
                manualDeviceCode = selectedCode,
            )
        }
    }

    private fun isManualRejection(message: String?): Boolean {
        val text = message.orEmpty()
        if (text.isBlank()) return false
        val transient = listOf(
            "超时", "连接", "不可达", "timeout", "connection", "network", "failed to connect",
        )
        return transient.none { text.contains(it, ignoreCase = true) }
    }

    fun retryCurrent() {
        stopJogAsync()
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
        stopJogAsync()
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
        stopJogAsync()
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
        stopJogAsync()
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
        val epoch = sessionEpoch
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.listDeployments()
                .onSuccess { list ->
                    if (epoch != sessionEpoch) return@onSuccess
                    _uiState.update { it.copy(records = list, loading = false) }
                }
                .onFailure { e ->
                    if (epoch != sessionEpoch) return@onFailure
                    _uiState.update {
                        it.copy(loading = false, error = e.message ?: "加载布设记录失败")
                    }
                }
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
        stopJogAsync()
        manualEpoch++
        createClientId = null
        _uiState.update {
            it.copy(
                draft = DeploymentDraft(),
                deviceSet = null,
                event = null,
                selectedEventId = null,
                error = null,
                message = null,
                manualMode = false,
                manualEntering = false,
                manualDeviceCode = null,
                manualHoldingDir = null,
            )
        }
        reloadDevices()
        loadActiveEvent()
    }

    // ────────────────────────── 会话生命周期 ──────────────────────────

    /** 登录成功/跳过登录后调用：清掉旧会话状态，再按新身份加载。 */
    fun onSessionStarted() {
        resetForNewSession()
        reloadDevices()
        loadActiveEvent(force = true)
    }

    /** 服务器地址已切换：旧服务端的数据全部作废，重新加载。 */
    fun onServerChanged() {
        resetForNewSession()
        reloadDevices()
    }

    /** 登出/切换账号：清空状态，不再发请求。 */
    fun clearSession() {
        resetForNewSession()
    }

    private fun resetForNewSession() {
        sessionEpoch++
        manualEpoch++
        stopPolling()
        manualJob?.cancel()
        manualJob = null
        manualSessionId = null
        manualSeq = 0
        createClientId = null
        coneTrailMemories.clear()
        _uiState.value = DeploymentUiState()
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    companion object {
        private const val MANUAL_KEEPALIVE_MS = 300L
        private const val MAX_TRAIL_EVENTS = 8

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyAppApplication
                DeploymentViewModel(app.serviceLocator.deploymentRepository)
            }
        }
    }
}
