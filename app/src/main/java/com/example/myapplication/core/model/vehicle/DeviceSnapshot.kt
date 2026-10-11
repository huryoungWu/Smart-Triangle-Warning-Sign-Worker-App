package com.example.myapplication.core.model.vehicle

/**
 * 一次设备状态读取（GET /status）的完整结果 —— **APP 的唯一事实源**。
 * 一切"完成/停止/模式"以本快照为准，不得由 APP 侧推断（规格第 4 节·数据真实性）。
 */
data class DeviceSnapshot(
    val deviceId: String,
    val fw: String,
    val mode: VehicleMode,
    val motion: MotionState,
    val estopLocked: Boolean,
    val stopSource: StopSource,
    val light: LightMode,
    val task: TaskInfo,
    val odo: OdoInfo,
    /** C 阶段：待可靠数据来源验收后开放，null 表示无来源 → UI 置灰 */
    val batteryPercent: Int? = null,
    val tilt: Boolean? = null,
    val obstacle: Boolean? = null,
    /** 设备侧时间戳（ms） */
    val deviceTs: Long = 0L,
    /** 手机侧收到时间（ms），用于"最后更新时间"与"非实时"判断 */
    val receivedAt: Long = System.currentTimeMillis(),
)

data class TaskInfo(
    val id: String? = null,
    val kind: TaskKind? = null,
    val source: TaskSource = TaskSource.APP,
    val targetM: Double = 0.0,
    val traveledM: Double = 0.0,
    val state: TaskRunState = TaskRunState.IDLE,
    /** 一键布置阶段标识（lighting/countdown/moving/parked） */
    val stage: String? = null,
    val resultNote: String? = null,
) {
    val progress: Float
        get() = if (targetM <= 0.0) 0f else (traveledM / targetM).toFloat().coerceIn(0f, 1f)
}

/** 运行数据（M09）：行程仅由车轮编码器估算 */
data class OdoInfo(
    val leftPulse: Long = 0L,
    val rightPulse: Long = 0L,
    val traveledM: Double = 0.0,
    val speedMps: Double = 0.0,
    val diffPulse: Long = 0L,
)

/** 命令票据（规格第 4 节·命令状态分层） */
data class CommandTicket(
    val id: String,
    val kind: CommandKind,
    val phase: CommandPhase,
    val sentAt: Long,
    val ackedAt: Long? = null,
    val doneAt: Long? = null,
    val note: String? = null,
)

/** 作业记录（M09）：设备结果与人工核验分开记录 */
data class WorkRecord(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long,
    val kind: TaskKind,
    val source: TaskSource,
    val targetM: Double,
    /** 编码器估算行驶距离 */
    val traveledM: Double,
    /** 设备侧结果："done"|"failed"|"canceled"|"link_loss"|"unknown" */
    val deviceResult: String,
    val deviceResultNote: String? = null,
    /** 人工现场核验："pass"|"fail"|"not_done" */
    val verifyResult: String = VERIFY_NOT_DONE,
    val verifyNote: String? = null,
    val abnormalNote: String? = null,
) {
    companion object {
        /** M09：记录仅保存在本机手机，不得宣称已上传平台 */
        const val LOCAL_ONLY_NOTICE = "仅本机保存"

        const val VERIFY_PASS = "pass"
        const val VERIFY_FAIL = "fail"
        const val VERIFY_NOT_DONE = "not_done"

        const val RESULT_DONE = "done"
        const val RESULT_FAILED = "failed"
        const val RESULT_CANCELED = "canceled"
        const val RESULT_LINK_LOSS = "link_loss"
        const val RESULT_UNKNOWN = "unknown"

        fun deviceResultLabel(value: String): String = when (value) {
            RESULT_DONE -> "设备报告已完成"
            RESULT_FAILED -> "设备报告失败"
            RESULT_CANCELED -> "人工停止／已取消"
            RESULT_LINK_LOSS -> "失联保护停车"
            RESULT_UNKNOWN -> "结果未知"
            else -> value
        }

        fun verifyResultLabel(value: String): String = when (value) {
            VERIFY_PASS -> "核验通过"
            VERIFY_FAIL -> "核验异常"
            VERIFY_NOT_DONE -> "未核验"
            else -> value
        }
    }
}
