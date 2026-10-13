package com.example.myapplication.core.model.risk

import java.util.UUID

/** 事故类型 */
enum class AccidentType(val label: String) {
    REAR_END("追尾事故"),
    ROLLOVER("车辆侧翻"),
    BREAKDOWN("车辆抛锚"),
    CONSTRUCTION("道路施工"),
    DEBRIS("散落物占道"),
    OTHER("其他风险"),
}

/**
 * 道路风险事件（前方道路风险提示）。
 *
 * ⚠️ 数据真实性：当前为**演示数据**（无真实事故数据源），
 * 照片/视频为占位，接通真实数据源后替换 [com.example.myapplication.core.data.risk.RiskRepository] 实现。
 */
data class Accident(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val type: AccidentType,
    /** 距用户的直线距离（米），列表按此从近到远排序 */
    val distanceM: Double,
    /** 位置（GCJ-02，与高德地图一致） */
    val lat: Double,
    val lng: Double,
    val occurredAt: Long,
    val description: String,
    /** 是否有现场照片（演示：占位图） */
    val hasPhoto: Boolean = true,
    /** 是否有现场视频（演示：占位播放器） */
    val hasVideo: Boolean = false,
) {
    val distanceText: String
        get() = if (distanceM < 1000) "${distanceM.toInt()} 米" else String.format("%.1f 公里", distanceM / 1000)
}

/** 风险提示页的距离半径（公里），用户可自行设置 */
object RiskRadius {
    const val MIN_KM = 1
    const val MAX_KM = 20
    const val DEFAULT_KM = 5
}
