package com.example.myapplication.core.model.vehicle

/**
 * 一次设备状态读取（GET /status）的完整结果 —— **APP 的唯一事实源**。
 * 一切"完成/停止/模式"以本快照为准，不得由 APP 侧推断（规格第 4 节·数据真实性）。
 */
data class DeviceSnapshot(
    val deviceCode: String,
    val fw: String,
    val mode: VehicleMode,
    val motion: MotionState,
    val estopLocked: Boolean,
    val stopSource: StopSource,
    val light: LightMode,
    val task: TaskInfo,
    val odo: OdoInfo,
    /**
     * 车端 GPS；null = 车端未装 GPS 模块或协议未带字段。
     * **null 时小车位置只能是行程估算，UI 不得称"地图定位"**（规格第 4 节）。
     */
    val gps: VehicleGps? = null,
    /** C 阶段：待可靠数据来源验收后开放，null 表示无来源 → UI 置灰 */
    val batteryPercent: Int? = null,
    val tilt: Boolean? = null,
    val obstacle: Boolean? = null,
    /** 设备侧时间戳（ms） */
    val deviceTs: Long = 0L,
    /** 手机侧收到时间（ms），用于"最后更新时间"与"非实时"判断 */
    val receivedAt: Long = System.currentTimeMillis(),
)

/**
 * 设备摘要（连接页设备选择用）。
 * 一台或多台可选，每台显示：是否开始（已上电）、是否可用、电量。
 */
data class DeviceSummary(
    val id: String,
    val name: String,
    /** 是否开始（已上电/启动） */
    val started: Boolean = false,
    /** 是否可用（可用于作业） */
    val available: Boolean = true,
    /** 电量百分比，-1 表示无来源 */
    val batteryPercent: Int = -1,
) {
    val batteryText: String
        get() = if (batteryPercent < 0) "未知" else "$batteryPercent%"
}

/** 车端 GPS 模块状态 */
enum class GpsFixState { FIXED, NO_FIX, ABSENT }

/**
 * 车端上报 GPS（协议预留 lat/lng 字段）。
 *
 * **坐标系为 WGS-84**（车端 NMEA/模块原始输出），字段名即坐标系声明；
 * 上图前必须经 `CoordTransform.wgs84ToGcj02`，禁止与 GCJ-02 混存。
 */
data class VehicleGps(
    val wgs84Lat: Double,
    val wgs84Lng: Double,
    val accuracyM: Float? = null,
    val speedMps: Double? = null,
    val headingDeg: Double? = null,
    val state: GpsFixState = GpsFixState.FIXED,
    val at: Long = 0L,
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
    /** 分段进度（设备回报）；旧固件无此字段时为空列表，UI 退化为整体进度 */
    val segments: List<SegmentState> = emptyList(),
    /** 当前航向（度），转向段推进可见 */
    val headingDeg: Double = 0.0,
    /** 作业车道（第几车道）：设备回显发布时下发的 `lane`；无则 null（旧固件/未下发） */
    val lane: Int? = null,
) {
    /** 当前段下标（RUNNING/WAITING_CONFIRM），无则已完成段数 */
    val segmentIndex: Int
        get() = segments.indexOfFirst {
            it.state == SegmentRunState.RUNNING || it.state == SegmentRunState.WAITING_CONFIRM
        }.let { if (it == -1) segments.count { s -> s.state == SegmentRunState.DONE } else it }

    /** 当前段是否为「待确认」（分段确认模式的间隙） */
    val waitingConfirm: Boolean
        get() = segments.any { it.state == SegmentRunState.WAITING_CONFIRM }

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
    // ── 任务规格与上传状态（UPLOAD_MODE=HTTP 时上传服务端；STUB 时仅本机）──
    val name: String? = null,
    val mode: TaskMode? = null,
    /** 发布时的作业车道（第几车道）；旧记录无此字段为 null */
    val lane: Int? = null,
    /** 发布时的路线原样保存 */
    val route: List<RouteSegment> = emptyList(),
    /** local=仅本机 / pending=待上传 / uploaded=已上传（HTTP：服务端单号；STUB：模拟单号）/ failed=上传失败 */
    val uploadState: String = UPLOAD_LOCAL,
    val uploadedAt: Long? = null,
    val serverId: String? = null,
) {
    companion object {
        /** 记录始终在本机手机留一份；STUB 模式下不得宣称已上传平台，HTTP 模式展示真实服务端单号 */
        const val LOCAL_ONLY_NOTICE = "仅本机保存"

        const val UPLOAD_LOCAL = "local"
        const val UPLOAD_PENDING = "pending"
        const val UPLOAD_UPLOADED = "uploaded"
        const val UPLOAD_FAILED = "failed"

        /** [serverUpload]=true（UPLOAD_MODE=HTTP）时展示"已上传服务端"；默认 false 保持 STUB 安全文案 */
        fun uploadStateLabel(v: String, serverUpload: Boolean = false): String = when (v) {
            UPLOAD_LOCAL -> "仅本机保存"
            UPLOAD_PENDING -> "已保存本机 · 待上传"
            UPLOAD_UPLOADED -> if (serverUpload) "已保存本机 · 已上传服务端" else "已保存本机 · 已模拟上传"
            UPLOAD_FAILED -> "上传失败 · 可重试"
            else -> v
        }

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
