package com.example.myapplication.core.data.device

import com.example.myapplication.core.data.api.CmdAck
import com.example.myapplication.core.data.api.DeviceConnectRequest
import com.example.myapplication.core.data.api.DeviceRepository
import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.DeviceSummary
import com.example.myapplication.core.model.vehicle.GpsFixState
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MotionState
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.core.model.vehicle.OdoInfo
import com.example.myapplication.core.model.vehicle.RouteSegment
import com.example.myapplication.core.model.vehicle.SegmentAction
import com.example.myapplication.core.model.vehicle.SegmentRunState
import com.example.myapplication.core.model.vehicle.SegmentState
import com.example.myapplication.core.model.vehicle.StopSource
import com.example.myapplication.core.model.vehicle.TaskInfo
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskMode
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.model.vehicle.TaskSource
import com.example.myapplication.core.model.vehicle.VehicleGps
import com.example.myapplication.core.model.vehicle.VehicleMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import kotlin.math.abs

/** Fake 设备异常注入（供无真机验证规格异常分支） */
sealed interface FakeFault {
    data object None : FakeFault
    /** 失联：fetchStatus 抛 IO */
    data object LinkLoss : FakeFault
    /** 慢应答：命令迟迟无回包 → 超时/结果未知 */
    data class SlowAck(val delayMs: Long) : FakeFault
    /** 命令被接受但状态不跟进执行 → 结果未知 */
    data object ResultUnknown : FakeFault
    /** 左右轮脉冲偏差偏大 */
    data class OdoDeviation(val extraPulse: Int) : FakeFault
    /** 命令被设备拒绝 */
    data object CommandRejected : FakeFault
}

/**
 * 模拟三角警示牌小车（无真机时跑通全流程）。
 *
 * **路线分段状态机**：整条路线一次性下发，车端逐段执行：
 * - FWD/BACK：按 1 m/s 沿当前 heading 移动（默认正北）
 * - TURN：**只改 heading**（60°/s），不动位置、不计里程
 * - WAIT：原地不动，计时完成
 *
 * 两种模式：
 * - [TaskMode.AUTO]：段间零间隔，自动接续
 * - [TaskMode.SEGMENT_CONFIRM]：段间停在 `WAITING_CONFIRM`，等 `confirmSegment()` 才跑下一段
 *
 * 段失败 → 后续段 `SKIPPED`（熔断）；急停/取消同理。
 * 状态推进由 fetchStatus() 的调用节奏驱动（tick()）。
 */
class FakeDeviceRepository : DeviceRepository {

    private val mutex = Mutex()

    var fault: FakeFault = FakeFault.None

    /**
     * 是否模拟车端 GPS 模块（默认 false = 复刻现阶段真实硬件，主走行程估算路径）。
     * 开启后**故意输出 WGS-84**，让演示机走真实 WGS-84→GCJ-02 转换链路。
     */
    var gpsEnabled: Boolean = false

    /** 车端 GPS 模拟基准点（GCJ-02）；真机上由 MapViewModel 用手机实际定位覆盖 */
    @Volatile
    var gpsBaseGcj02: GeoPoint = BASE_GCJ02

    /** 模拟行进方向（度，正北=0）；与作业人员方向一致，未知时默认正北 */
    @Volatile
    var headingDeg: Double = 0.0

    /** 相对锚点 A 的局部坐标（米）：x=东，y=北，默认正北 */
    private var posEastM = 0.0
    private var posNorthM = 0.0

    /**
     * 时间加速系数（**仅供单测**，生产默认 1.0）。
     * 让 tick() 的 dtSec 按倍数放大，测试无需真实等待即可推进完整路线。
     */
    @Volatile
    var tickScale: Double = 1.0

    /** 当前选中的设备 id（连接页下拉选择后写入） */
    @Volatile
    var selectedDeviceCode: String = DEVICE_CODE

    private var lastTickAt = System.currentTimeMillis()
    private var lastCmdAt = System.currentTimeMillis()
    private var movingDir: MoveDir? = null

    // ── 设备侧状态 ──
    private var mode = VehicleMode.STANDBY
    private var motion = MotionState.IDLE
    private var estopLocked = false
    private var stopSource = StopSource.NONE
    private var light = LightMode.OFF
    private var task = TaskInfo()
    private var odo = OdoInfo()

    // ── 路线分段状态机 ──
    private var routeSegments: MutableList<SegmentState> = mutableListOf()
    private var routeMode: TaskMode = TaskMode.AUTO
    private var segElapsedSec = 0.0     // WAIT 段计时
    private var turnDoneDeg = 0.0       // TURN 段已转角度

    // ══════════════ 设备列表 ══════════════

    /** 默认两台设备，供连接页下拉选择 */
    private val devices = listOf(
        com.example.myapplication.core.model.vehicle.DeviceSummary(
            id = "TC-001", name = "三角警示牌一号车",
            started = true, available = true, batteryPercent = 86,
        ),
        com.example.myapplication.core.model.vehicle.DeviceSummary(
            id = "TC-002", name = "三角警示牌二号车",
            started = false, available = false, batteryPercent = 32,
        ),
    )

    override suspend fun listDevices(): List<com.example.myapplication.core.model.vehicle.DeviceSummary> {
        delay(150)
        return devices
    }

    // ══════════════ 查询 ══════════════

    /**
     * 连接设备（连接即绑定）：模拟服务端的绑定码校验 —— **绑定码必填**（空 → 失败），
     * 非空即通过（与服务端 Mock 的宽松口径一致，便于演示模式跑通全流程）。
     */
    override suspend fun connect(request: DeviceConnectRequest): Result<Unit> = mutex.withLock {
        if (fault is FakeFault.LinkLoss) {
            return Result.failure(IOException("无法连接设备（模拟失联）"))
        }
        if (request.bindingCode.isBlank()) {
            return Result.failure(IOException("绑定码不能为空，请输入设备铭牌上的绑定码"))
        }
        delay(NETWORK_DELAY_MS * 4)   // 模拟一次绑定请求往返
        selectedDeviceCode = request.deviceCode
        Result.success(Unit)
    }

    override suspend fun fetchStatus(): Result<DeviceSnapshot> = mutex.withLock {
        if (fault is FakeFault.LinkLoss) {
            return Result.failure(IOException("无法连接设备（模拟失联）"))
        }
        delay(NETWORK_DELAY_MS)
        tick()
        val snapshot = buildSnapshot()
        val f = fault
        if (f is FakeFault.OdoDeviation) {
            return Result.success(snapshot.copy(odo = snapshot.odo.copy(diffPulse = snapshot.odo.diffPulse + f.extraPulse)))
        }
        Result.success(snapshot)
    }

    // ══════════════ 手动遥控 ══════════════

    override suspend fun sendMove(dir: MoveDir): Result<CmdAck> = command {
        if (estopLocked || mode == VehicleMode.SAFE_STOP) {
            return@command CmdAck(false, reason = "estop_locked：急停锁定中，禁止运动指令")
        }
        // 任务执行中允许手动微调：手动期间暂停自动推进，松手后任务继续
        movingDir = dir
        lastCmdAt = System.currentTimeMillis()
        if (task.state != TaskRunState.RUNNING) {
            mode = VehicleMode.MANUAL
        }
        motion = MotionState.MOVING
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    /**
     * 停车语义细化：手动微调停车**不取消**处于 WAITING_CONFIRM 的任务；
     * 仅当任务正在执行运动段时才把任务置 CANCELED。
     */
    override suspend fun sendStop(): Result<CmdAck> = command {
        movingDir = null
        lastCmdAt = System.currentTimeMillis()
        motion = MotionState.IDLE
        val runningSeg = currentSegment()
        if (task.state == TaskRunState.RUNNING && runningSeg?.state == SegmentRunState.RUNNING) {
            routeSegments = routeSegments.map {
                if (it.state == SegmentRunState.RUNNING) it.copy(state = SegmentRunState.FAILED) else it
            }.map {
                if (it.state == SegmentRunState.PENDING) it.copy(state = SegmentRunState.SKIPPED) else it
            }.toMutableList()
            task = task.copy(state = TaskRunState.CANCELED, resultNote = "manual_stop", segments = routeSegments)
            mode = VehicleMode.STANDBY
            stopSource = StopSource.MANUAL
        } else {
            // 微调停车 / 无任务：仅停运动，不碰任务
            if (task.state != TaskRunState.RUNNING && !estopLocked) mode = VehicleMode.STANDBY
            stopSource = StopSource.MANUAL
        }
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    // ══════════════ 急停 / 恢复 ══════════════

    /** 手动微调结束：只停手动运动，不取消正在运行的任务。 */
    override suspend fun sendManualStop(): Result<CmdAck> = command {
        movingDir = null
        motion = MotionState.IDLE
        odo = odo.copy(speedMps = 0.0)
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun sendEstop(): Result<CmdAck> = command {
        movingDir = null
        motion = MotionState.IDLE
        estopLocked = true
        mode = VehicleMode.SAFE_STOP
        stopSource = StopSource.ESTOP
        if (task.state == TaskRunState.RUNNING) {
            failRoute("estop", TaskRunState.CANCELED)
        }
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun sendResume(): Result<CmdAck> = command {
        if (!estopLocked && mode != VehicleMode.SAFE_STOP) {
            return@command CmdAck(false, reason = "not_locked：当前未处于急停锁定状态")
        }
        estopLocked = false
        mode = VehicleMode.STANDBY
        motion = MotionState.IDLE
        stopSource = StopSource.NONE
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    // ══════════════ 路线任务 ══════════════

    /** 单段快捷路径（兼容旧调用 / 车身按钮任务） */
    override suspend fun startTask(kind: TaskKind, targetM: Double): Result<CmdAck> = startRoute(
        kind = kind,
        mode = TaskMode.AUTO,
        segments = listOf(RouteSegment(action = SegmentAction.FWD, value = targetM)),
        light = light,
    )

    override suspend fun startRoute(
        kind: TaskKind,
        mode: TaskMode,
        segments: List<RouteSegment>,
        light: LightMode,
        lane: Int?,
    ): Result<CmdAck> = command {
        if (estopLocked || this.mode == VehicleMode.SAFE_STOP) {
            return@command CmdAck(false, reason = "estop_locked：急停锁定中，禁止启动任务")
        }
        if (task.state == TaskRunState.RUNNING) {
            return@command CmdAck(false, reason = "task_running：已有任务运行中，禁止重复启动")
        }
        if (segments.isEmpty()) {
            return@command CmdAck(false, reason = "route_empty：路线不能为空")
        }
        if (segments.size > MAX_SEGMENTS) {
            return@command CmdAck(false, reason = "too_many_segments：路段不能超过 $MAX_SEGMENTS 段")
        }
        segments.forEach { seg ->
            val ok = when (seg.action) {
                SegmentAction.FWD -> seg.value in MIN_M..MAX_M
                SegmentAction.TURN -> abs(seg.value) in MIN_DEG..MAX_DEG
            }
            if (!ok) {
                return@command CmdAck(false, reason = "segment_out_of_range：路段参数超出允许范围")
            }
        }

        routeMode = mode
        this.light = light
        // 新任务从作业人员锚点 A 出发：局部坐标归零，默认正北
        posEastM = 0.0
        posNorthM = 0.0
        headingDeg = 0.0
        routeSegments = segments.map {
            SegmentState(action = it.action, value = it.value, state = SegmentRunState.PENDING)
        }.toMutableList()
        resetSegProgress()
        beginSegment(0, autoStart = mode == TaskMode.AUTO)

        val totalM = segments
            .filter { it.action == SegmentAction.FWD }
            .sumOf { it.value }

        task = TaskInfo(
            id = "t-${UUID.randomUUID().toString().take(6)}",
            kind = kind,
            source = TaskSource.APP,
            targetM = totalM,
            traveledM = 0.0,
            state = TaskRunState.RUNNING,
            stage = null,
            resultNote = null,
            segments = routeSegments,
            headingDeg = headingDeg,
            lane = lane,          // 作业车道随任务保存，/status 回显（与真机/服务端口径一致）
        )
        this.mode = if (mode == TaskMode.AUTO) VehicleMode.AUTO else VehicleMode.STANDBY
        motion = MotionState.IDLE
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun confirmSegment(): Result<CmdAck> = command {
        if (task.state != TaskRunState.RUNNING) {
            return@command CmdAck(false, reason = "no_task：当前无运行中任务")
        }
        val idx = routeSegments.indexOfFirst { it.state == SegmentRunState.WAITING_CONFIRM }
        if (idx == -1) {
            return@command CmdAck(false, reason = "not_waiting_confirm：当前没有待确认的路段")
        }
        resetSegProgress()
        beginSegment(idx, autoStart = true)
        task = task.copy(segments = routeSegments)
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun startRecover(): Result<CmdAck> = command {
        if (estopLocked || mode == VehicleMode.SAFE_STOP) {
            return@command CmdAck(false, reason = "estop_locked：急停锁定中，禁止回收")
        }
        if (task.state == TaskRunState.RUNNING) {
            return@command CmdAck(false, reason = "task_running：已有任务运行中，禁止重复启动")
        }
        if (odo.traveledM <= 0.01) {
            return@command CmdAck(false, reason = "already_home：小车已在作业人员位置，无需回收")
        }
        task = TaskInfo(
            id = "t-${UUID.randomUUID().toString().take(6)}",
            kind = TaskKind.RECOVER,
            source = TaskSource.APP,
            targetM = odo.traveledM,
            traveledM = odo.traveledM,
            state = TaskRunState.RUNNING,
            stage = "recovering",
            resultNote = null,
        )
        mode = VehicleMode.AUTO
        motion = MotionState.IDLE
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun cancelTask(): Result<CmdAck> = command {
        if (task.state == TaskRunState.RUNNING) {
            failRoute("canceled_by_app", TaskRunState.CANCELED)
            mode = VehicleMode.STANDBY
            motion = MotionState.IDLE
            movingDir = null
        }
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    /** 用户现场确认任务完成：停止运动并把任务置为 DONE（幂等）。 */
    override suspend fun completeTask(): Result<CmdAck> = command {
        if (task.state == TaskRunState.DONE) {
            return@command CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
        }
        if (task.state != TaskRunState.RUNNING) {
            return@command CmdAck(false, reason = "no_task：当前无运行中任务")
        }
        movingDir = null
        motion = MotionState.IDLE
        mode = VehicleMode.STANDBY
        odo = odo.copy(speedMps = 0.0)
        // 用户确认完成：当前运行/待确认段收尾为 done，后续未执行段标 skipped
        routeSegments = routeSegments.map {
            when (it.state) {
                SegmentRunState.RUNNING, SegmentRunState.WAITING_CONFIRM ->
                    it.copy(state = SegmentRunState.DONE, progress = 1f)
                SegmentRunState.PENDING -> it.copy(state = SegmentRunState.SKIPPED)
                else -> it
            }
        }.toMutableList()
        task = task.copy(
            state = TaskRunState.DONE,
            resultNote = "user_confirmed",
            segments = routeSegments,
        )
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun setLight(modeIn: LightMode): Result<CmdAck> = command {
        light = modeIn
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun resetOdo(): Result<CmdAck> = command {
        if (motion != MotionState.IDLE || task.state == TaskRunState.RUNNING) {
            return@command CmdAck(false, reason = "busy：自动运动中禁止计数清零")
        }
        odo = OdoInfo()
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    // ══════════════ 内部：命令通道 ══════════════

    private suspend fun command(block: suspend () -> CmdAck): Result<CmdAck> = mutex.withLock {
        val f = fault
        when (f) {
            is FakeFault.LinkLoss -> return Result.failure(IOException("连接中断（模拟失联）"))
            is FakeFault.SlowAck -> {
                delay(f.delayMs)
                return Result.failure(java.net.SocketTimeoutException("命令应答超时"))
            }

            else -> Unit
        }
        delay(NETWORK_DELAY_MS)
        val ack = block()
        return when (f) {
            is FakeFault.CommandRejected -> Result.success(CmdAck(false, reason = "estop_locked：设备拒绝该指令"))
            is FakeFault.ResultUnknown -> Result.success(ack.copy(accepted = true))
            else -> Result.success(ack)
        }
    }

    // ══════════════ 内部：分段推进 ══════════════

    private fun currentSegment(): SegmentState? = routeSegments.firstOrNull {
        it.state == SegmentRunState.RUNNING || it.state == SegmentRunState.WAITING_CONFIRM
    }

    private fun resetSegProgress() {
        segElapsedSec = 0.0
        turnDoneDeg = 0.0
    }

    /** 开始第 idx 段 */
    private fun beginSegment(idx: Int, autoStart: Boolean) {
        if (idx !in routeSegments.indices) return
        val st = if (autoStart) SegmentRunState.RUNNING else SegmentRunState.WAITING_CONFIRM
        routeSegments[idx] = routeSegments[idx].copy(state = st, progress = 0f)
    }

    /** 当前段中止（失败/取消/急停），后续段一律 SKIPPED（熔断） */
    private fun failRoute(note: String, finalState: TaskRunState = TaskRunState.FAILED) {
        routeSegments = routeSegments.map {
            when {
                it.state == SegmentRunState.RUNNING || it.state == SegmentRunState.WAITING_CONFIRM ->
                    it.copy(state = SegmentRunState.FAILED)

                it.state == SegmentRunState.PENDING -> it.copy(state = SegmentRunState.SKIPPED)
                else -> it
            }
        }.toMutableList()
        task = task.copy(state = finalState, resultNote = note, segments = routeSegments)
        mode = VehicleMode.STANDBY
        motion = MotionState.IDLE
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dtSec = (now - lastTickAt).coerceAtLeast(0L) / 1000.0 * tickScale
        lastTickAt = now

        // 断流看门狗：手动移动中 800ms 无新指令 → 自动停车
        if (movingDir != null && now - lastCmdAt > WATCHDOG_MS) {
            movingDir = null
            motion = MotionState.IDLE
            stopSource = StopSource.LINK_LOSS
            if (task.state != TaskRunState.RUNNING && mode == VehicleMode.MANUAL) mode = VehicleMode.STANDBY
        }

        if (fault is FakeFault.ResultUnknown) return   // 接受命令但不推进 → 结果未知

        // 手动微调移动（不推进段进度）：前进/后退沿航向位移，左转/右转只改航向
        val dir = movingDir
        if (dir != null && motion == MotionState.MOVING) {
            if (dir == MoveDir.LEFT || dir == MoveDir.RIGHT) {
                val d = TURN_SPEED_DPS * dtSec
                val delta = if (dir == MoveDir.LEFT) -d else d
                headingDeg = (headingDeg + delta + 360.0) % 360.0
                odo = odo.copy(speedMps = 0.0)
            } else {
                val step = MANUAL_SPEED_MPS * dtSec
                val sign = if (dir == MoveDir.BACK) -1.0 else 1.0
                val rad = Math.toRadians(headingDeg)
                posEastM += sign * step * kotlin.math.sin(rad)
                posNorthM += sign * step * kotlin.math.cos(rad)
                odo = odo.copy(
                    traveledM = odo.traveledM + step,
                    leftPulse = odo.leftPulse + (step * PULSE_PER_M).toLong(),
                    rightPulse = odo.rightPulse + (step * PULSE_PER_M).toLong(),
                    speedMps = MANUAL_SPEED_MPS,
                )
            }
        }

        // 任务推进：手动微调期间暂停，松手（manual_stop）后从当前进度继续
        val manualOverride = movingDir != null && motion == MotionState.MOVING
        when {
            !manualOverride && task.kind == TaskKind.RECOVER && task.state == TaskRunState.RUNNING ->
                tickRecover(dtSec)
            !manualOverride && task.state == TaskRunState.RUNNING -> tickRoute(dtSec, now)
            !manualOverride && motion != MotionState.MOVING -> odo = odo.copy(speedMps = 0.0)
        }
    }

    /** 路线分段推进 */
    private fun tickRoute(dtSec: Double, now: Long) {
        val idx = routeSegments.indexOfFirst { it.state == SegmentRunState.RUNNING }
        if (idx == -1) {
            // 无运行段（可能全在 WAITING_CONFIRM）
            motion = MotionState.IDLE
            odo = odo.copy(speedMps = 0.0)
            task = task.copy(segments = routeSegments)
            return
        }
        val seg = routeSegments[idx]
        when (seg.action) {
            SegmentAction.FWD -> {
                val remaining = (seg.value * (1 - seg.progress)).coerceAtLeast(0.0)
                val step = kotlin.math.min(MOVE_SPEED_MPS * dtSec, remaining)
                val done = (seg.value * seg.progress) + step
                val progress = (done / seg.value).toFloat().coerceAtMost(1f)
                routeSegments[idx] = seg.copy(progress = progress)
                val rad = Math.toRadians(headingDeg)
                posEastM += step * kotlin.math.sin(rad)
                posNorthM += step * kotlin.math.cos(rad)
                odo = odo.copy(
                    traveledM = odo.traveledM + step,
                    leftPulse = odo.leftPulse + (step * PULSE_PER_M).toLong(),
                    rightPulse = odo.rightPulse + (step * PULSE_PER_M).toLong(),
                    speedMps = MOVE_SPEED_MPS,
                )
                motion = MotionState.MOVING
                if (progress >= 1f) completeSegment(idx)
            }

            SegmentAction.TURN -> {
                val target = abs(seg.value)
                val remaining = (target - turnDoneDeg).coerceAtLeast(0.0)
                val stepDeg = kotlin.math.min(TURN_SPEED_DPS * dtSec, remaining)
                turnDoneDeg += stepDeg
                val progress = (turnDoneDeg / target).toFloat().coerceAtMost(1f)
                routeSegments[idx] = seg.copy(progress = progress)
                // 只改 heading，不动位置、不计里程
                headingDeg = (headingDeg + (if (seg.value < 0) -stepDeg else stepDeg) + 360.0) % 360.0
                motion = MotionState.MOVING
                odo = odo.copy(speedMps = 0.0)
                if (progress >= 1f) completeSegment(idx)
            }

        }
        task = task.copy(segments = routeSegments, headingDeg = headingDeg)
    }

    /** 段完成：接续下一段或收尾 */
    private fun completeSegment(idx: Int) {
        routeSegments[idx] = routeSegments[idx].copy(state = SegmentRunState.DONE, progress = 1f)
        resetSegProgress()
        val next = idx + 1
        if (next >= routeSegments.size) {
            // 全部完成
            task = task.copy(
                state = TaskRunState.DONE,
                resultNote = "route_done",
                segments = routeSegments,
                traveledM = routeSegments
                    .filter { it.action == SegmentAction.FWD }
                    .sumOf { it.value },
            )
            motion = MotionState.IDLE
            mode = VehicleMode.STANDBY
            odo = odo.copy(speedMps = 0.0)
        } else {
            beginSegment(next, autoStart = routeMode == TaskMode.AUTO)
            task = task.copy(segments = routeSegments)
            if (routeMode == TaskMode.SEGMENT_CONFIRM) {
                // 间隙：待人工确认，允许手动微调
                mode = VehicleMode.STANDBY
                motion = MotionState.IDLE
            }
        }
    }

    /** 回收：行驶里程递减回 0（= 作业人员锚点） */
    private fun tickRecover(dtSec: Double) {
        val step = RECOVER_SPEED_MPS * dtSec
        val traveled = (task.traveledM - step).coerceAtLeast(0.0)
        task = task.copy(traveledM = traveled)
        odo = odo.copy(
            traveledM = traveled,
            leftPulse = (traveled * PULSE_PER_M).toLong(),
            rightPulse = (traveled * PULSE_PER_M).toLong(),
            speedMps = RECOVER_SPEED_MPS,
        )
        motion = MotionState.MOVING
        // 沿当前位置指向锚点 A 的方向回收
        val mag = kotlin.math.hypot(posEastM, posNorthM)
        if (mag > 0) {
            val f = ((mag - step).coerceAtLeast(0.0)) / mag
            val hx = posEastM
            val hy = posNorthM
            posEastM *= f
            posNorthM *= f
            headingDeg = (Math.toDegrees(kotlin.math.atan2(-hx, -hy)) + 360.0) % 360.0
        }
        if (traveled <= 0.01) {
            task = task.copy(state = TaskRunState.DONE, resultNote = "recovered")
            motion = MotionState.IDLE
            mode = VehicleMode.STANDBY
            odo = odo.copy(traveledM = 0.0, speedMps = 0.0)
            posEastM = 0.0
            posNorthM = 0.0
        }
    }

    // ══════════════ 快照与 GPS ══════════════

    private fun buildSnapshot(): DeviceSnapshot = DeviceSnapshot(
        deviceCode = selectedDeviceCode,
        fw = FW_VERSION,
        mode = mode,
        motion = motion,
        estopLocked = estopLocked,
        stopSource = stopSource,
        light = light,
        task = task.copy(segments = routeSegments, headingDeg = headingDeg),
        odo = odo,
        batteryPercent = null,
        tilt = null,
        obstacle = null,
        deviceTs = System.currentTimeMillis(),
        receivedAt = System.currentTimeMillis(),
        gps = buildGps(),
    )

    private fun buildGps(): VehicleGps? {
        if (!gpsEnabled) return null
        // 位置 = 锚点 A + 车辆局部坐标（x=东，y=北，米）
        val dLat = posNorthM / 111_320.0
        val dLng = posEastM / (111_320.0 * kotlin.math.cos(Math.toRadians(gpsBaseGcj02.lat)))
        val driftedGcj = GeoPoint(gpsBaseGcj02.lat + dLat, gpsBaseGcj02.lng + dLng)
        val wgs = CoordTransform.gcj02ToWgs84(driftedGcj) // 故意给 WGS-84，走真实转换链路
        return VehicleGps(
            wgs84Lat = wgs.lat,
            wgs84Lng = wgs.lng,
            accuracyM = 3.5f,
            speedMps = odo.speedMps,
            headingDeg = headingDeg,
            state = GpsFixState.FIXED,
            at = System.currentTimeMillis(),
        )
    }

    companion object {
        const val DEVICE_CODE = "TC-001"
        const val FW_VERSION = "0.3.1"

        val BASE_GCJ02: GeoPoint = GeoPoint(39.9928, 116.4004)
        const val DEMO_HEADING_DEG = 0.0

        private const val NETWORK_DELAY_MS = 50L
        private const val WATCHDOG_MS = 800L
        private const val MANUAL_SPEED_MPS = 1.0
        private const val MOVE_SPEED_MPS = 1.0
        private const val RECOVER_SPEED_MPS = 1.0
        private const val TURN_SPEED_DPS = 60.0
        private const val PULSE_PER_M = 500.0

        // 与 TaskSpec/SegmentRange 同一套规则
        private const val MAX_SEGMENTS = 10
        private const val MIN_M = 1.0
        private const val MAX_M = 50.0
        private const val MIN_DEG = 5.0
        private const val MAX_DEG = 180.0
    }
}
