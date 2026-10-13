package com.example.myapplication.core.data.device

import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.DeviceSummary
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.core.model.vehicle.RouteSegment
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskMode

/** 设备命令应答 */
data class CmdAck(
    val accepted: Boolean,
    val cmdId: String? = null,
    /** 拒绝原因（如 estop_locked / param_out_of_range），必须原样展示给用户（NFR 可读原因） */
    val reason: String? = null,
)

/**
 * 三角警示牌小车通信接口（M01）。
 * **仓库只负责一次请求-应答**；轮询、连接态、命令分层票据全在 VehicleViewModel（便于单测）。
 *
 * 约定失败语义：
 * - `Result.failure`（IO/Connect）→ 失联
 * - 超时 → 失联/结果未知（由 ViewModel 计时判定）
 * - 200 但 `accepted:false` → 设备拒绝，携带 reason
 */
interface DeviceRepository {
    /** 可选设备列表（连接页下拉选择用） */
    suspend fun listDevices(): List<DeviceSummary>

    /** GET /status —— 唯一事实源，仅收到有效状态才算"已连接" */
    suspend fun fetchStatus(): Result<DeviceSnapshot>

    /** POST /cmd {type:move} —— 按住移动（M04） */
    suspend fun sendMove(dir: MoveDir): Result<CmdAck>

    /** POST /cmd {type:stop} —— 普通停车：停运动+取消任务，不锁、不关灯（M04） */
    suspend fun sendStop(): Result<CmdAck>

    /** POST /cmd {type:estop} —— 急停：进入安全停止并锁定（M08） */
    suspend fun sendEstop(): Result<CmdAck>

    /** POST /cmd {type:resume} —— 人工恢复至待命，解锁急停，不续跑任务（M08） */
    suspend fun sendResume(): Result<CmdAck>

    /** POST /cmd {type:task_start} —— 启动定距/一键任务（M05/M06，单段快捷路径） */
    suspend fun startTask(kind: TaskKind, targetM: Double): Result<CmdAck>

    /**
     * POST /cmd {type:task_start, segments:[...]} —— **整条路线一次性下发**。
     * 车端离线执行整条路线（失联语义正确），段间衔接由车端闭环。
     */
    suspend fun startRoute(
        kind: TaskKind,
        mode: TaskMode,
        segments: List<RouteSegment>,
        light: LightMode,
    ): Result<CmdAck>

    /** POST /cmd {type:segment_confirm} —— 分段确认模式：确认执行当前段 */
    suspend fun confirmSegment(): Result<CmdAck>

    /** POST /cmd {type:recover} —— 自动回收：小车返回作业人员位置（起点=车，终点=人） */
    suspend fun startRecover(): Result<CmdAck>

    /** POST /cmd {type:task_cancel} */
    suspend fun cancelTask(): Result<CmdAck>

    /** POST /light —— 三模式互斥（M07）；最终显示以 /status 为准 */
    suspend fun setLight(mode: LightMode): Result<CmdAck>

    /** POST /cmd {type:odo_reset} —— 仅静止且无任务时允许（M09） */
    suspend fun resetOdo(): Result<CmdAck>
}
