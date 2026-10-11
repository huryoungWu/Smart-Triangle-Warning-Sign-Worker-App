package com.example.myapplication

import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标系转换单测（关键：车端 GPS 是 WGS-84，高德底图是 GCJ-02）。
 *
 * 三重防呆：
 * - 偏移量为 0 → 说明没转换（点位会偏几百米）
 * - 偏移量 > 0.05 度 → 说明公式错
 * - 境外必须恒等，否则海外坐标会被错误偏移
 */
class CoordTransformTest {

    private val chinaPoints = listOf(
        GeoPoint(39.9088, 116.3975), // 北京天安门
        GeoPoint(31.2304, 121.4737), // 上海
        GeoPoint(22.5431, 114.0579), // 深圳
    )

    @Test
    fun `domestic points are offset by a few hundred meters (proof conversion happens)`() {
        chinaPoints.forEach { wgs ->
            val gcj = CoordTransform.wgs84ToGcj02(wgs)
            val offsetDeg = Math.abs(gcj.lat - wgs.lat) + Math.abs(gcj.lng - wgs.lng)
            assertTrue(
                "国内点偏移应为 1e-3~1e-2 度量级，实际 $offsetDeg（0=没转换，过大=公式错）",
                offsetDeg > 1e-3 && offsetDeg < 0.05,
            )
        }
    }

    @Test
    fun `round trip within 1e-6 degree (about 0_1 m)`() {
        chinaPoints.forEach { wgs ->
            val back = CoordTransform.gcj02ToWgs84(CoordTransform.wgs84ToGcj02(wgs))
            assertTrue("往返误差过大：$wgs -> $back", Math.abs(back.lat - wgs.lat) < 1e-6)
            assertTrue("往返误差过大：$wgs -> $back", Math.abs(back.lng - wgs.lng) < 1e-6)
        }
    }

    @Test
    fun `points outside china are identical (no offset)`() {
        val outside = listOf(
            GeoPoint(51.5074, -0.1278),   // 伦敦
            GeoPoint(35.6895, 139.6917),  // 东京（经度 > 137.8347 判境外）
            GeoPoint(40.7128, -74.0060),  // 纽约
        )
        outside.forEach { p ->
            val gcj = CoordTransform.wgs84ToGcj02(p)
            assertEquals(p.lat, gcj.lat, 0.0)
            assertEquals(p.lng, gcj.lng, 0.0)
        }
    }

    @Test
    fun `distance helper is sane`() {
        val a = GeoPoint(39.9088, 116.3975)
        val b = GeoPoint(39.9098, 116.3975) // 纬度差 0.001 度 ≈ 111m
        val d = CoordTransform.distanceM(a, b)
        assertTrue("两点距离应约 111m，实际 $d", d > 100 && d < 125)
    }

    @Test
    fun `project moves forward along heading`() {
        val start = GeoPoint(39.9088, 116.3975)
        val north = CoordTransform.projectGcj02(start, headingDeg = 0.0, distanceM = 111.32)
        assertTrue("正北投影应增纬度", north.lat > start.lat)
        val east = CoordTransform.projectGcj02(start, headingDeg = 90.0, distanceM = 111.32)
        assertTrue("正东投影应增经度", east.lng > start.lng)
    }
}
