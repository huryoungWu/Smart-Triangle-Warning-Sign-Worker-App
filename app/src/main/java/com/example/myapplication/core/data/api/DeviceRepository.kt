package com.example.myapplication.core.data.api

import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.DeviceSummary
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.core.model.vehicle.RouteSegment
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskMode

/** 设备命令应答（契约数据类） */
data class CmdAck(
    val accepted: Boolean,
    val cmdId: String? = null,
    /** 拒绝原因（如 estop_locked / param_out_of_range），必须原样展示给用户（NFR 可读原因） */
    val reason: String? = null,
)

/**
 * 设备连接请求（连接页「连接设备」）——连接即绑定，5 个字段缺一不可
 * （`POST /api/v1/devices/bind`，见 docs/后端接口设计文档.md 第 4.2 节）：
 *
 * - [username] / [password]：**登录后带入**（跳过登录时为 null，字段省略；正式服务端由 Bearer 鉴权兜底）
 * - [deviceCode]：设备编号（对应请求字段 `device_code`，连接页下拉选中的设备）
 * - [bindingCode]：绑定码（对应请求字段 `binding_code`，**由用户输入**，设备铭牌上的绑定码）
 * - [deviceType]：设备类型（对应请求字段 `device_type`），默认 [com.example.myapplication.core.model.vehicle.DeviceType.TRIANGLE]
 */
data class DeviceConnectRequest(
    val username: String?,
    val password: String?,
    val deviceCode: String,
    val bindingCode: String,
    val deviceType: String = com.example.myapplication.core.model.vehicle.DeviceType.TRIANGLE,
)

/**
 * 三角警示牌小车通信接口（M01）——**手机对外（设备/服务端）的唯一通道契约**。
 * 仓库只负责一次请求-应答；状态读取时机、连接态、命令分层票据全在 VehicleViewModel（便于单测）。
 *
 * 实现分布（本文件为纯接口，不含实现）：
 * - [com.example.myapplication.core.data.device.FakeDeviceRepository]   模拟设备（演示）
 * - [com.example.myapplication.core.data.device.HttpDeviceRepository]   直连真机 192.168.4.1（旧方案）
 * - [com.example.myapplication.core.data.device.ServerDeviceRepository] 三层架构：手机 → 服务端 → 小车
 * 装配与切换见 [com.example.myapplication.di.ServiceLocator.TRANSPORT]。
 * 协议见 docs/后端接口设计文档.md。
 *
 * 约定失败语义：
 * - `Result.failure`（IO/Connect）→ 失联
 * - 超时 → 失联/结果未知（由 ViewModel 计时判定）
 * - 200 但 `accepted:false` → 设备拒绝，携带 reason
 */
interface DeviceRepository {
    /** 可选设备列表（连接页下拉选择用） */
    suspend fun listDevices(): List<DeviceSummary>

    /**
     * 连接设备（连接即绑定）：账号 + 设备编号 + **用户输入的绑定码** + 设备类型。
     *
     * 成功后只读取一次状态（之后由用户手动刷新）；失败（绑定码为空/错误、设备已被绑定、设备不存在）返回
     * `Result.failure`，携带可读原因，界面原样展示、保持未连接。
     * 直连真机（旧方案）无绑定码概念，实现为直接成功。
     */
    suspend fun connect(request: DeviceConnectRequest): Result<Unit>

    /**
     * GET /status —— 唯一事实源，仅收到有效状态才算"已连接"。
     *
     * 调用时机：连接成功后读取一次；之后仅在用户点击「刷新状态」时调用。
     */
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
     *
     * [lane]：作业车道（第几车道，1~8）；随命令下发并在 `/status` 的 `task.lane` 回显。
     */
    suspend fun startRoute(
        kind: TaskKind,
        mode: TaskMode,
        segments: List<RouteSegment>,
        light: LightMode,
        lane: Int? = null,
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
