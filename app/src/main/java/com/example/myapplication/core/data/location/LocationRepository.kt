package com.example.myapplication.core.data.location

import com.example.myapplication.core.geo.GeoPoint
import kotlinx.coroutines.flow.Flow

/**
 * 手机定位结果。**坐标系声明在字段名里**：高德定位 SDK 返回即 GCJ-02，
 * 上图**不做任何转换**（二次转换同样会偏移几百米）。
 */
data class PhoneFix(
    val gcj02Lat: Double,
    val gcj02Lng: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val at: Long = System.currentTimeMillis(),
    val sourceLabel: String = "GPS",
) {
    val point: GeoPoint get() = GeoPoint(gcj02Lat, gcj02Lng)
}

/** 轨迹点带来源标签，渲染层按来源分色/分线型（数据真实性要求） */
data class TrackPoint(val point: GeoPoint, val source: TrackSource, val at: Long)

enum class TrackSource { PHONE, VEHICLE_GPS, VEHICLE_ESTIMATE }

/**
 * 手机定位接口。与 `DeviceRepository` 同款双实现（Fake / AMap），
 * 由 `ServiceLocator.LOCATION_SOURCE` 一行开关选择。
 *
 * **权限与隐私合规**：[start] 只能在用户同意隐私政策且已授予定位权限后调用；
 * 实现类不得在构造函数里实例化定位 SDK。
 */
interface LocationRepository {
    /** 持续定位流；replay=1，仅在 start() 后发射 */
    val fixes: Flow<PhoneFix>

    /** 幂等；权限与隐私同意都就绪后由地图页调用 */
    fun start()

    fun stop()
}
