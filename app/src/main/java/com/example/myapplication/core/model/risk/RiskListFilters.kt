package com.example.myapplication.core.model.risk

import com.example.myapplication.core.util.TimeFormat

/** 事故列表时间范围（默认「今天」） */
enum class RiskTimeScope(val label: String) {
    TODAY("今天"),
    ALL("全部"),
}

/** 事故列表排序方式（默认按距离近→远）；[orderHint] 用于表头提示当前顺序 */
enum class RiskSort(val label: String, val orderHint: String) {
    DISTANCE("按距离", "由近到远"),
    RECENT("按时间", "由新到旧"),
}

/**
 * 事故列表口径（风险页专用，与 UI 解耦便于单测）：
 * 1. **只保留带占用车道（第几车道）的条目** —— 列表每行都要显示「第 N 车道」，
 *    数据源给不出车道的一律先删掉（如「长下坡事故多发路段」这类无具体车道的条目）
 * 2. 按 [scope] 过滤发生时间（TODAY = 当天，按北京时间判定）
 * 3. 按 [sort] 排序：DISTANCE 从近到远 / RECENT 从新到旧
 */
fun List<Accident>.applyRiskView(
    scope: RiskTimeScope,
    sort: RiskSort,
    now: Long = System.currentTimeMillis(),
): List<Accident> {
    val withLane = filter { it.lane != null }
    val byTime = when (scope) {
        RiskTimeScope.TODAY -> withLane.filter { TimeFormat.isToday(it.occurredAt, now) }
        RiskTimeScope.ALL -> withLane
    }
    return when (sort) {
        RiskSort.DISTANCE -> byTime.sortedBy { it.distanceM }
        RiskSort.RECENT -> byTime.sortedByDescending { it.occurredAt }
    }
}
