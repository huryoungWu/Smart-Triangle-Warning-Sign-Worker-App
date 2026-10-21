package com.example.myapplication.core.model.deployment

import com.example.myapplication.core.model.vehicle.LaneRange
import kotlin.math.abs

/** 锥桶协同布设领域模型（纯数据，可单测；协议字段见 docs/锥桶协同布设功能需求规格说明书.md 第 9 章） */

/**
 * 锥桶设备类型（协议字段 `device_type`，统一 snake_case）：
 * 主桶 = `master_cone`，从桶 = `slave_cone`。
 */
enum class DeploymentDeviceType(val token: String, val label: String) {
    MASTER_CONE("master_cone", "主桶"),
    SLAVE_CONE("slave_cone", "从桶"),
    ;

    companion object {
        fun fromToken(v: String?): DeploymentDeviceType? = when (v?.lowercase()) {
            "master_cone" -> MASTER_CONE
            "slave_cone" -> SLAVE_CONE
            else -> null
        }
    }
}

enum class DeploymentState(val token: String, val label: String) {
    READY("ready", "准备中"),
    STARTING("starting", "启动中"),
    RUNNING("running", "执行中"),
    PAUSED("paused", "已暂停"),
    PENDING_VERIFY("pending_verify", "待现场核验"),
    VERIFY_FAILED("verify_failed", "核验异常"),
    COMPLETED("completed", "已完成"),
    ABORTED("aborted", "已终止"),
    FAILED("failed", "失败"),
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == ABORTED || this == FAILED
    val isActive: Boolean get() = !isTerminal

    companion object {
        fun fromToken(v: String?): DeploymentState = entries.firstOrNull { it.token == v } ?: READY
    }
}

enum class DeploymentResult(val token: String, val label: String) {
    COMPLETED("completed", "已完成"),
    COMPLETED_WITH_EXCEPTION("completed_with_exception", "已完成（带异常）"),
    ;

    companion object {
        fun fromToken(v: String?): DeploymentResult? = entries.firstOrNull { it.token == v }
    }
}

enum class DeploymentPhase(val token: String, val label: String) {
    NOT_STARTED("not_started", "待启动"),
    START_SENT("start_sent", "启动指令已发送"),
    START_ACKED("start_acked", "已接收"),
    MOVING("moving", "移动中"),
    STOPPED("stopped", "已暂停/已停止"),
    ARRIVED("arrived", "已到达（待核验）"),
    NO_MOVE("no_move", "无需移动"),
    SKIPPED_MANUAL("skipped_manual", "人工跳过（待处置）"),
    FAILED("failed", "启动失败/异常"),
    ;

    companion object {
        fun fromToken(v: String?): DeploymentPhase = entries.firstOrNull { it.token == v } ?: NOT_STARTED
    }
}

enum class CommandResultState(val token: String, val label: String) {
    NONE("none", "未发送"),
    SENT("sent", "已发送"),
    ACKED("acked", "已确认"),
    REJECTED("rejected", "被拒绝"),
    TIMEOUT("timeout", "超时/结果未知"),
    DUPLICATE("duplicate", "重复指令（已执行）"),
    ;

    companion object {
        fun fromToken(v: String?): CommandResultState = entries.firstOrNull { it.token == v } ?: NONE
    }
}

enum class ReportResultState(val token: String, val label: String) {
    NONE("none", "未上报"),
    WAITING("waiting", "等待达到 d"),
    SENT("sent", "已发送"),
    ACKED("acked", "已确认"),
    LOST("lost", "上报丢失"),
    TIMEOUT("timeout", "上报超时"),
    NOT_APPLICABLE("not_applicable", "不适用"),
    ;

    companion object {
        fun fromToken(v: String?): ReportResultState = entries.firstOrNull { it.token == v } ?: NONE
    }
}

enum class VerifyResultState(val token: String, val label: String) {
    NOT_DONE("not_done", "未核验"),
    PASS("pass", "核验通过"),
    FAIL("fail", "核验异常"),
    ;

    companion object {
        fun fromToken(v: String?): VerifyResultState = entries.firstOrNull { it.token == v } ?: NOT_DONE
    }
}

enum class SkipDisposition(val token: String, val label: String) {
    PLACED_MANUALLY("placed_manually", "人工摆放到位"),
    REMOVED_FROM_ROUTE("removed_from_route", "移出路线"),
    NOT_REQUIRED("not_required", "不参与本次布设"),
    ;

    companion object {
        fun fromToken(v: String?): SkipDisposition? = entries.firstOrNull { it.token == v }
    }
}

object DeploymentLimits {
    const val N_MAX = 5
    const val L_MIN = 1.0
    const val L_MAX = 200.0
    const val D_MAX = 50.0
    const val PRECISION = 0.1
    const val MAX_NAME_LEN = 30
    const val MAX_NOTE_LEN = 100

    fun hasValidPrecision(v: Double): Boolean =
        abs(v * 10.0 - Math.round(v * 10.0)) < 1e-6
}

/** 设备选择用摘要（设备台账）；字段名与协议 `device_code` / `device_type` / `binding_code` 对齐 */
data class DeploymentDeviceSummary(
    val deviceCode: String,
    val name: String,
    val deviceType: DeploymentDeviceType?,
    val bindingCode: String? = null,
    val setId: String? = null,
    val masterCode: String? = null,
    val online: Boolean = true,
    val available: Boolean = true,
    val occupied: Boolean = false,
    val occupiedByEvent: String? = null,
    val activeTaskId: String? = null,
    val estopLocked: Boolean = false,
    val batteryPercent: Int = -1,
) {
    val batteryText: String get() = if (batteryPercent < 0) "未知" else "$batteryPercent%"

    val selectable: Boolean
        get() = deviceType != null && online && available && !occupied && activeTaskId == null && !estopLocked

    fun unavailableReason(): String? = when {
        deviceType == null -> "设备类型未知（不是锥桶设备）"
        estopLocked -> "急停锁定中"
        // 布设事件占用优先于单设备任务展示，避免把“被事件占用”误写成“正在执行任务”
        occupied -> occupiedByEvent?.let { "已被事件 $it 占用" } ?: "已被其他事件占用"
        activeTaskId != null -> "正在执行任务"
        !online -> "设备离线"
        !available -> "设备不可用"
        else -> null
    }
}

data class DeviceSet(
    val setId: String?,
    val master: DeploymentDeviceSummary?,
    val slaves: List<DeploymentDeviceSummary>,
)

data class SelectedSlave(
    val device: DeploymentDeviceSummary,
    val order: Int,
)

data class TargetPreview(
    val deviceType: DeploymentDeviceType,
    val deviceCode: String,
    val order: Int,
    val targetM: Double,
) {
    val orderLabel: String get() = if (deviceType == DeploymentDeviceType.MASTER_CONE) "主桶" else "从 $order"
}

/**
 * 布设草稿（向导内本地状态）。
 *
 * L/d 用字符串保存输入，校验时解析；`target(slave k) = L - k*d`。
 */
data class DeploymentDraft(
    val name: String = "",
    val lane: Int? = null,
    val note: String = "",
    val master: DeploymentDeviceSummary? = null,
    val slaves: List<SelectedSlave> = emptyList(),
    val lText: String = "",
    val dText: String = "",
    val precheckSafety: Boolean = false,
    val precheckOrder: Boolean = false,
) {
    val n: Int get() = slaves.size
    val L: Double? get() = lText.trim().replace('−', '-').toDoubleOrNull()
    val d: Double? get() = dText.trim().replace('−', '-').toDoubleOrNull()

    fun targetFor(order: Int): Double? {
        val l = L ?: return null
        val interval = d ?: return null
        return round1(l - order * interval)
    }

    val preview: List<TargetPreview>
        get() {
            val l = L ?: return emptyList()
            val interval = d ?: return emptyList()
            return buildList {
                master?.let { add(TargetPreview(DeploymentDeviceType.MASTER_CONE, it.deviceCode, 0, round1(l))) }
                slaves.sortedBy { it.order }.forEach { s ->
                    add(TargetPreview(DeploymentDeviceType.SLAVE_CONE, s.device.deviceCode, s.order, round1(l - s.order * interval)))
                }
            }
        }

    /** 步骤 1：设备与事件信息 */
    fun step1Errors(): List<String> {
        val errors = mutableListOf<String>()
        if (name.length > DeploymentLimits.MAX_NAME_LEN) errors.add("事件名不能超过 ${DeploymentLimits.MAX_NAME_LEN} 字")
        if (note.length > DeploymentLimits.MAX_NOTE_LEN) errors.add("备注不能超过 ${DeploymentLimits.MAX_NOTE_LEN} 字")
        val laneNo = lane
        if (laneNo == null) errors.add("请填写作业车道（${LaneRange.MIN}~${LaneRange.MAX}）")
        else if (laneNo < LaneRange.MIN || laneNo > LaneRange.MAX) errors.add("作业车道须在 ${LaneRange.MIN}~${LaneRange.MAX} 之间")
        if (master == null) errors.add("请选择主桶")
        if (n < 1) errors.add("请至少选择 1 只从桶")
        if (n > DeploymentLimits.N_MAX) errors.add("从桶数量不能超过 ${DeploymentLimits.N_MAX}")
        val orders = slaves.map { it.order }
        if (orders.sorted() != (1..n).toList()) errors.add("从桶顺序必须为连续的 1..$n")
        if (slaves.map { it.device.deviceCode }.toSet().size != slaves.size) errors.add("从桶不能重复")
        if (master != null && slaves.any { it.device.deviceCode == master.deviceCode }) errors.add("主桶不能同时作为从桶")
        if (master != null && master.deviceType != DeploymentDeviceType.MASTER_CONE) {
            errors.add("所选主桶 device_type 必须为 master_cone")
        }
        if (slaves.any { it.device.deviceType != DeploymentDeviceType.SLAVE_CONE }) {
            errors.add("所选从桶中存在 device_type 不为 slave_cone 的设备")
        }
        if (master != null && master.bindingCode.isNullOrBlank()) {
            errors.add("主桶 ${master.deviceCode} 缺少 binding_code，请重新绑定")
        }
        slaves.forEach { s ->
            if (s.device.bindingCode.isNullOrBlank()) {
                errors.add("从桶 ${s.device.deviceCode} 缺少 binding_code，请重新绑定")
            }
            val reason = s.device.unavailableReason()
            if (reason != null) errors.add("从桶 ${s.device.deviceCode}：$reason")
        }
        master?.unavailableReason()?.let { errors.add("主桶 ${master.deviceCode}：$it") }
        return errors
    }

    /** 步骤 2：参数 L/d 与预览 */
    fun step2Errors(): List<String> {
        val errors = mutableListOf<String>()
        val l = L
        val interval = d
        if (l == null) errors.add("请输入主桶目标行程 L（米）")
        if (interval == null) errors.add("请输入启动间隔 d（米）")
        if (l != null && interval != null) {
            if (!DeploymentLimits.hasValidPrecision(l)) errors.add("L 精度最多 0.1 m")
            if (!DeploymentLimits.hasValidPrecision(interval)) errors.add("d 精度最多 0.1 m")
            if (l < DeploymentLimits.L_MIN || l > DeploymentLimits.L_MAX) {
                errors.add("L 必须在 ${DeploymentLimits.L_MIN.toInt()}~${DeploymentLimits.L_MAX.toInt()} 米")
            }
            if (interval <= 0.0 || interval > DeploymentLimits.D_MAX) {
                errors.add("启动间隔 d 必须大于 0 且不超过 ${DeploymentLimits.D_MAX.toInt()} 米")
            }
            val need = n * interval
            if (l < need - 1e-9) {
                errors.add("L 必须 ≥ N×d（当前 ${fmt(l)} < ${n}×${fmt(interval)}=${fmt(need)}）")
            }
        }
        if (n == 0) errors.add("请先在步骤 1 选择从桶")
        return errors
    }

    fun validate(): List<String> = step1Errors() + step2Errors()

    val precheckDone: Boolean get() = precheckSafety && precheckOrder

    fun canStart(): Boolean = validate().isEmpty() && precheckDone

    fun laneLabel(): String = lane?.let { LaneRange.label(it) } ?: "未填写"

    companion object {
        fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
        fun fmt(v: Double): String =
            if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.1f", v)
    }
}

data class DeploymentParams(
    val L: Double,
    val d: Double,
    val N: Int,
)

data class DeploymentCommandState(
    val state: CommandResultState = CommandResultState.NONE,
    val commandId: String? = null,
    val sentAt: Long? = null,
    val ackedAt: Long? = null,
    val reason: String? = null,
)

data class DeploymentReportState(
    val state: ReportResultState = ReportResultState.NONE,
    val seq: Int = 0,
    val sentAt: Long? = null,
    val ackedAt: Long? = null,
)

data class DeploymentTriggerState(
    val to: String?,
    val seq: Int,
    val state: String,
    val commandId: String?,
)

data class DeploymentSkip(
    val skipped: Boolean = false,
    val reason: String = "",
    val disposition: SkipDisposition? = null,
    val note: String = "",
    val skippedAt: Long? = null,
    val skippedBy: String? = null,
    val stopConfirmed: Boolean? = null,
)

data class DeploymentVerifyState(
    val state: VerifyResultState = VerifyResultState.NOT_DONE,
    val note: String? = null,
    val verifiedAt: Long? = null,
)

data class DeploymentAnomaly(
    val code: String,
    val message: String,
    val deviceCode: String? = null,
    val detectedAt: Long = 0L,
) {
    val label: String get() = message.ifBlank { code }
}

data class DeploymentDeviceState(
    val deviceCode: String,
    val name: String,
    val deviceType: DeploymentDeviceType?,
    val bindingCode: String? = null,
    val order: Int,
    val online: Boolean,
    val targetM: Double,
    val traveledM: Double,
    val phase: DeploymentPhase,
    val startCommand: DeploymentCommandState = DeploymentCommandState(),
    val report: DeploymentReportState = DeploymentReportState(),
    val trigger: DeploymentTriggerState? = null,
    val reachedInterval: Boolean = false,
    val arrived: Boolean = false,
    val skip: DeploymentSkip? = null,
    val verify: DeploymentVerifyState = DeploymentVerifyState(),
    val anomaly: DeploymentAnomaly? = null,
) {
    val progress: Float
        get() = if (targetM <= 0.001) 1f else (traveledM / targetM).toFloat().coerceIn(0f, 1f)

    val isMaster: Boolean get() = deviceType == DeploymentDeviceType.MASTER_CONE || order == 0
    val orderLabel: String get() = if (isMaster) "主桶" else "从 $order"
    val started: Boolean get() = phase != DeploymentPhase.NOT_STARTED
    val skipped: Boolean get() = skip?.skipped == true || phase == DeploymentPhase.SKIPPED_MANUAL
}

data class DeploymentNextExpected(
    val type: String,
    val deviceCode: String,
    val thresholdM: Double,
    val afterReportTrigger: String?,
    val state: String,
)

data class DeploymentStep(
    val seq: Int,
    val type: String,
    val from: String?,
    val to: String?,
    val cause: String? = null,
    val causeReportId: String? = null,
    val commandId: String? = null,
    val reportId: String? = null,
    val state: String,
    val at: Long = 0L,
)

data class DeploymentEvent(
    val deploymentId: String,
    val eventId: String? = null,
    val name: String,
    val lane: Int?,
    val state: DeploymentState,
    val result: DeploymentResult? = null,
    val params: DeploymentParams,
    val devices: List<DeploymentDeviceState>,
    val nextExpected: DeploymentNextExpected? = null,
    val steps: List<DeploymentStep> = emptyList(),
    val anomalies: List<DeploymentAnomaly> = emptyList(),
    val startedAt: Long? = null,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null,
) {
    val master: DeploymentDeviceState? get() = devices.firstOrNull { it.isMaster }
    val slaves: List<DeploymentDeviceState> get() = devices.filter { !it.isMaster }.sortedBy { it.order }
    val ordered: List<DeploymentDeviceState> get() = listOfNotNull(master) + slaves
    val terminal: Boolean get() = state.isTerminal
    val hasSkip: Boolean get() = devices.any { it.skipped }
    val arrivedCount: Int get() = devices.count { it.arrived }
    val verifiedCount: Int get() = devices.count { it.verify.state == VerifyResultState.PASS }
    val allArrivedOrDisposed: Boolean
        get() = devices.all { it.arrived || (it.skipped && it.skip?.disposition != null) }
    val allVerified: Boolean get() = devices.all { it.verify.state == VerifyResultState.PASS }

    fun device(code: String): DeploymentDeviceState? = devices.firstOrNull { it.deviceCode == code }
}

data class DeploymentRecordSummary(
    val deploymentId: String,
    val name: String,
    val lane: Int?,
    val state: DeploymentState,
    val result: DeploymentResult?,
    val L: Double,
    val d: Double,
    val N: Int,
    val masterCode: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val hasSkip: Boolean,
)
