package com.example.myapplication

import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.geo.VehicleMapPoint
import com.example.myapplication.core.geo.resolveVehicleMapPoint
import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.GpsFixState
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MotionState
import com.example.myapplication.core.model.vehicle.OdoInfo
import com.example.myapplication.core.model.vehicle.StopSource
import com.example.myapplication.core.model.vehicle.TaskInfo
import com.example.myapplication.core.model.vehicle.VehicleGps
import com.example.myapplication.core.model.vehicle.VehicleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 「车端 GPS → 高德可用格式」的**离线验证**（不用真机、不用服务端），覆盖联调链路的每一环：
 *
 * 1. 黄金样本：固定 WGS-84 输入 → 精确 GCJ-02 输出（与标准算法参考实现锁定，防公式回归）
 * 2. 服务端↔APP 往返复刻：模拟 Mock 的「锚点 GCJ → 输出 WGS-84」过程，
 *    APP 端 [resolveVehicleMapPoint] 转换后应回到原锚点附近
 *    —— 这正是"服务端发出的坐标 → 高德地图收到的格式"的离线完整模拟
 * 3. 高德可用性：输出必须是合法经纬度、有限值，且确实发生了 GCJ-02 偏移（非原样透传）
 * 4. 境外恒等：海外点（如模拟器默认定位）转换前后一致，不被错误偏移
 *
 * 背景：高德 SDK 只接受 **GCJ-02** 的 LatLng；车端/服务端只出 WGS-84。
 * 全工程唯一的转换点是 resolveVehicleMapPoint（VehicleMapPosition.kt），故本测试从该入口断言。
 */
class GpsToAmapFormatTest {

    private fun snapshotWithGps(lat: Double, lng: Double) = DeviceSnapshot(
        deviceCode = "TC-001",
        fw = "0.3.1",
        mode = VehicleMode.STANDBY,
        motion = MotionState.IDLE,
        estopLocked = false,
        stopSource = StopSource.NONE,
        light = LightMode.OFF,
        task = TaskInfo(),
        odo = OdoInfo(),
        gps = VehicleGps(wgs84Lat = lat, wgs84Lng = lng, accuracyM = 5f, state = GpsFixState.FIXED),
    )

    /** 走完整入口：WGS-84 快照 → [resolveVehicleMapPoint] → GCJ-02 点（与 APP 渲染同一条路径） */
    private fun resolvedGcj(lat: Double, lng: Double): GeoPoint {
        val r = resolveVehicleMapPoint(snapshotWithGps(lat, lng), anchor = null)
        assertTrue("GPS FIX 必须解析为 Gps 点", r is VehicleMapPoint.Gps)
        return (r as VehicleMapPoint.Gps).gcj02
    }

    /** ① 黄金样本（参考实现生成），容差 1e-6 度 ≈ 0.1 米 */
    @Test
    fun `golden wgs84 to gcj02 fixtures`() {
        val cases = listOf(
            Triple(39.9088, 116.3975, GeoPoint(39.9102035, 116.4037436)), // 北京
            Triple(31.2304, 121.4737, GeoPoint(31.2284577, 121.4782231)), // 上海
            Triple(22.5431, 114.0579, GeoPoint(22.5403828, 114.0630140)), // 深圳
            Triple(39.9928, 116.4004, GeoPoint(39.9942074, 116.4066547)), // Mock 默认基准点
        )
        cases.forEach { (lat, lng, expect) ->
            val gcj = resolvedGcj(lat, lng)
            assertEquals("纬度不符（输入 $lat,$lng）", expect.lat, gcj.lat, 1e-6)
            assertEquals("经度不符（输入 $lat,$lng）", expect.lng, gcj.lng, 1e-6)
        }
    }

    /** ② 服务端↔APP 往返复刻：Mock 把锚点(GCJ)转成 WGS 发出 → APP 转回 GCJ 应回到原锚点附近（≤1e-5 度 ≈ 1 米） */
    @Test
    fun `mock to app round trip lands back on the anchor`() {
        val anchors = listOf(
            GeoPoint(39.9928, 116.4004), // Mock 默认基准点
            GeoPoint(39.9088, 116.3975),
            GeoPoint(31.2304, 121.4737),
        )
        anchors.forEach { anchor ->
            // ① 服务端侧（mock 的 gcj02ToWgs84）：这就是状态接口 gps 块里的 lat/lng
            val emitted = CoordTransform.gcj02ToWgs84(anchor)
            // ② APP 侧（resolveVehicleMapPoint）：收到 WGS → 转回 GCJ
            val back = resolvedGcj(emitted.lat, emitted.lng)
            assertTrue(
                "往返误差过大：锚点 $anchor → 发出 WGS $emitted → APP 转回 $back",
                abs(back.lat - anchor.lat) < 1e-5 && abs(back.lng - anchor.lng) < 1e-5,
            )
        }
    }

    /** ③ 高德可用性：合法经纬度范围 + 有限值 + 确实发生偏移（防原样透传/防双重转换） */
    @Test
    fun `resolved point is valid for amap latlng`() {
        val gcj = resolvedGcj(39.9088, 116.3975)
        assertTrue("纬度越界：${gcj.lat}", gcj.lat.isFinite() && gcj.lat in -90.0..90.0)
        assertTrue("经度越界：${gcj.lng}", gcj.lng.isFinite() && gcj.lng in -180.0..180.0)
        val offset = abs(gcj.lat - 39.9088) + abs(gcj.lng - 116.3975)
        assertTrue("必须发生 GCJ-02 偏移（原样透传会偏几百米），实际 $offset", offset > 1e-3)
        assertTrue("偏移过大疑似双重转换，实际 $offset", offset < 0.05)
    }

    /** ④ 境外恒等：Mock 对境外点原样输出 → APP 原样解析（海外/模拟器默认定位场景） */
    @Test
    fun `overseas point passes through unchanged`() {
        val mv = GeoPoint(37.4219999, -122.084) // 山景城（模拟器默认定位）
        val back = resolvedGcj(mv.lat, mv.lng)
        assertEquals(mv.lat, back.lat, 1e-9)
        assertEquals(mv.lng, back.lng, 1e-9)
    }
}
