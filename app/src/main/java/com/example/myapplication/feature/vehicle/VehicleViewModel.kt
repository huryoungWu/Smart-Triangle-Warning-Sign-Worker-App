package com.example.myapplication.feature.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.MyAppApplication
import com.example.myapplication.core.data.device.DeviceRepository
import com.example.myapplication.core.data.prefs.SettingsRepository
import com.example.myapplication.core.data.record.WorkRecordRepository
import com.example.myapplication.core.model.AppDialog
import com.example.myapplication.core.model.vehicle.CommandKind
import com.example.myapplication.core.model.vehicle.CommandPhase
import com.example.myapplication.core.model.vehicle.CommandTicket
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.DeviceSnapshot
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
    private val taskUploadRepository: com.example.myapplication.core.data.upload.TaskUploadRepository,
    private val snapshotRepository: com.example.myapplication.core.data.media.SnapshotRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VehicleUiState())
    val uiState: StateFlow<VehicleUiState> = _uiState.asStateFlow()

    val records: Flow<List<WorkRecord>> = workRecordRepository.records

    /** 已抓拍的任务快照 */
    val snapshots: Flow<List<com.example.myapplication.core.model.media.TaskSnapshot>> =
        snapshotRepository.snapshots

    private var pollJob: Job? = null
    private var keepaliveJob: Job? = null
    private var cmdSeq = 1

    /** 进行中任务的本地记录 id（任务结束时落库） */
    private var activeRecordId: String? = null
    private var activeRecordStartedAt = 0L
    private val writtenTaskIds = mutableSetOf<String>()
    private var activeSpecRef: TaskSpec? = null

    init {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    draft = TaskSpec.defaultFixed(),
                    lightSetting = settingsRepository.defaultLight.first(),
                )
            }
            // 加载可选设备列表，默认选第一台
            val devices = deviceRepository.listDevices()
            _uiState.update {
                it.copy(
                    deviceList = devices,
                    selectedDeviceId = devices.firstOrNull()?.id ?: "",
                )
            }
        }
    }

    /** 选择要连接的设备（连接页下拉） */
    fun selectDevice(deviceId: String) {
        if (_uiState.value.connection == ConnectionPhase.CONNECTED) return
        _uiState.update { it.copy(selectedDeviceId = deviceId) }
    }

    // ==================== M01 设备连接 ====================

    /** 连接设备：只有收到有效状态才显示"已连接"（M01） */
    fun connect() {
        if (pollJob?.isActive == true) return
        // 把所选设备 id 下发到设备层（Fake 会据此回对应设备信息）
        (deviceRepository as? com.example.myapplication.core.data.device.FakeDeviceRepository)?.let { fake ->
            fake.selectedDeviceId = _uiState.value.selectedDeviceId
        }
        _uiState.update { it.copy(connection = ConnectionPhase.CONNECTING) }
        pollJob = viewModelScope.launch {
            while (isActive) {
                val result = deviceRepository.fetchStatus()
                result.onSuccess { snapshot ->
                    onSnapshot(snapshot)
                }.onFailure {
                    onLinkLost()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 手动断开连接（M01）：停止轮询与保持包，清空设备状态回未连接 */
    fun disconnect() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        pollJob?.cancel()
        pollJob = null
        _uiState.update {
            it.copy(
                connection = ConnectionPhase.DISCONNECTED,
                snapshot = null,
                lastUpdateAt = 0L,
                holdingMove = false,
                holdingDir = null,
                activeTicket = null,
                stopUnconfirmed = false,
            )
        }
    }

    /** 失联立即显示"已断开／状态未确认"并禁用运动操作（M01） */
    private fun onLinkLost() {
        val wasConnected = _uiState.value.connection == ConnectionPhase.CONNECTED
        if (_uiState.value.holdingMove) {
            // 失联：清"按住中"，尝试停车（命令标 UNKNOWN，不排队不重发）
            _uiState.update { it.copy(holdingMove = false, holdingDir = null) }
        }
        // 失联时清空未确认命令票据（离线命令禁排队）
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
                            "重连后仅重新读取设备状态，不会重发断线前的移动、布置或恢复命令。",
                    )
                } else it.dialog,
            )
        }
        // 若断线时有任务在跑，落一条"失联保护"记录
        recordTaskIfNeeded(WorkRecord.RESULT_LINK_LOSS, "失联保护停车（车端断网停车）")
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

    /** 设备报告完成（M05/M06）：写记录 + 进入人工核验 */
    private fun onTaskDeviceDone(snapshot: DeviceSnapshot) {
        val taskId = snapshot.task.id ?: "task-${System.currentTimeMillis()}"
        if (writtenTaskIds.contains(taskId)) return
        writtenTaskIds.add(taskId)

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
                    "报告已完成",
                    "车端报告已达到目标位置（目标 ${snapshot.task.targetM} 米，编码器估算 ${snapshot.task.traveledM} 米）。\n\n" +
                        "请人工现场核对：实际位置、车辆朝向、警示灯光是否正常。\n" +
                        "现场核验结果将与设备完成分开记录。",
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
        if (state.snapshot?.mode == VehicleMode.AUTO) {
            rejectMotion("自动任务执行中，禁止手动抢占")
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
        sendCommand(CommandKind.STOP, trackTicket = false) { deviceRepository.sendStop() }
    }

    /** 离开手动遥控页（返回/切后台）→ 强制停车（三重保障之一） */
    fun onLeaveManual() {
        if (_uiState.value.holdingMove) onHoldEnd()
    }

    /** 普通停车（M04）：停运动 + 取消自动任务，不锁定、不自动关灯 */
    fun stopNormal() {
        sendCommand(CommandKind.STOP) { deviceRepository.sendStop() }
        _uiState.update { it.copy(holdingMove = false, holdingDir = null) }
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
            name = draft.name.ifBlank {
                com.example.myapplication.core.util.TimeFormat.format("MMdd-HHmm", System.currentTimeMillis())
            },
        )
        _uiState.update { it.copy(draft = spec, activeSpec = spec) }

        activeRecordId = UUID.randomUUID().toString()
        activeRecordStartedAt = System.currentTimeMillis()
        activeSpecRef = spec

        sendCommand(CommandKind.TASK_START, trackTicket = true) {
            deviceRepository.startRoute(spec.kind, spec.mode, spec.segments, spec.lightOnRun)
        }
    }

    /** 发布失败后「重新下发」：用同一份规格再发一次（走 taskStartEnabled 门） */
    fun retryPublish() {
        val spec = _uiState.value.activeSpec ?: return
        if (!_uiState.value.taskStartEnabled) {
            rejectMotion("当前状态不允许重新下发")
            return
        }
        activeRecordId = UUID.randomUUID().toString()
        activeRecordStartedAt = System.currentTimeMillis()
        sendCommand(CommandKind.TASK_START, trackTicket = true) {
            deviceRepository.startRoute(spec.kind, spec.mode, spec.segments, spec.lightOnRun)
        }
    }

    /** 分段确认模式：确认执行当前段 */
    fun confirmCurrentSegment() {
        sendCommand(CommandKind.SEGMENT_CONFIRM, trackTicket = false) { deviceRepository.confirmSegment() }
    }

    /** 触发任务上传（服务器尚未接入 -> 占位实现） */
    fun uploadActiveRecord() {
        val id = activeRecordId ?: _uiState.value.pendingVerify?.recordId ?: return
        viewModelScope.launch {
            workRecordRepository.setUploadState(id, WorkRecord.UPLOAD_PENDING)
            syncUploadRecord(id)
            val record = workRecordRepository.records.first().firstOrNull { it.id == id } ?: return@launch
            val result = taskUploadRepository.upload(record)
            result.onSuccess { r ->
                workRecordRepository.setUploaded(id, r.serverId, r.uploadedAt)
                syncUploadRecord(id)
                _uiState.update {
                    it.copy(
                        dialog = AppDialog.Alert(
                            "已模拟上传",
                            "任务记录已保存本机，并完成模拟上传（演示占位）。\n\n" +
                                "占位单号：" + r.serverId + "\n未接入真实服务器，不构成正式上传。",
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
        viewModelScope.launch {
            syncUploadRecord(pending.recordId)
            workRecordRepository.updateVerify(
                id = pending.recordId,
                verifyResult = if (pass) WorkRecord.VERIFY_PASS else WorkRecord.VERIFY_FAIL,
                verifyNote = note,
            )
        }
        val ticket = _uiState.value.activeTicket
        if (ticket != null) {
            _uiState.update {
                it.copy(
                    activeTicket = ticket.copy(phase = CommandPhase.VERIFIED, note = "人工核验：${if (pass) "通过" else "异常"}"),
                    pendingVerify = null,
                    dialog = AppDialog.Alert(
                        "核验已记录",
                        if (pass) {
                            "人工现场核验：通过。\n设备完成与人工核验已分别记入作业记录（${WorkRecord.LOCAL_ONLY_NOTICE}）。"
                        } else {
                            "人工现场核验：发现异常。\n已记入作业记录，请现场处置后再继续作业。"
                        },
                    ),
                )
            }
        } else {
            _uiState.update {
                it.copy(pendingVerify = null, dialog = AppDialog.Alert("核验已记录", "核验结果已记入作业记录（${WorkRecord.LOCAL_ONLY_NOTICE}）。"))
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
     * 抓拍快照（小车运行中）。
     * 快照含图片 + 时间 + 事件类型，用于后续发给过往司机的预警信息。
     */
    fun captureSnapshot(eventLabel: String) {
        viewModelScope.launch {
            val spec = _uiState.value.activeSpec
            val snap = com.example.myapplication.core.model.media.TaskSnapshot(
                capturedAt = System.currentTimeMillis(),
                taskName = spec?.name ?: "布置任务",
                eventLabel = eventLabel,
                imageUri = null,           // 当前无摄像头，占位；接摄像头后存实际图片
                note = "演示占位快照",
            )
            snapshotRepository.capture(snap)
            _uiState.update {
                it.copy(
                    dialog = AppDialog.Alert(
                        "快照已保存",
                        "已抓拍一张快照并保存本机。事件类型：" + eventLabel +
                            "。该快照可与时间、事件类型一同用于预警信息；预警上传接口已预留，暂未启用。",
                    ),
                )
            }
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
        state.snapshot?.mode == VehicleMode.AUTO -> "自动任务执行中"
        else -> "当前状态不允许"
    }

    /**
     * 发送命令并维护命令五层票据（规格第 4 节）。
     * 超时/失败一律标"结果未知"，**不自动重发**。
     */
    private fun sendCommand(
        kind: CommandKind,
        trackTicket: Boolean = false,
        block: suspend () -> Result<com.example.myapplication.core.data.device.CmdAck>,
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
        pollJob?.cancel()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
        /** 按住移动时的保持包间隔（须短于设备看门狗 800ms） */
        private const val MOVE_KEEPALIVE_MS = 300L

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
                )
            }
        }
    }
}
