package com.example.myapplication.core.model.vehicle

import com.example.myapplication.core.model.AppDialog

/** 预检·人工现场确认（M03）：系统不检测障碍物与道路安全，全靠人工核对 */
data class PrecheckHuman(
    val heading: Boolean = false,
    val passage: Boolean = false,
    val bystanders: Boolean = false,
    val distance: Boolean = false,
) {
    val allChecked: Boolean get() = heading && passage && bystanders && distance

    fun uncheckedItems(): List<String> = buildList {
        if (!heading) add("车头朝向是否正确")
        if (!passage) add("前方通道是否无障碍")
        if (!bystanders) add("周边是否有人员")
        if (!distance) add("目标距离是否合适")
    }
}

/** 待人工核验的任务（M05/M06）：设备完成与人工核验分开 */
data class PendingVerify(
    val recordId: String,
    val kind: TaskKind,
    val source: TaskSource,
    val targetM: Double,
    val traveledM: Double,
    val finishedAt: Long,
)

/**
 * 全局 UI 状态聚合（由 VehicleViewModel 持有，Activity 级共享）。
 */
data class VehicleUiState(
    val connection: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val snapshot: DeviceSnapshot? = null,
    /** 最近一次成功收到设备状态的时间，用于"最后更新时间"与"非实时"标注（M02/M09） */
    val lastUpdateAt: Long = 0L,
    /** 进行中的命令票据（命令五层分层），同一时刻仅一条运动类命令 */
    val activeTicket: CommandTicket? = null,
    /** 手动遥控按住中（M04） */
    val holdingMove: Boolean = false,
    val precheckHuman: PrecheckHuman = PrecheckHuman(),
    val deployPreset: DistancePreset = DistancePreset.M1,
    val lightSetting: LightMode = LightMode.OFF,
    /** 发出停止/急停后设备尚未确认（M08："停止未确认，需现场核查"） */
    val stopUnconfirmed: Boolean = false,
    val pendingVerify: PendingVerify? = null,
    /** 恢复待命前的人工现场核查确认（M08） */
    val pendingResumeConfirm: Boolean = false,
    val dialog: AppDialog? = null,
) {
    /** 数据是否过期（>3 秒），过期须标注"非实时"（M09） */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        lastUpdateAt == 0L || now - lastUpdateAt > 3000L

    /** 运动类指令是否可下发（M04 自动任务禁止手动抢占；失联/急停锁定禁用） */
    val motionCommandsEnabled: Boolean
        get() = connection == ConnectionPhase.CONNECTED &&
            snapshot?.estopLocked != true &&
            snapshot?.mode != VehicleMode.AUTO &&
            snapshot?.mode != VehicleMode.SAFE_STOP

    /** 布置类任务是否可启动（预检必需项 + 连接 + 无进行中任务） */
    val taskStartEnabled: Boolean
        get() = connection == ConnectionPhase.CONNECTED &&
            snapshot?.estopLocked != true &&
            snapshot?.mode != VehicleMode.SAFE_STOP &&
            snapshot?.task?.state != TaskRunState.RUNNING &&
            activeTicket == null

    /** 设备条件检查（M03）：至少在线、静止、未急停锁定、无其他运行任务 */
    fun deviceConditions(): List<Pair<String, Boolean>> {
        val s = snapshot
        return listOf(
            "设备在线" to (connection == ConnectionPhase.CONNECTED && s != null),
            "车辆静止" to (s?.motion == MotionState.IDLE),
            "未急停锁定" to (s?.estopLocked == false),
            "无其他运行任务" to (s?.task?.state != TaskRunState.RUNNING),
        )
    }

    fun deviceConditionFailureReasons(): List<String> = deviceConditions()
        .filterNot { it.second }
        .map { "不满足：${it.first}" }
}
