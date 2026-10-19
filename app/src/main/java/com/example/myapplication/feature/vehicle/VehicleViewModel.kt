package com.example.myapplication.feature.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.MyAppApplication
import com.example.myapplication.core.data.api.CmdAck
import com.example.myapplication.core.data.api.DeviceRepository
import com.example.myapplication.core.data.api.SnapshotUploadRepository
import com.example.myapplication.core.data.api.TaskUploadRepository
import com.example.myapplication.core.data.prefs.SettingsRepository
import com.example.myapplication.core.data.record.WorkRecordRepository
import com.example.myapplication.core.model.AppDialog
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.di.UploadMode
import com.example.myapplication.core.model.vehicle.CommandKind
import com.example.myapplication.core.model.vehicle.CommandPhase
import com.example.myapplication.core.model.vehicle.CommandTicket
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.DeviceSummary
import com.example.myapplication.core.model.vehicle.RouteSegment
import com.example.myapplication.core.model.vehicle.TaskSpec
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MotionState
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.core.model.vehicle.OneTouchStage
import com.example.myapplication.core.model.vehicle.PendingVerify
import com.example.myapplication.core.model.vehicle.PrecheckHuman
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.model.vehicle.TaskSource
import com.example.myapplication.core.model.vehicle.VehicleMode
import com.example.myapplication.core.model.vehicle.VehicleUiState
import com.example.myapplication.core.model.vehicle.WorkRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 全局设备状态机（M01-M10）—— Activity 级共享唯一状态源。
 *
 * 落实规格第 4 节：
 * 1. 命令五层分层，超时标"结果未知"，**不自动重复运动命令**
 * 2. 急停优先级最高，普通停车 ≠ 急停锁定
 * 3. 灯光与运动分离（停车/急停/失联都不自动关灯）
 * 4. 离线命令禁排队：失联不缓存命令，重连只查状态
 * 5. 数据真实性：位置仅编码器估算，一切以设备回包为准
 */
class VehicleViewModel(
    private val deviceRepository: DeviceRepository,
    private val workRecordRepository: WorkRecordRepository,
    private val settingsRepository: SettingsRepository,
    private val taskUploadRepository: TaskUploadRepository,
    private val snapshotRepository: com.example.myapplication.core.data.media.SnapshotRepository,
    /** 快照上传（SERVER 模式启用；演示模式为 null，仅存本机） */
    private val snapshotUploadRepository: SnapshotUploadRepository?,
    private val deviceRegistryRepository: com.example.myapplication.core.data.device.DeviceRegistryRepository,
    /** 登录会话：连接设备时把**登录账号与密码**随绑定请求上报（见 [connect]） */
    private val authRepository: com.example.myapplication.core.data.api.AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VehicleUiState())
    val uiState: StateFlow<VehicleUiState> = _uiState.asStateFlow()

    val records: Flow<List<WorkRecord>> = workRecordRepository.records

    /** 已抓拍的任务快照 */
    val snapshots: Flow<List<com.example.myapplication.core.model.media.TaskSnapshot>> =
        snapshotRepository.snapshots

    /** 用户添加的设备（连接页「添加设备」弹窗内展示，可手动删除） */
    val customDevices: Flow<List<DeviceSummary>> = deviceRegistryRepository.devices

    /** 连接（bind + 初次状态读取）协程 */
    private var connectJob: Job? = null
    /** 手动刷新状态协程（同一时刻只允许一个） */
    private var refreshJob: Job? = null
    private var keepaliveJob: Job? = null
    /** 实时状态轮询（任务执行页/地图页可见时启用，设备端 1Hz） */
    private var pollJob: Job? = null
    private var liveStatusRefs = 0
    /** 串行化轮询与手动刷新，避免同一时刻发出两条状态请求 */
    private val statusMutex = Mutex()
    private var cmdSeq = 1

    /** 进行中任务的本地记录 id（任务结束时落库） */
    private var activeRecordId: String? = null
    private var activeRecordStartedAt = 0L
    private val writtenTaskIds = mutableSetOf<String>()
    private var activeSpecRef: TaskSpec? = null

    /** 上一帧设备回报的任务状态（自动快照：检测任务开始/结束迁移用） */
    private var lastTaskRunState: TaskRunState? = null

    init {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    draft = TaskSpec.defaultFixed(),
                    lightSetting = settingsRepository.defaultLight.first(),
                )
            }
            // 加载可选设备列表（内置 + 用户已添加），默认选第一台
            val devices = loadDeviceList()
            _uiState.update {
                it.copy(
                    deviceList = devices,
                    selectedDeviceCode = devices.firstOrNull()?.id ?: "",
                )
            }
        }
    }

    /** 选择要连接的设备（连接页下拉） */
    fun selectDevice(deviceCode: String) {
        if (_uiState.value.connection == ConnectionPhase.CONNECTED) return
        _uiState.update { it.copy(selectedDeviceCode = deviceCode) }
    }

    /** 设备列表 = 内置设备 + 用户已添加登记（按编号去重） */
    private suspend fun loadDeviceList(): List<DeviceSummary> {
        val builtin = deviceRepository.listDevices()
        val added = deviceRegistryRepository.devices.first()
        return (builtin + added).distinctBy { it.id }
    }

    /**
     * 重新拉取设备列表（登录/切换账号完成后由登录页回调触发）。
     * 设备信息按登录用户获取：登录前拉到的是未鉴权（默认用户）的列表，登录成功后需要刷新。
     */
    fun reloadDevices() {
        viewModelScope.launch {
            val devices = loadDeviceList()
            _uiState.update { s ->
                val keep = s.selectedDeviceCode.takeIf { id -> devices.any { it.id == id } }
                s.copy(
                    deviceList = devices,
                    selectedDeviceCode = keep ?: devices.firstOrNull()?.id ?: "",
                )
            }
        }
    }

    /**
     * 添加设备（连接页「添加设备」：手动输入设备编号或扫码）。
     * 登记到本机设备表后立即可选；同编号重复添加时直接选中已有项。
     */
    fun addDevice(rawId: String) {
        val id = rawId.trim()
        if (id.isEmpty()) {
            rejectMotion("设备编号不能为空")
            return
        }
        if (id.length > MAX_DEVICE_CODE_LEN) {
            rejectMotion("设备编号不能超过 $MAX_DEVICE_CODE_LEN 个字符")
            return
        }
        val existing = _uiState.value.deviceList.firstOrNull { it.id.equals(id, ignoreCase = true) }
        if (existing != null) {
            _uiState.update {
                it.copy(
                    selectedDeviceCode = existing.id,
                    dialog = AppDialog.Alert("设备已存在", "编号 ${existing.id} 已在设备列表中，已为你选中该设备。"),
                )
            }
            return
        }
        val summary = DeviceSummary(
            id = id,
            name = "自定义设备",
            started = false,
            available = true,
            batteryPercent = -1,
        )
        viewModelScope.launch {
            deviceRegistryRepository.add(summary)
            _uiState.update {
                it.copy(
                    deviceList = loadDeviceList(),
                    selectedDeviceCode = id,
                    dialog = AppDialog.Alert("添加成功", "设备 $id 已添加并选中，可直接连接。"),
                )
            }
        }
    }

    /**
     * 删除用户添加的设备（连接页「添加设备」弹窗内手动删除）。
     * 删除后若该设备正被选中，自动改选列表中的第一台；正连接的设备须先断开连接。
     */
    fun removeDevice(deviceCode: String) {
        val id = deviceCode.trim()
        if (id.isEmpty()) return
        if (_uiState.value.connection == ConnectionPhase.CONNECTED &&
            _uiState.value.selectedDeviceCode == id
        ) {
            rejectMotion("该设备已连接，请先断开连接后再删除")
            return
        }
        viewModelScope.launch {
            deviceRegistryRepository.remove(id)
            val devices = loadDeviceList()
            _uiState.update {
                it.copy(
                    deviceList = devices,
                    selectedDeviceCode = if (it.selectedDeviceCode == id) {
                        devices.firstOrNull()?.id ?: ""
                    } else {
                        it.selectedDeviceCode
                    },
                    dialog = AppDialog.Alert(
                        "设备已删除",
                        "设备 $id 已从本机设备列表移除。如需恢复，重新输入编号或扫码添加即可。",
                    ),
                )
            }
        }
    }

    // ==================== M01 设备连接 ====================

    /**
     * 连接设备（M01）：**先带绑定码连接（连接即绑定）**，成功后**只读取一次**初始状态。
     *
     * 之后不再有任何定时请求；状态只在用户点击「刷新状态」时重新读取（见 [refreshStatus]）。
     *
     * 连接请求 5 个字段：登录账号/密码（[authRepository] 登录后带入，未登录省略）+ 设备编号
     * （[VehicleUiState.selectedDeviceCode]）+ [bindingCode]（用户输入）+ 设备类型（默认 `triangle`）。
     * 连接失败（绑定码错误/已被绑定/设备不存在）保持未连接并原样展示服务端原因。
     */
    fun connect(bindingCode: String) {
        if (connectJob?.isActive == true || refreshJob?.isActive == true) return
        val selectedId = _uiState.value.selectedDeviceCode
        if (selectedId.isBlank()) {
            rejectMotion("请先选择要连接的设备")
            return
        }
        val code = bindingCode.trim()
        if (code.isEmpty()) {
            rejectMotion("请输入绑定码（设备铭牌上的绑定码），才能连接设备")
            return
        }
        // 把所选设备 id 下发到设备层（Fake / 服务端实现据此回对应设备信息与路由命令）
        (deviceRepository as? com.example.myapplication.core.data.device.FakeDeviceRepository)?.let {
            it.selectedDeviceCode = selectedId
        }
        (deviceRepository as? com.example.myapplication.core.data.device.ServerDeviceRepository)?.let {
            it.selectedDeviceCode = selectedId
        }
        _uiState.update { it.copy(connection = ConnectionPhase.CONNECTING) }
        connectJob = viewModelScope.launch {
            val credentials = authRepository.currentCredentials
            val connected = deviceRepository.connect(
                com.example.myapplication.core.data.api.DeviceConnectRequest(
                    username = credentials?.username,
                    password = credentials?.password,
                    deviceCode = selectedId,
                    bindingCode = code,
                    deviceType = com.example.myapplication.core.model.vehicle.DeviceType.TRIANGLE,
                ),
            )
            if (connected.isFailure) {
                val reason = connected.exceptionOrNull()?.message ?: "网络异常"
                _uiState.update {
                    it.copy(
                        connection = ConnectionPhase.DISCONNECTED,
                        dialog = AppDialog.Warn(
                            "连接失败",
                            "设备 $selectedId 连接失败：$reason\n\n" +
                                "请核对设备编号与绑定码（设备铭牌上）后重试；若设备已被其他账号绑定，请先在设备台账中确认归属。",
                        ),
                    )
                }
                return@launch
            }
            // 连接成功后的初始状态
            fetchStatusOnce()
            // 如果当前页面已请求实时状态（如重连），恢复轮询
            startStatusPollingIfNeeded()
        }
    }

    /** 任务执行页/地图页可见时申请实时状态（引用计数，支持页面切换）。 */
    fun acquireLiveStatus() {
        liveStatusRefs += 1
        startStatusPollingIfNeeded()
    }

    /** 对应页面离开/切后台时释放实时状态。 */
    fun releaseLiveStatus() {
        liveStatusRefs = (liveStatusRefs - 1).coerceAtLeast(0)
        if (liveStatusRefs == 0) stopStatusPolling()
    }

    private fun startStatusPollingIfNeeded() {
        if (liveStatusRefs <= 0 || pollJob?.isActive == true) return
        if (_uiState.value.connection == ConnectionPhase.DISCONNECTED) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                statusMutex.withLock { fetchStatusOnce() }
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopStatusPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 手动刷新设备状态（唯一的状态重新读取入口）。
     *
     * - 仅在「已连接」或「已断开/状态未确认」时可用；刷新成功后由 [onSnapshot] 恢复已连接。
     * - 刷新期间忽略重复点击，保证同一时刻只有一个状态请求。
     * - 不自恢复、不重试：失败后由用户再次点击刷新。
     */
    fun refreshStatus() {
        val state = _uiState.value
        if (state.connection != ConnectionPhase.CONNECTED &&
            state.connection != ConnectionPhase.LOST
        ) {
            return
        }
        if (connectJob?.isActive == true || refreshJob?.isActive == true) return

        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                statusMutex.withLock { fetchStatusOnce() }
            } finally {
                _uiState.update { it.copy(refreshing = false) }
            }
        }
    }

    /** 状态接口的唯一读取出口：连接后的初次读取与手动刷新共用 */
    private suspend fun fetchStatusOnce() {
        deviceRepository.fetchStatus()
            .onSuccess { snapshot ->
                onSnapshot(snapshot)
            }.onFailure {
                onLinkLost()
            }
    }

    /** 手动断开连接（M01）：停止保持包与状态刷新任务，清空设备状态回未连接 */
    fun disconnect() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        refreshJob?.cancel()
        refreshJob = null
        stopStatusPolling()
        connectJob?.cancel()
        connectJob = null
        _uiState.update {
            it.copy(
                connection = ConnectionPhase.DISCONNECTED,
                snapshot = null,
                lastUpdateAt = 0L,
                holdingMove = false,
                holdingDir = null,
                activeTicket = null,
                stopUnconfirmed = false,
                refreshing = false,
                taskCompletedByUser = false,
            )
        }
    }

    /**
     * 服务端地址已切换（设置 → 服务器地址）：旧连接、旧设备列表全部作废。
     *
     * 必须在 `ServiceLocator.applyServerBaseUrl` **之后**调用 —— 此时地址已生效，
     * [reloadDevices] 才会打到新服务端。不清空的话，状态刷新与 300ms 遥控保持包
     * 会安静地继续打向新服务端。
     */
    fun onServerChanged() {
        disconnect()
        _uiState.update { it.copy(deviceList = emptyList(), selectedDeviceCode = "") }
        reloadDevices()
    }

    /** 失联立即显示"已断开／状态未确认"并禁用运动操作（M01） */
    private fun onLinkLost() {
        val wasConnected = _uiState.value.connection == ConnectionPhase.CONNECTED
        val taskWasRunning = lastTaskRunState == TaskRunState.RUNNING
        if (_uiState.value.holdingMove) {
            // 失联：清"按住中"，尝试停车（命令标 UNKNOWN，不排队不重发）
            _uiState.update { it.copy(holdingMove = false, holdingDir = null) }
        }
        // 失联时清空未确认命令票据（离线命令禁排队）；
        // 断线时任务在跑则补拍结束快照，提示并入本弹窗（弹窗位只有一个，安全提示优先）
        _uiState.update {
            it.copy(
                connection = ConnectionPhase.LOST,
                activeTicket = it.activeTicket?.copy(
                    phase = if (it.activeTicket.phase == CommandPhase.VERIFIED) it.activeTicket.phase
                    else CommandPhase.UNKNOWN,
                    note = "失联，命令结果未知，请查询设备状态后现场确认",
                ),
                dialog = if (wasConnected) {
                    AppDialog.Warn(
                        "设备已断开",
                        "已断开／状态未确认。运动操作已禁用。\n\n" +
                            "重连后仅重新读取设备状态，不会重发断线前的移动、布置或恢复命令。" +
                            if (taskWasRunning) {
                                "\n\n断线时任务仍在执行，已自动补拍一张任务结束快照。"
                            } else "",
                    )
                } else it.dialog,
            )
        }
        // 若断线时有任务在跑，落一条"失联保护"记录并补一张任务结束快照（回包已停，状态迁移到不了）
        recordTaskIfNeeded(WorkRecord.RESULT_LINK_LOSS, "失联保护停车（车端断网停车）")
        if (taskWasRunning) {
            lastTaskRunState = TaskRunState.CANCELED
            saveSnapshot("任务结束（自动抓拍）", "任务失联保护停车自动抓拍")
        }
    }

    // ==================== 设备状态回包处理（唯一事实源） ====================

    private fun onSnapshot(snapshot: DeviceSnapshot) {
        val prevState = _uiState.value
        _uiState.update {
            it.copy(
                connection = ConnectionPhase.CONNECTED,
                snapshot = snapshot,
                lastUpdateAt = System.currentTimeMillis(),
            )
        }
        trackTaskAutoSnapshot(snapshot)

        // 停止确认（M08）：设备已反映停车 → 撤下"停止未确认"
        if (prevState.stopUnconfirmed &&
            (snapshot.motion == MotionState.IDLE || snapshot.estopLocked || snapshot.mode == VehicleMode.SAFE_STOP)
        ) {
            _uiState.update { it.copy(stopUnconfirmed = false) }
        }

        syncTicketWithSnapshot(snapshot)
        handleExternalTask(snapshot)
    }

    /** 命令五层：按设备回包推进票据（规格第 4 节） */
    private fun syncTicketWithSnapshot(snapshot: DeviceSnapshot) {
        val ticket = _uiState.value.activeTicket ?: return
        if (ticket.phase == CommandPhase.VERIFIED) return

        val task = snapshot.task
        val newPhase = when {
            ticket.phase == CommandPhase.SENDING || ticket.phase == CommandPhase.ACCEPTED -> when {
                task.state == TaskRunState.RUNNING -> CommandPhase.EXECUTING
                task.state == TaskRunState.DONE -> CommandPhase.DEVICE_DONE
                task.state == TaskRunState.FAILED -> CommandPhase.FAILED
                else -> ticket.phase
            }

            ticket.phase == CommandPhase.EXECUTING -> when {
                task.state == TaskRunState.DONE -> CommandPhase.DEVICE_DONE
                task.state == TaskRunState.FAILED -> CommandPhase.FAILED
                task.state == TaskRunState.CANCELED -> CommandPhase.FAILED
                else -> ticket.phase
            }

            else -> ticket.phase
        }

        if (newPhase != ticket.phase) {
            val updated = ticket.copy(
                phase = newPhase,
                doneAt = if (newPhase == CommandPhase.DEVICE_DONE) System.currentTimeMillis() else ticket.doneAt,
                note = when (newPhase) {
                    CommandPhase.DEVICE_DONE -> "设备报告已完成，请人工现场核验"
                    CommandPhase.FAILED -> task.resultNote ?: "设备报告任务未完成"
                    else -> ticket.note
                },
            )
            _uiState.update { it.copy(activeTicket = updated) }

            if (newPhase == CommandPhase.DEVICE_DONE) {
                onTaskDeviceDone(snapshot)
            } else if (newPhase == CommandPhase.FAILED) {
                recordTaskIfNeeded(WorkRecord.RESULT_FAILED, task.resultNote)
                writtenTaskIds.add(task.id ?: return)
            }
        }
    }

    /** 车身实体按钮启动的任务（M06）：APP 只读取来源/进度/结果 */
    private fun handleExternalTask(snapshot: DeviceSnapshot) {
        val task = snapshot.task
        if (task.source != TaskSource.BUTTON || task.id == null) return
        if (_uiState.value.activeTicket != null) return
        if (task.state == TaskRunState.RUNNING) {
            val ticket = CommandTicket(
                id = "ext-${task.id}",
                kind = CommandKind.TASK_START,
                phase = CommandPhase.EXECUTING,
                sentAt = snapshot.receivedAt,
                ackedAt = snapshot.receivedAt,
                note = "来源：车身按钮",
            )
            _uiState.update { it.copy(activeTicket = ticket) }
            activeRecordId = UUID.randomUUID().toString()
            activeRecordStartedAt = snapshot.receivedAt
        }
    }

    /** 设备报告完成 / 用户确认完成（M05/M06）：写记录 + 进入人工核验 */
    private fun onTaskDeviceDone(snapshot: DeviceSnapshot) {
        val taskId = snapshot.task.id ?: "task-${System.currentTimeMillis()}"
        if (writtenTaskIds.contains(taskId)) return
        writtenTaskIds.add(taskId)
        // 用户点击「任务完成」触发的刷新：完成文案按用户确认口径显示
        val userConfirmed = _uiState.value.taskCompletedByUser

        val recordId = activeRecordId ?: UUID.randomUUID().toString()
        activeRecordId = recordId
        if (activeRecordStartedAt == 0L) activeRecordStartedAt = snapshot.receivedAt

        viewModelScope.launch {
            syncUploadRecord(recordId)
            workRecordRepository.append(
                WorkRecord(
                    id = recordId,
                    startedAt = activeRecordStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    kind = snapshot.task.kind ?: TaskKind.FIXED_DISTANCE,
                    source = snapshot.task.source,
                    targetM = snapshot.task.targetM,
                    traveledM = snapshot.task.traveledM,
                    deviceResult = WorkRecord.RESULT_DONE,
                    deviceResultNote = snapshot.task.resultNote,
                    verifyResult = WorkRecord.VERIFY_NOT_DONE,
                    name = activeSpecRef?.name,
                    mode = activeSpecRef?.mode,
                    lane = activeSpecRef?.lane,
                    route = activeSpecRef?.segments?.map {
                        RouteSegment(action = it.action, value = it.value)
                    } ?: emptyList(),
                ),
            )
        }
        _uiState.update {
            it.copy(
                pendingVerify = PendingVerify(
                    recordId = recordId,
                    kind = snapshot.task.kind ?: TaskKind.FIXED_DISTANCE,
                    source = snapshot.task.source,
                    targetM = snapshot.task.targetM,
                    traveledM = snapshot.task.traveledM,
                    finishedAt = System.currentTimeMillis(),
                ),
                dialog = AppDialog.Alert(
                    if (userConfirmed) "任务已完成" else "报告已完成",
                    (if (userConfirmed) {
                        "你已现场确认任务完成"
                    } else {
                        "车端报告已达到目标位置"
                    }) + "（目标 ${snapshot.task.targetM} 米，编码器估算 ${snapshot.task.traveledM} 米）。\n\n" +
                        "请人工现场核对：实际位置、车辆朝向、警示灯光是否正常。\n" +
                        "现场核验结果将与设备完成分开记录。\n\n" +
                        "已自动抓拍任务结束快照（含图片、时间与事件类型），可用于生成发给过往司机的预警信息。",
                ),
            )
        }
    }

    /** 任务异常结束时落记录（失败/取消/失联/结果未知） */
    private fun recordTaskIfNeeded(result: String, note: String?) {
        val snapshot = _uiState.value.snapshot ?: return
        val task = snapshot.task
        if (task.state != TaskRunState.RUNNING) return
        val taskId = task.id ?: return
        if (writtenTaskIds.contains(taskId)) return
        writtenTaskIds.add(taskId)

        val recordId = activeRecordId ?: UUID.randomUUID().toString()
        viewModelScope.launch {
            syncUploadRecord(recordId)
            workRecordRepository.append(
                WorkRecord(
                    id = recordId,
                    startedAt = if (activeRecordStartedAt > 0) activeRecordStartedAt else snapshot.receivedAt,
                    finishedAt = System.currentTimeMillis(),
                    kind = task.kind ?: TaskKind.FIXED_DISTANCE,
                    source = task.source,
                    targetM = task.targetM,
                    traveledM = task.traveledM,
                    deviceResult = result,
                    deviceResultNote = note,
                    verifyResult = WorkRecord.VERIFY_NOT_DONE,
                    // 作业车道：与发布时一致（异常结束时同样留痕，便于记录页/事故核对）
                    lane = activeSpecRef?.lane,
                ),
            )
        }
        activeRecordId = null
        activeRecordStartedAt = 0L
    }

    // ==================== M04 手动遥控 ====================

    fun onHoldStart(dir: MoveDir) {
        val state = _uiState.value
        if (!state.motionCommandsEnabled) {
            rejectMotion("当前状态禁止手动运动：${motionRejectionReason(state)}")
            return
        }
        if (_uiState.value.holdingMove) return
        _uiState.update { it.copy(holdingMove = true, holdingDir = dir) }

        // 立即发一条，随后持续发保持包：设备侧有断流看门狗，只发一条会被判失联自动停车
        sendCommand(CommandKind.MOVE, trackTicket = false) { deviceRepository.sendMove(dir) }
        keepaliveJob = viewModelScope.launch {
            while (isActive && _uiState.value.holdingMove) {
                delay(MOVE_KEEPALIVE_MS)
                if (_uiState.value.holdingMove) {
                    deviceRepository.sendMove(dir)   // 保持包：失败也不打断按住
                }
            }
        }
    }

    /** 松手/触控取消/退出页面/失联 → 停车（M04） */
    fun onHoldEnd() {
        if (!_uiState.value.holdingMove) return
        keepaliveJob?.cancel()
        keepaliveJob = null
        _uiState.update { it.copy(holdingMove = false, holdingDir = null) }
        // 只结束手动微调：正在执行的自动任务保持，下一拍继续
        sendCommand(CommandKind.MANUAL_STOP, trackTicket = false) { deviceRepository.sendManualStop() }
        refreshStatus() // 松手后读一次位置，让地图反映本次微调结果
    }

    /** 离开手动遥控页（返回/切后台）→ 结束手动微调（不取消任务） */
    fun onLeaveManual() {
        if (_uiState.value.holdingMove) onHoldEnd()
    }

    /** 手动微调停车（M04）：停手动运动，不取消任务 */
    fun stopNormal() {
        sendCommand(CommandKind.MANUAL_STOP) { deviceRepository.sendManualStop() }
        _uiState.update { it.copy(holdingMove = false, holdingDir = null) }
        refreshStatus()
    }

    // ==================== M08 急停与恢复 ====================

    /** 急停（M08）：最高优先、无二次确认、立即下发 */
    fun estop() {
        val pending = _uiState.value.activeTicket
        if (pending != null && pending.phase != CommandPhase.VERIFIED) {
            _uiState.update {
                it.copy(activeTicket = pending.copy(phase = CommandPhase.UNKNOWN, note = "急停已触发，原任务结果未知"))
            }
        }
        _uiState.update { it.copy(stopUnconfirmed = true, holdingMove = false, holdingDir = null) }
        sendCommand(CommandKind.ESTOP, trackTicket = false) { deviceRepository.sendEstop() }
        recordTaskIfNeeded(WorkRecord.RESULT_CANCELED, "急停锁定（estop）")
    }

    /** 人工恢复至待命（M08）：不自动续跑 */
    fun resumeFromEstop() {
        sendCommand(CommandKind.RESUME, trackTicket = false) { deviceRepository.sendResume() }
    }

    /** 请求恢复待命（M08）：弹出人工现场核查确认，确认后才下发恢复 */
    fun requestResume() {
        _uiState.update { it.copy(pendingResumeConfirm = true) }
    }

    fun cancelResumeConfirm() {
        _uiState.update { it.copy(pendingResumeConfirm = false) }
    }

    fun confirmResume() {
        _uiState.update { it.copy(pendingResumeConfirm = false) }
        resumeFromEstop()
    }

    // ==================== M05 定距布置 ====================

    // ==================== 任务发布草稿 ====================

    /** 重置草稿为某类型默认值（进入发布表单时调用） */
    fun initDraft(kind: TaskKind) {
        _uiState.update { it.copy(draft = TaskSpec.defaultFor(kind)) }
    }

    /** 更新草稿（表单任意字段） */
    fun updateDraft(transform: (TaskSpec) -> TaskSpec) {
        _uiState.update { it.copy(draft = transform(it.draft)) }
    }

    /** 切换预检勾选项（草稿内） */
    fun togglePrecheck(item: (PrecheckHuman) -> PrecheckHuman) {
        _uiState.update { it.copy(draft = it.draft.copy(precheck = item(it.draft.precheck))) }
    }

    private fun startTask(kind: TaskKind, targetM: Double) {
        val state = _uiState.value
        if (!state.taskStartEnabled) {
            rejectMotion(
                when {
                    state.connection != ConnectionPhase.CONNECTED -> "设备未连接，无法下发任务"
                    state.snapshot?.estopLocked == true -> "急停锁定中，请先现场核查后恢复"
                    state.snapshot?.task?.state == TaskRunState.RUNNING -> "已有任务运行中，禁止重复启动"
                    state.activeTicket != null -> "上一条命令尚未确认，禁止重复启动"
                    else -> "当前状态不允许启动任务"
                },
            )
            return
        }
        _uiState.update { it.copy(taskCompletedByUser = false) }
        val recordId = UUID.randomUUID().toString()
        activeRecordId = recordId
        activeRecordStartedAt = System.currentTimeMillis()
        sendCommand(
            kind = CommandKind.TASK_START,
            trackTicket = true,
        ) { deviceRepository.startTask(kind, targetM) }
    }

    /**
     * 自动回收：小车返回作业人员位置（起点=车、终点=人），
     * 回收完成后即可直接发起下一次定距布置。
     */
    fun startRecoverTask() {
        val state = _uiState.value
        if (!state.taskStartEnabled) {
            rejectMotion(
                when {
                    state.connection != ConnectionPhase.CONNECTED -> "设备未连接，无法下发回收"
                    state.snapshot?.estopLocked == true -> "急停锁定中，请先现场核查后恢复"
                    state.snapshot?.task?.state == TaskRunState.RUNNING -> "已有任务运行中"
                    else -> "当前状态不允许回收"
                },
            )
            return
        }
        _uiState.update { it.copy(taskCompletedByUser = false) }
        val recordId = UUID.randomUUID().toString()
        activeRecordId = recordId
        activeRecordStartedAt = System.currentTimeMillis()
        sendCommand(CommandKind.TASK_START, trackTicket = true) { deviceRepository.startRecover() }
    }

    /**
     * **发布任务**：校验 -> 生成默认任务名 -> 整条路线下发 -> 立即进入执行。
     * 发布即代表任务已开始（执行页无启动按钮）。
     */
    fun publishDraft() {
        val draft = _uiState.value.draft
        val errors = draft.validate()
        if (errors.isNotEmpty()) {
            _uiState.update {
                it.copy(dialog = AppDialog.Reject("任务参数校验未通过", errors, "请修正路线设计后重新发布"))
            }
            return
        }
        val state = _uiState.value
        if (!state.taskStartEnabled || state.connection != ConnectionPhase.CONNECTED) {
            rejectMotion(
                when {
                    state.connection != ConnectionPhase.CONNECTED -> "设备未连接，无法发布任务"
                    state.snapshot?.estopLocked == true -> "急停锁定中，请先现场核查后恢复"
                    state.snapshot?.task?.state == TaskRunState.RUNNING -> "已有任务运行中，禁止重复发布"
                    else -> "当前状态不允许发布任务"
                },
            )
            return
        }
        val spec = draft.copy(
            // 留空自动生成任务名（「自动布置」），避免出现「1014-0930」这类只有数字、含义不明的标题
            name = draft.name.ifBlank { defaultTaskName(draft) },
        )
        _uiState.update { it.copy(draft = spec, activeSpec = spec, taskCompletedByUser = false) }

        activeRecordId = UUID.randomUUID().toString()
        activeRecordStartedAt = System.currentTimeMillis()
        activeSpecRef = spec

        sendCommand(CommandKind.TASK_START, trackTicket = true) {
            deviceRepository.startRoute(spec.kind, spec.mode, spec.segments, spec.lightOnRun, spec.lane)
        }
    }

    /** 默认任务名（留空自动生成）：「自动布置」 */
    private fun defaultTaskName(spec: TaskSpec): String =
        if (spec.kind == TaskKind.FIXED_DISTANCE) "自动布置" else spec.kind.label

    /** 发布失败后「重新下发」：用同一份规格再发一次（走 taskStartEnabled 门） */
    fun retryPublish() {
        val spec = _uiState.value.activeSpec ?: return
        if (!_uiState.value.taskStartEnabled) {
            rejectMotion("当前状态不允许重新下发")
            return
        }
        _uiState.update { it.copy(taskCompletedByUser = false) }
        activeRecordId = UUID.randomUUID().toString()
        activeRecordStartedAt = System.currentTimeMillis()
        sendCommand(CommandKind.TASK_START, trackTicket = true) {
            deviceRepository.startRoute(spec.kind, spec.mode, spec.segments, spec.lightOnRun, spec.lane)
        }
    }

    /** 分段确认模式：确认执行当前段 */
    fun confirmCurrentSegment() {
        sendCommand(CommandKind.SEGMENT_CONFIRM, trackTicket = false) { deviceRepository.confirmSegment() }
    }

    /** 手动提交上传（核验异常、上传失败重试时使用；核验通过走自动上传） */
    fun uploadActiveRecord() {
        val id = activeRecordId
            ?: _uiState.value.pendingVerify?.recordId
            ?: _uiState.value.uploadRecord?.id
            ?: return
        performUpload(id, auto = false)
    }

    /**
     * 执行上传：`UPLOAD_MODE=HTTP`（TRANSPORT=SERVER）时 POST 服务端 `/api/v1/tasks`，
     * STUB 时仅占位模拟。成功后服务端单号写回本机记录（记录页可见）。
     * [auto] = true 表示核验通过后的自动上传，完成提示与手动提交区分文案。
     */
    private fun performUpload(id: String, auto: Boolean) {
        viewModelScope.launch {
            workRecordRepository.setUploadState(id, WorkRecord.UPLOAD_PENDING)
            syncUploadRecord(id)
            val record = workRecordRepository.records.first().firstOrNull { it.id == id }
            if (record == null) {
                _uiState.update {
                    it.copy(dialog = AppDialog.Warn("上传未执行", "未找到对应作业记录，请稍后在记录页重试。"))
                }
                return@launch
            }
            val serverUpload = ServiceLocator.UPLOAD_MODE == UploadMode.HTTP
            val result = taskUploadRepository.upload(record, _uiState.value.selectedDeviceCode)
            result.onSuccess { r ->
                workRecordRepository.setUploaded(id, r.serverId, r.uploadedAt)
                syncUploadRecord(id)
                val userConfirmed = _uiState.value.taskCompletedByUser
                _uiState.update {
                    it.copy(
                        dialog = AppDialog.Alert(
                            when {
                                auto && userConfirmed && serverUpload -> "任务完成 · 已上传"
                                auto && userConfirmed -> "任务完成 · 已模拟上传"
                                auto && serverUpload -> "核验通过 · 已自动上传"
                                auto -> "核验通过 · 已模拟上传"
                                serverUpload -> "上传成功"
                                else -> "已模拟上传"
                            },
                            buildString {
                                if (auto) {
                                    append(if (userConfirmed) "用户确认任务完成。\n" else "人工现场核验：通过。\n")
                                }
                                if (serverUpload) {
                                    append("任务记录已保存本机，并上传服务端（POST /api/v1/tasks）。\n\n")
                                    append("服务端单号：").append(r.serverId)
                                    append("\n上传时间：").append(TimeFormat.format("HH:mm:ss", r.uploadedAt))
                                } else {
                                    append("任务记录已保存本机，并完成模拟上传（演示占位）。\n\n")
                                    append("占位单号：").append(r.serverId)
                                    append("\n未接入真实服务器，不构成正式上传。")
                                }
                            },
                        ),
                    )
                }
            }.onFailure { e ->
                workRecordRepository.setUploadState(id, WorkRecord.UPLOAD_FAILED)
                syncUploadRecord(id)
                _uiState.update {
                    it.copy(
                        dialog = AppDialog.Warn(
                            "上传失败",
                            (e.message ?: "未知原因") + "\n记录已保存本机，可稍后重试。",
                        ),
                    )
                }
            }
        }
    }

    /** 取消任务（M05/M06） */
    fun cancelTask() {
        sendCommand(CommandKind.TASK_CANCEL, trackTicket = false) { deviceRepository.cancelTask() }
        recordTaskIfNeeded(WorkRecord.RESULT_CANCELED, "用户取消任务")
    }

    /**
     * 用户现场确认任务完成：任务进度只有「未完成 / 已完成」，已完成以本操作为准。
     *
     * 下发 `task_complete` 成功后立即标记本地已完成，落一条作业记录并**直接上传**，
     * 同时主动读一次状态用于收尾（用户操作触发，不是定时轮询）。
     */
    fun completeTask() {
        val state = _uiState.value
        if (state.taskCompletedByUser) return
        if (state.connection != ConnectionPhase.CONNECTED) {
            rejectMotion("设备未连接，无法确认任务完成")
            return
        }
        sendCommand(
            kind = CommandKind.TASK_COMPLETE,
            trackTicket = false,
            onAccepted = {
                _uiState.update { it.copy(taskCompletedByUser = true) }
                // 防止随后状态刷新把同一任务再落一条记录
                _uiState.value.snapshot?.task?.id?.let { writtenTaskIds.add(it) }
                finalizeUserCompletedTask()
                refreshStatus()
            },
        ) { deviceRepository.completeTask() }
    }

    /**
     * 用户确认完成后的收尾：落一条「已完成」记录（设备已自动完成过则复用）并**直接上传**。
     * 用户点击「任务完成」即视为完成确认，不再要求二次「核验通过」。
     */
    private fun finalizeUserCompletedTask() {
        viewModelScope.launch {
            val snapshot = _uiState.value.snapshot ?: return@launch
            val recordId = _uiState.value.pendingVerify?.recordId
                ?: activeRecordId
                ?: UUID.randomUUID().toString()
            activeRecordId = recordId
            if (activeRecordStartedAt == 0L) activeRecordStartedAt = snapshot.receivedAt

            val existing = workRecordRepository.records.first().firstOrNull { it.id == recordId }
            if (existing == null) {
                workRecordRepository.append(
                    WorkRecord(
                        id = recordId,
                        startedAt = activeRecordStartedAt,
                        finishedAt = System.currentTimeMillis(),
                        kind = snapshot.task.kind ?: TaskKind.FIXED_DISTANCE,
                        source = snapshot.task.source,
                        targetM = snapshot.task.targetM,
                        traveledM = snapshot.task.traveledM,
                        deviceResult = WorkRecord.RESULT_DONE,
                        deviceResultNote = "user_confirmed",
                        verifyResult = WorkRecord.VERIFY_PASS,
                        verifyNote = "用户确认任务完成",
                        name = activeSpecRef?.name,
                        mode = activeSpecRef?.mode,
                        lane = activeSpecRef?.lane,
                        route = activeSpecRef?.segments?.map {
                            RouteSegment(action = it.action, value = it.value)
                        } ?: emptyList(),
                    ),
                )
            } else {
                workRecordRepository.updateVerify(recordId, WorkRecord.VERIFY_PASS, "用户确认任务完成")
            }
            _uiState.update { it.copy(pendingVerify = null) }
            syncUploadRecord(recordId)
            // 已经上传过的记录不重复上传
            if (existing?.uploadState != WorkRecord.UPLOAD_UPLOADED) {
                performUpload(recordId, auto = true)
            }
        }
    }

    /** 一键布置阶段（M06）：以设备回报为准 */
    fun oneTouchStage(): OneTouchStage? = when (_uiState.value.snapshot?.task?.stage) {
        "lighting" -> OneTouchStage.LIGHTING
        "countdown" -> OneTouchStage.COUNTDOWN
        "moving" -> OneTouchStage.MOVING
        "parked" -> OneTouchStage.PARKED
        else -> if (_uiState.value.snapshot?.task?.state == TaskRunState.DONE) OneTouchStage.WAITING_VERIFY else null
    }

    // ==================== M07 警示灯 ====================

    fun setLight(mode: LightMode) {
        // 灯光与运动分离：关灯不解除急停（M07）
        _uiState.update { it.copy(lightSetting = mode) }
        viewModelScope.launch {
            val result = deviceRepository.setLight(mode)
            result.onFailure { e ->
                _uiState.update {
                    it.copy(
                        dialog = AppDialog.Warn(
                            "灯光设置失败",
                            "与设备通信失败，灯光模式保持不变。\n${e.message ?: "未知原因"}",
                        ),
                    )
                }
            }.onSuccess { ack ->
                if (!ack.accepted) {
                    _uiState.update {
                        it.copy(
                            dialog = AppDialog.Reject(
                                "灯光设置被拒绝",
                                listOf(ack.reason ?: "设备拒绝该指令"),
                                "请稍后重试",
                            ),
                        )
                    }
                }
            }
        }
    }

    // ==================== M09 运行数据 ====================

    /** 计数清零（M09）：自动运动中禁止 */
    fun resetOdo() {
        val state = _uiState.value
        if (state.snapshot?.task?.state == TaskRunState.RUNNING || state.snapshot?.motion == MotionState.MOVING) {
            rejectMotion("自动运动中禁止计数清零")
            return
        }
        sendCommand(CommandKind.ODO_RESET, trackTicket = false) { deviceRepository.resetOdo() }
    }

    // ==================== 人工核验（M05/M06） ====================

    fun confirmVerify(pass: Boolean, note: String?) {
        val pending = _uiState.value.pendingVerify ?: return
        val ticket = _uiState.value.activeTicket
        _uiState.update {
            it.copy(
                activeTicket = ticket?.copy(phase = CommandPhase.VERIFIED, note = "人工核验：${if (pass) "通过" else "异常"}"),
                pendingVerify = null,
                // 核验通过 → 直接自动上传（结果由上传完成/失败弹窗提示，无需手动提交）；
                // 核验异常 → 保留手动提交入口，此处仅提示已记录
                dialog = if (pass) null else AppDialog.Alert(
                    "核验已记录",
                    "人工现场核验：发现异常。\n已记入作业记录，请现场处置后再继续作业。",
                ),
            )
        }
        viewModelScope.launch {
            syncUploadRecord(pending.recordId)
            workRecordRepository.updateVerify(
                id = pending.recordId,
                verifyResult = if (pass) WorkRecord.VERIFY_PASS else WorkRecord.VERIFY_FAIL,
                verifyNote = note,
            )
            if (pass) {
                // 先落核验结果再上传，保证上传载荷里带「核验通过」
                performUpload(pending.recordId, auto = true)
            } else {
                syncUploadRecord(pending.recordId)
            }
        }
    }

    // ==================== 通用 ====================

    /** 刷新执行页展示用的作业记录（含上传状态） */
    private suspend fun syncUploadRecord(id: String) {
        val r = workRecordRepository.records.first().firstOrNull { it.id == id }
        _uiState.update { it.copy(uploadRecord = r) }
    }

    /**
     * 抓拍快照（小车运行中，**人工手动**）。
     * 快照含图片 + 时间 + 事件类型，用于后续发给过往司机的预警信息。
     */
    fun captureSnapshot(eventLabel: String) {
        val uploaded = snapshotUploadRepository != null
        saveSnapshot(eventLabel, "人工抓拍（演示占位快照）")
        _uiState.update {
            it.copy(
                dialog = AppDialog.Alert(
                    "快照已保存",
                    buildString {
                        append("已抓拍一张快照：保存本机")
                        if (uploaded) append("，并上传服务端（将作为「作业人员上报」在风险页展示）")
                        append("。\n事件类型：")
                        append(eventLabel)
                        append("。")
                    },
                ),
            )
        }
    }

    /**
     * 任务自动快照：**任务开始自动拍一张、任务结束自动拍一张**（以设备回报的任务状态迁移为准），
     * 且**每次自动抓拍都弹窗一次**（任务开始 / 任务结束各一次）。
     * 中间过程由人工自行决定拍不拍（[captureSnapshot]）。
     *
     * 注意：任务正常完成（DONE）时「结束快照」提示并入「报告已完成」弹窗（见 [onTaskDeviceDone]），
     * 失联触发的结束快照并入「设备已断开」弹窗（见 [onLinkLost]）——同一时刻仅有一个弹窗位，
     * 避免安全提示被覆盖。
     */
    private fun trackTaskAutoSnapshot(snapshot: DeviceSnapshot) {
        val task = snapshot.task
        val prev = lastTaskRunState
        lastTaskRunState = task.state
        if (prev == null) return
        when {
            task.state == TaskRunState.RUNNING && prev != TaskRunState.RUNNING -> {
                saveSnapshot("任务开始（自动抓拍）", "任务启动自动抓拍")
                notifyAutoSnapshot("任务开始", "任务执行中可随时用「捕捉快照」手动补拍。")
            }

            prev == TaskRunState.RUNNING && task.state != TaskRunState.RUNNING -> {
                saveSnapshot("任务结束（自动抓拍）", "任务${task.state.label}自动抓拍")
                if (task.state != TaskRunState.DONE) {
                    notifyAutoSnapshot("任务结束", "任务最终状态：${task.state.label}。")
                }
            }
        }
    }

    /** 自动抓拍后的弹窗提示（任务开始/结束各弹一次；同一时刻仅一个弹窗位，见 [trackTaskAutoSnapshot]） */
    private fun notifyAutoSnapshot(phase: String, extra: String) {
        _uiState.update {
            it.copy(
                dialog = AppDialog.Alert(
                    "$phase · 已自动抓拍快照",
                    buildString {
                        append("系统已自动抓拍一张快照（$phase）。\n\n")
                        append("快照包含图片、抓拍时间与事件类型，可用于生成发给过往司机的预警信息。")
                        if (extra.isNotBlank()) {
                            append("\n")
                            append(extra)
                        }
                    },
                ),
            )
        }
    }

    /** 落一张快照到本机，并上传服务端（自动/手动共用；服务端登记为风险事件，风险页可见） */
    private fun saveSnapshot(eventLabel: String, note: String) {
        viewModelScope.launch {
            val spec = _uiState.value.activeSpec
            val snapshot = com.example.myapplication.core.model.media.TaskSnapshot(
                capturedAt = System.currentTimeMillis(),
                taskName = spec?.name ?: "布置任务",
                lane = spec?.lane,         // 作业车道：服务端登记风险事件时带上
                eventLabel = eventLabel,
                imageUri = null,           // 当前无摄像头，占位；接摄像头后存实际图片
                note = note,
            )
            snapshotRepository.capture(snapshot)
            // 统一约定：快照上传到服务端 → 服务端登记为风险事件（风险页展示「作业人员上报」）。
            // 失败不阻塞主流程（本机已保存）；重试标记/队列为后续增强。
            snapshotUploadRepository?.upload(snapshot, _uiState.value.selectedDeviceCode)
        }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialog = null) }
    }

    fun showInfo(title: String, message: String) {
        _uiState.update { it.copy(dialog = AppDialog.Alert(title, message)) }
    }

    private fun rejectMotion(reason: String) {
        _uiState.update { it.copy(dialog = AppDialog.Reject("无法执行", listOf(reason), "请确认设备状态与现场情况后重试")) }
    }

    private fun motionRejectionReason(state: VehicleUiState): String = when {
        state.connection != ConnectionPhase.CONNECTED -> "设备未连接或状态未确认"
        state.snapshot?.estopLocked == true -> "急停锁定中"
        state.snapshot?.mode == VehicleMode.SAFE_STOP -> "安全停止中"
        else -> "当前状态不允许"
    }

    /**
     * 发送命令并维护命令五层票据（规格第 4 节）。
     * 超时/失败一律标"结果未知"，**不自动重发**。
     */
    private fun sendCommand(
        kind: CommandKind,
        trackTicket: Boolean = false,
        onAccepted: (() -> Unit)? = null,
        block: suspend () -> Result<CmdAck>,
    ) {
        val now = System.currentTimeMillis()
        val ticket = CommandTicket(
            id = "cmd-${cmdSeq++}",
            kind = kind,
            phase = CommandPhase.SENDING,
            sentAt = now,
        )
        if (trackTicket) {
            _uiState.update { it.copy(activeTicket = ticket) }
        }

        viewModelScope.launch {
            val result = block()
            result.onSuccess { ack ->
                if (ack.accepted) {
                    onAccepted?.invoke()
                    if (trackTicket) {
                        val current = _uiState.value.activeTicket
                        if (current?.id == ticket.id) {
                            _uiState.update {
                                it.copy(activeTicket = current.copy(phase = CommandPhase.ACCEPTED, ackedAt = System.currentTimeMillis()))
                            }
                        }
                    }
                } else {
                    val reason = ack.reason ?: "设备拒绝该指令"
                    if (trackTicket) {
                        val current = _uiState.value.activeTicket
                        if (current?.id == ticket.id) {
                            _uiState.update {
                                it.copy(activeTicket = current.copy(phase = CommandPhase.FAILED, note = reason))
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(dialog = AppDialog.Reject("${kind.label}被设备拒绝", listOf(reason), "请按提示排查后重试"))
                    }
                }
            }.onFailure { e ->
                val message = e.message ?: "通信失败"
                if (trackTicket) {
                    val current = _uiState.value.activeTicket
                    if (current?.id == ticket.id) {
                        _uiState.update {
                            it.copy(
                                activeTicket = current.copy(
                                    phase = CommandPhase.UNKNOWN,
                                    note = "结果未知：$message。请查询设备状态后现场确认，系统不会自动重发。",
                                ),
                            )
                        }
                    }
                }
                if (kind != CommandKind.MOVE && kind != CommandKind.STOP) {
                    _uiState.update {
                        it.copy(
                            dialog = AppDialog.Warn(
                                "${kind.label}：结果未知",
                                "$message\n\n命令可能已到达设备。请先查询设备状态并现场确认，**系统不会自动重复发送运动命令**。",
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectJob?.cancel()
        refreshJob?.cancel()
        stopStatusPolling()
    }

    companion object {
        /** 实时状态轮询间隔（任务执行页/地图页可见时） */
        private const val STATUS_POLL_INTERVAL_MS = 1000L
        /** 按住移动时的保持包间隔（须短于设备看门狗 800ms） */
        private const val MOVE_KEEPALIVE_MS = 300L
        /** 添加设备编号最大长度 */
        private const val MAX_DEVICE_CODE_LEN = 32

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyAppApplication
                val sl = app.serviceLocator
                VehicleViewModel(
                    sl.deviceRepository,
                    sl.workRecordRepository,
                    sl.settingsRepository,
                    sl.taskUploadRepository,
                    sl.snapshotRepository,
                    sl.snapshotUploadRepository,
                    sl.deviceRegistryRepository,
                    sl.authRepository,
                )
            }
        }
    }
}
