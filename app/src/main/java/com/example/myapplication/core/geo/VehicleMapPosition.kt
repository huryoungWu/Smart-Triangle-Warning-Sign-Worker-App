package com.example.myapplication.core.geo

import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.GpsFixState

/**
 * 小车在地图上的位置语义 —— **数据真实性的唯一裁决点**（规格第 4 节）。
 *
 * 规则：
 * 1. 只有车端 GPS FIX 才能称为「小车地图位置」
 * 2. 无 GPS 一律走行程估算，文案必含「估算」与「非地图定位」
 * 3. 无锚点时返回 Unknown（**宁缺毋滥，不硬造点**）
 */
sealed interface VehicleMapPoint {

    /** 车端 GPS FIX：WGS-84 已转 GCJ-02，可称「小车（GPS）」 */
    data class Gps(val gcj02: GeoPoint, val accuracyM: Float?, val at: Long) : VehicleMapPoint

    /** 行程估算示意点：**非地图定位** */
    data class Estimated(
        val gcj02: GeoPoint,
        val basis: String,
        val anchor: GeoPoint,
        val at: Long,
    ) : VehicleMapPoint

    data object Unknown : VehicleMapPoint
}

/** 估算锚点：有车 GPS 用最后 GPS 点，否则用任务/运动起点的手机定位 */
data class EstimateAnchor(
    val gcj02: GeoPoint,
    val traveledAtAnchorM: Double,
    val headingDeg: Double,
    val label: String,
)

/** 估算点文案（单测锁死，防 UI 冒充地图定位） */
const val ESTIMATE_BASIS = "由车轮编码器估算行驶距离推算 · 非地图定位"

/**
 * 判定顺序：
 * 1. 无快照 → Unknown
 * 2. `gps.state == FIXED` → Gps（唯一合法的小车地图位置，此处做 WGS-84→GCJ-02）
 * 3. 其余且有锚点 → Estimated（从锚点沿 heading 直线投影 `traveledM - traveledAtAnchorM` 米）
 * 4. 无锚点 → Unknown
 */
fun resolveVehicleMapPoint(
    snapshot: DeviceSnapshot?,
    anchor: EstimateAnchor?,
    now: Long = System.currentTimeMillis(),
): VehicleMapPoint {
    if (snapshot == null) return VehicleMapPoint.Unknown

    val gps = snapshot.gps
    if (gps != null && gps.state == GpsFixState.FIXED) {
        // 唯一允许调用坐标转换的地方：车端 GPS 是 WGS-84
        val gcj02 = CoordTransform.wgs84ToGcj02(GeoPoint(gps.wgs84Lat, gps.wgs84Lng))
        return VehicleMapPoint.Gps(gcj02, gps.accuracyM, if (gps.at > 0) gps.at else now)
    }

    if (anchor == null) return VehicleMapPoint.Unknown

    val deltaM = (snapshot.odo.traveledM - anchor.traveledAtAnchorM).coerceAtLeast(0.0)
    val projected = CoordTransform.projectGcj02(anchor.gcj02, anchor.headingDeg, deltaM)
    return VehicleMapPoint.Estimated(
        gcj02 = projected,
        basis = ESTIMATE_BASIS,
        anchor = anchor.gcj02,
        at = now,
    )
}

/** 地图渲染模型（全部 GCJ-02，SDK 无关） */
data class MapRenderModel(
    val phone: GeoPoint? = null,
    val phoneTrail: List<GeoPoint> = emptyList(),
    val vehicleGps: GeoPoint? = null,
    val vehicleGpsTrail: List<GeoPoint> = emptyList(),
    val vehicleEstimate: GeoPoint? = null,
    val estimateAnchor: GeoPoint? = null,
    val followPhone: Boolean = false,
    val vehicleHasGps: Boolean = false,
)
