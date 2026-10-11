package com.example.myapplication.core.data.device

import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MotionState
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.core.model.vehicle.OdoInfo
import com.example.myapplication.core.model.vehicle.StopSource
import com.example.myapplication.core.model.vehicle.TaskInfo
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.model.vehicle.TaskSource
import com.example.myapplication.core.model.vehicle.VehicleMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

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
 * 内部持有一个"设备侧状态机"，按已验证的车端行为模拟：
 * - 按住移动：sendMove 后 0.25 m/s 递增；sendStop 立即停车
 * - 断流看门狗：800ms 内无 move/stop 自动停车（stopSource=LINK_LOSS）
 * - 定距任务：递增至目标后 state=DONE
 * - 一键任务：灯光双闪 → 倒计时 → 低速直行 → 停车续闪
 *
 * 状态推进由 fetchStatus() 的调用节奏驱动（tick()），无需额外定时器。
 */
class FakeDeviceRepository : DeviceRepository {

    private val mutex = Mutex()

    var fault: FakeFault = FakeFault.None

    private var lastTickAt = System.currentTimeMillis()
    private var lastCmdAt = System.currentTimeMillis()
    private var movingDir: MoveDir? = null

    // 设备侧状态
    private var mode = VehicleMode.STANDBY
    private var motion = MotionState.IDLE
    private var estopLocked = false
    private var stopSource = StopSource.NONE
    private var light = LightMode.OFF
    private var task = TaskInfo()
    private var odo = OdoInfo()
    private var oneTouchStage: String? = null
    private var stageDeadline = 0L

    override suspend fun fetchStatus(): Result<DeviceSnapshot> = mutex.withLock {
        if (fault is FakeFault.LinkLoss) {
            return Result.failure(IOException("无法连接 192.168.4.1（模拟失联）"))
        }
        delay(NETWORK_DELAY_MS)
        tick()
        val faultNow = fault
        val snapshot = buildSnapshot()
        if (faultNow is FakeFault.OdoDeviation) {
            // 注入左右轮偏差，行程差变大（M09 差值数据）
            return Result.success(
                snapshot.copy(odo = snapshot.odo.copy(diffPulse = snapshot.odo.diffPulse + faultNow.extraPulse)),
            )
        }
        Result.success(snapshot)
    }

    override suspend fun sendMove(dir: MoveDir): Result<CmdAck> = command {
        if (estopLocked || mode == VehicleMode.SAFE_STOP) {
            return@command CmdAck(false, reason = "estop_locked：急停锁定中，禁止运动指令")
        }
        if (mode == VehicleMode.AUTO && task.state == TaskRunState.RUNNING) {
            return@command CmdAck(false, reason = "task_running：自动任务执行中，禁止手动抢占")
        }
        movingDir = dir
        lastCmdAt = System.currentTimeMillis()
        mode = VehicleMode.MANUAL
        motion = MotionState.MOVING
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun sendStop(): Result<CmdAck> = command {
        movingDir = null
        lastCmdAt = System.currentTimeMillis()
        motion = MotionState.IDLE
        if (task.state == TaskRunState.RUNNING) {
            task = task.copy(state = TaskRunState.CANCELED, resultNote = "manual_stop")
        }
        if (!estopLocked) mode = VehicleMode.STANDBY
        stopSource = StopSource.MANUAL
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun sendEstop(): Result<CmdAck> = command {
        movingDir = null
        motion = MotionState.IDLE
        estopLocked = true
        mode = VehicleMode.SAFE_STOP
        stopSource = StopSource.ESTOP
        if (task.state == TaskRunState.RUNNING) {
            task = task.copy(state = TaskRunState.CANCELED, resultNote = "estop")
        }
        // 关键：急停不自动关灯（M07 灯光与运动分离）
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
        // 不自动续跑任何任务（M08）
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun startTask(kind: TaskKind, targetM: Double): Result<CmdAck> = command {
        if (estopLocked || mode == VehicleMode.SAFE_STOP) {
            return@command CmdAck(false, reason = "estop_locked：急停锁定中，禁止启动任务")
        }
        if (task.state == TaskRunState.RUNNING) {
            return@command CmdAck(false, reason = "task_running：已有任务运行中，禁止重复启动")
        }
        if (targetM !in ALLOWED_TARGETS) {
            return@command CmdAck(false, reason = "param_out_of_range：仅支持 0.5／1／2 米档位")
        }
        task = TaskInfo(
            id = "t-${UUID.randomUUID().toString().take(6)}",
            kind = kind,
            source = TaskSource.APP,
            targetM = targetM,
            traveledM = 0.0,
            state = TaskRunState.RUNNING,
            stage = if (kind == TaskKind.ONE_TOUCH) "lighting" else null,
            resultNote = null,
        )
        mode = VehicleMode.AUTO
        motion = MotionState.IDLE
        oneTouchStage = if (kind == TaskKind.ONE_TOUCH) "lighting" else null
        stageDeadline = System.currentTimeMillis() + STAGE_LIGHTING_MS
        if (kind == TaskKind.ONE_TOUCH) light = LightMode.BLINK
        CmdAck(true, "c-${UUID.randomUUID().toString().take(6)}")
    }

    override suspend fun cancelTask(): Result<CmdAck> = command {
        if (task.state == TaskRunState.RUNNING) {
            task = task.copy(state = TaskRunState.CANCELED, resultNote = "canceled_by_app")
            mode = VehicleMode.STANDBY
            motion = MotionState.IDLE
            movingDir = null
        }
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

    // ---------- 内部 ----------

    private suspend fun command(block: suspend () -> CmdAck): Result<CmdAck> = mutex.withLock {
        val faultNow = fault
        when (faultNow) {
            is FakeFault.LinkLoss -> return Result.failure(IOException("连接中断（模拟失联）"))
            is FakeFault.SlowAck -> {
                delay(faultNow.delayMs)
                return Result.failure(java.net.SocketTimeoutException("命令应答超时"))
            }

            else -> Unit
        }
        delay(NETWORK_DELAY_MS)
        val ack = block()
        return when (faultNow) {
            is FakeFault.CommandRejected ->
                Result.success(CmdAck(false, reason = "estop_locked：设备拒绝该指令"))

            is FakeFault.ResultUnknown ->
                Result.success(ack.copy(accepted = true)) // 接受但 tick 不推进执行

            else -> Result.success(ack)
        }
    }

    /** 按经过时间推进设备侧状态（由 fetchStatus 节奏驱动） */
    private fun tick() {
        val now = System.currentTimeMillis()
        val dtSec = (now - lastTickAt).coerceAtLeast(0L) / 1000.0
        lastTickAt = now

        // 断流看门狗：800ms 无 move/stop 则自动停车（已验证的车端能力）
        if (movingDir != null && now - lastCmdAt > WATCHDOG_MS) {
            movingDir = null
            motion = MotionState.IDLE
            stopSource = StopSource.LINK_LOSS
            if (mode == VehicleMode.MANUAL) mode = VehicleMode.STANDBY
        }

        val faultNow = fault
        if (faultNow is FakeFault.ResultUnknown) return // 接受命令但不推进 → 结果未知

        // 手动移动：0.25 m/s
        if (movingDir != null && motion == MotionState.MOVING) {
            val step = MANUAL_SPEED_MPS * dtSec
            odo = odo.copy(
                traveledM = odo.traveledM + step,
                leftPulse = odo.leftPulse + (step * PULSE_PER_M).toLong(),
                rightPulse = odo.rightPulse + (step * PULSE_PER_M).toLong(),
                speedMps = MANUAL_SPEED_MPS,
                diffPulse = 0L,
            )
        }

        // 自动任务推进
        if (task.state == TaskRunState.RUNNING) {
            when (task.kind) {
                TaskKind.ONE_TOUCH -> tickOneTouch(now, dtSec)
                else -> tickFixedDistance(dtSec)
            }
        } else if (motion != MotionState.MOVING) {
            odo = odo.copy(speedMps = 0.0)
        }
    }

    private fun tickFixedDistance(dtSec: Double) {
        val step = FIXED_SPEED_MPS * dtSec
        val traveled = (task.traveledM + step).coerceAtMost(task.targetM)
        task = task.copy(traveledM = traveled)
        odo = odo.copy(
            traveledM = odo.traveledM + step,
            leftPulse = odo.leftPulse + (step * PULSE_PER_M).toLong(),
            rightPulse = odo.rightPulse + (step * PULSE_PER_M).toLong(),
            speedMps = FIXED_SPEED_MPS,
        )
        motion = MotionState.MOVING
        if (traveled >= task.targetM - 1e-6) {
            task = task.copy(state = TaskRunState.DONE, resultNote = "arrived")
            motion = MotionState.IDLE
            mode = VehicleMode.STANDBY
            odo = odo.copy(speedMps = 0.0)
        }
    }

    private fun tickOneTouch(now: Long, dtSec: Double) {
        val stage = oneTouchStage
        when {
            stage == "lighting" -> {
                light = LightMode.BLINK
                motion = MotionState.IDLE
                if (now >= stageDeadline) {
                    oneTouchStage = "countdown"
                    stageDeadline = now + STAGE_COUNTDOWN_MS
                    task = task.copy(stage = "countdown")
                }
            }

            stage == "countdown" -> {
                light = LightMode.BLINK
                if (now >= stageDeadline) {
                    oneTouchStage = "moving"
                    task = task.copy(stage = "moving")
                }
            }

            stage == "moving" -> {
                light = LightMode.BLINK
                val step = ONE_TOUCH_SPEED_MPS * dtSec
                val traveled = (task.traveledM + step).coerceAtMost(task.targetM)
                task = task.copy(traveledM = traveled, stage = "moving")
                motion = MotionState.MOVING
                odo = odo.copy(
                    traveledM = odo.traveledM + step,
                    leftPulse = odo.leftPulse + (step * PULSE_PER_M).toLong(),
                    rightPulse = odo.rightPulse + (step * PULSE_PER_M).toLong(),
                    speedMps = ONE_TOUCH_SPEED_MPS,
                )
                if (traveled >= task.targetM - 1e-6) {
                    // 自动停车且继续闪灯
                    oneTouchStage = "parked"
                    task = task.copy(state = TaskRunState.DONE, stage = "parked", resultNote = "parked_keep_blink")
                    motion = MotionState.IDLE
                    mode = VehicleMode.STANDBY
                    light = LightMode.BLINK
                    odo = odo.copy(speedMps = 0.0)
                }
            }

            else -> {
                // parked / 完成：保持双闪，等待人工核验
                light = LightMode.BLINK
                motion = MotionState.IDLE
            }
        }
    }

    private fun buildSnapshot(): DeviceSnapshot = DeviceSnapshot(
        deviceId = DEVICE_ID,
        fw = FW_VERSION,
        mode = mode,
        motion = motion,
        estopLocked = estopLocked,
        stopSource = stopSource,
        light = light,
        task = task.copy(stage = task.stage ?: oneTouchStage),
        odo = odo,
        batteryPercent = null, // C 阶段：无可靠数据来源
        tilt = null,
        obstacle = null,
        deviceTs = System.currentTimeMillis(),
        receivedAt = System.currentTimeMillis(),
    )

    companion object {
        const val DEVICE_ID = "TC-001"
        const val FW_VERSION = "0.3.1"

        private const val NETWORK_DELAY_MS = 50L
        private const val WATCHDOG_MS = 800L
        private const val MANUAL_SPEED_MPS = 0.25
        private const val FIXED_SPEED_MPS = 0.25
        private const val ONE_TOUCH_SPEED_MPS = 0.15
        private const val PULSE_PER_M = 500.0
        private const val STAGE_LIGHTING_MS = 1500L
        private const val STAGE_COUNTDOWN_MS = 3000L
        private val ALLOWED_TARGETS = setOf(0.5, 1.0, 2.0)
    }
}
