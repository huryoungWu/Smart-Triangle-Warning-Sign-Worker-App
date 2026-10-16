package com.example.myapplication.core.model.vehicle

import java.util.UUID
import kotlin.math.abs

/**
 * 路线设计与任务规格。
 *
 * 任务由**路线段序列**构成，如「向前 20 米 → 右转 90° → 向前 20 米」。
 * 发布即代表任务开始（执行页无启动按钮）。
 */

/** 路线段动作（仅「前进」与「转向」） */
enum class SegmentAction(val label: String, val unit: String, val token: String) {
    FWD("前进", "米", "fwd"),
    TURN("转向", "度", "turn"),
}

/**
 * 单个路线段：动作 + 参数。
 * 转向段的 [value] 用**有符号度数**：负 = 左转，正 = 右转。
 */
data class RouteSegment(
    val id: String = UUID.randomUUID().toString().take(8),
    val action: SegmentAction,
    val value: Double,
) {
    fun summary(): String = when (action) {
        SegmentAction.FWD -> "向前 ${fmt(value)} 米"
        SegmentAction.TURN ->
            if (value < 0) "左转 ${abs(value).toInt()}°" else "右转 ${value.toInt()}°"
    }

    private fun fmt(v: Double): String =
        if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.1f", v)
}

/** 作业车道范围（第几车道，1~8）：发布任务时必填 */
object LaneRange {
    const val MIN = 1
    const val MAX = 8

    /** 「第 N 车道」统一文案 */
    fun label(lane: Int): String = "第 $lane 车道"
}

/** 段参数范围（表单校验与设备校验同一套规则） */
object SegmentRange {
    const val MIN_M = 1.0
    const val MAX_M = 50.0
    const val MIN_DEG = 5.0
    const val MAX_DEG = 180.0
    const val MAX_SEGMENTS = 10
    const val MAX_TOTAL_M = 200.0
}

/**
 * 执行模式：
 * - [AUTO] 全自动驾驶（对应「定距布置」），段间零间隔
 * - [SEGMENT_CONFIRM] 分段确认（对应「手动遥控」），每段需人工确认后执行
 */
enum class TaskMode(val label: String, val token: String) {
    AUTO("全自动驾驶", "auto"),
    SEGMENT_CONFIRM("分段确认", "segment_confirm"),
}

/** 段执行状态（以设备回报为准） */
enum class SegmentRunState(val label: String) {
    PENDING("待执行"),
    WAITING_CONFIRM("待确认"),
    RUNNING("执行中"),
    DONE("已完成"),
    FAILED("失败"),
    SKIPPED("未执行"),
}

/** 段的执行状态与进度 */
data class SegmentState(
    val action: SegmentAction,
    val value: Double,
    val state: SegmentRunState = SegmentRunState.PENDING,
    /** 段内进度 0..1（米数/角度/秒） */
    val progress: Float = 0f,
) {
    fun summary(): String = RouteSegment(action = action, value = value).summary()

    /** 段内进度描述，如「12.0/20 米」「已转 35/90°」 */
    fun progressText(): String = when (action) {
        SegmentAction.FWD ->
            "${"%.1f".format(value * progress)}/${"%.1f".format(value)} 米"

        SegmentAction.TURN ->
            "已转 ${(abs(value) * progress).toInt()}/${abs(value).toInt()}°"
    }
}

/**
 * 任务发布表单数据（手动遥控 / 定距布置 共用）。
 */
data class TaskSpec(
    val kind: TaskKind,
    val mode: TaskMode,
    val name: String = "",
    /** 作业车道（第几车道，[LaneRange] 1~8）；null = 表单未填写（必填项） */
    val lane: Int? = null,
    val segments: List<RouteSegment> = emptyList(),
    /** 任务期间灯光（灯光与运动分离，任务结束不自动改灯） */
    val lightOnRun: LightMode = LightMode.BLINK,
    val note: String = "",
    /** 预检·人工现场确认（并入表单底部） */
    val precheck: PrecheckHuman = PrecheckHuman(),
) {
    /** 全路线行驶合计（前进段之和） */
    val totalM: Double
        get() = segments
            .filter { it.action == SegmentAction.FWD }
            .sumOf { it.value }

    /** 「第 N 车道」展示文案；未填写时为「未填写」 */
    fun laneLabel(): String = lane?.let { LaneRange.label(it) } ?: "未填写"

    /** 校验规则（纯函数，可单测）。返回空表示通过 */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val laneNo = lane
        if (laneNo == null) {
            errors.add("请填写作业车道（第几车道，${LaneRange.MIN}~${LaneRange.MAX}）")
        } else if (laneNo < LaneRange.MIN || laneNo > LaneRange.MAX) {
            errors.add("作业车道须在 ${LaneRange.MIN}~${LaneRange.MAX} 之间")
        }
        if (segments.isEmpty()) {
            errors.add("路线不能为空，请至少添加一个路段")
        }
        if (segments.size > SegmentRange.MAX_SEGMENTS) {
            errors.add("路段数量不能超过 ${SegmentRange.MAX_SEGMENTS} 段")
        }
        segments.forEachIndexed { i, s ->
            val no = i + 1
            when (s.action) {
                SegmentAction.FWD -> {
                    if (s.value < SegmentRange.MIN_M || s.value > SegmentRange.MAX_M) {
                        errors.add("第 $no 段：前进距离须在 ${SegmentRange.MIN_M}~${SegmentRange.MAX_M} 米")
                    }
                }

                SegmentAction.TURN -> {
                    if (abs(s.value) < SegmentRange.MIN_DEG || abs(s.value) > SegmentRange.MAX_DEG) {
                        errors.add("第 $no 段：转向角度须在 ${SegmentRange.MIN_DEG.toInt()}~${SegmentRange.MAX_DEG.toInt()} 度")
                    }
                }
            }
        }
        if (totalM > SegmentRange.MAX_TOTAL_M) {
            errors.add("全路线行驶合计 ${fmt(totalM)} 米，超过上限 ${SegmentRange.MAX_TOTAL_M.toInt()} 米")
        }
        if (name.length > MAX_NAME_LEN) {
            errors.add("任务名不能超过 $MAX_NAME_LEN 字")
        }
        if (note.length > MAX_NOTE_LEN) {
            errors.add("备注不能超过 $MAX_NOTE_LEN 字")
        }
        return errors
    }

    /** 路线摘要：「向前 20 米 → 右转 90° → 向前 20 米」 */
    fun routeSummary(): String =
        if (segments.isEmpty()) "（空路线）"
        else segments.joinToString(" → ") { it.summary() }

    companion object {
        const val MAX_NAME_LEN = 30
        const val MAX_NOTE_LEN = 100

        /** 「定距布置」默认：单段直达 */
        fun defaultFixed(): TaskSpec = TaskSpec(
            kind = TaskKind.FIXED_DISTANCE,
            mode = TaskMode.AUTO,
            segments = listOf(RouteSegment(action = SegmentAction.FWD, value = 20.0)),
        )

        /** 「手动遥控」默认：示例路线（向前 20 → 右转 → 向前 20 的简化版） */
        fun defaultManual(): TaskSpec = TaskSpec(
            kind = TaskKind.MANUAL,
            mode = TaskMode.SEGMENT_CONFIRM,
            segments = listOf(
                RouteSegment(action = SegmentAction.FWD, value = 20.0),
                RouteSegment(action = SegmentAction.TURN, value = 90.0),
                RouteSegment(action = SegmentAction.FWD, value = 20.0),
            ),
        )

        fun defaultFor(kind: TaskKind): TaskSpec =
            if (kind == TaskKind.MANUAL) defaultManual() else defaultFixed()

        private fun fmt(v: Double): String = if (v == v.toInt().toDouble()) v.toInt().toString()
        else String.format("%.1f", v)
    }
}
