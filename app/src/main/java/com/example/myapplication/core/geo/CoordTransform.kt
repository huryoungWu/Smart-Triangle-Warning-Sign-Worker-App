package com.example.myapplication.core.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 纯经纬度点。**core/geo 不依赖任何地图 SDK**，保证 JVM 可单测。 */
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * WGS-84 ↔ GCJ-02 坐标转换（克拉索夫斯基椭球偏移算法）。
 *
 * **为什么必须转换**：车端 GPS 模块（u-blox NEO-6M 等）输出 WGS-84，而高德底图是 GCJ-02。
 * 不做转换，小车点位会偏移几百米。
 *
 * **职责边界**：
 * - 车端 GPS 的 WGS-84 原值只存进 `DeviceSnapshot.gps.wgs84Lat/Lng`（事实源保持车端原始语义），
 *   **上图边界**才调用 [wgs84ToGcj02]。
 * - 高德定位 SDK 返回的已是 GCJ-02，**禁止**再过本转换（二次转换同样会偏移）。
 */
object CoordTransform {
    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0             // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594  // 第一偏心率平方

    /** 国外（含港澳台外）不做偏移，直接原样返回 */
    fun isOutOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** WGS-84 → GCJ-02（车端 GPS 上图前必经） */
    fun wgs84ToGcj02(p: GeoPoint): GeoPoint {
        if (isOutOfChina(p.lat, p.lng)) return p
        val (dLat, dLng) = delta(p.lat, p.lng)
        return GeoPoint(p.lat + dLat, p.lng + dLng)
    }

    /**
     * GCJ-02 → WGS-84（不动点迭代求逆，收敛到亚米级）。
     * 仅用于测试与推算基准互转；车端 GPS 上图走的是正变换 [wgs84ToGcj02]。
     */
    fun gcj02ToWgs84(p: GeoPoint): GeoPoint {
        if (isOutOfChina(p.lat, p.lng)) return p
        var wgs = p
        repeat(8) {
            val gcj = wgs84ToGcj02(wgs)
            val dLat = p.lat - gcj.lat
            val dLng = p.lng - gcj.lng
            if (abs(dLat) < 1e-9 && abs(dLng) < 1e-9) return wgs
            wgs = GeoPoint(wgs.lat + dLat, wgs.lng + dLng)
        }
        return wgs
    }

    /** 在 GCJ-02 坐标上按方位角做直线投影（dead-reckon 估算用；示意性质，误差会累积） */
    fun projectGcj02(start: GeoPoint, headingDeg: Double, distanceM: Double): GeoPoint {
        val rad = Math.toRadians(headingDeg)
        val dLat = (distanceM * cos(rad)) / 111_320.0
        val dLng = (distanceM * sin(rad)) / (111_320.0 * cos(Math.toRadians(start.lat)))
        return GeoPoint(start.lat + dLat, start.lng + dLng)
    }

    /** 两点间球面距离（米），用于轨迹防抖与"同点重合"验证 */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * 6_371_000.0 * kotlin.math.asin(sqrt(h))
    }

    private fun delta(lat: Double, lng: Double): Pair<Double, Double> {
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLat to dLng
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
