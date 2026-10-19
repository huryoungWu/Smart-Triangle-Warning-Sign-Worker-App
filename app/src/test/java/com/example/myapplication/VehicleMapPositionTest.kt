package com.example.myapplication

import com.example.myapplication.core.geo.ESTIMATE_BASIS
import com.example.myapplication.core.geo.EstimateAnchor
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

/**
 * 位置语义单测 —— 规格第 4 节·数据真实性的红线：
 * 只有车端 GPS FIX 才能称"小车地图位置"；无 GPS 必须标"估算/非地图定位"。
 */
class VehicleMapPositionTest {

    private fun snapshot(gps: VehicleGps?, traveledM: Double = 0.0) = DeviceSnapshot(
        deviceCode = "TC-001",
        fw = "0.3.1",
        mode = VehicleMode.STANDBY,
        motion = MotionState.IDLE,
        estopLocked = false,
        stopSource = StopSource.NONE,
        light = LightMode.OFF,
        task = TaskInfo(),
        odo = OdoInfo(traveledM = traveledM),
        gps = gps,
    )

    private fun gpsFix(lat: Double, lng: Double) = VehicleGps(
        wgs84Lat = lat,
        wgs84Lng = lng,
        accuracyM = 5f,
        state = GpsFixState.FIXED,
    )

    @Test
    fun `null snapshot yields unknown`() {
        assertTrue(resolveVehicleMapPoint(null, null) is VehicleMapPoint.Unknown)
    }

    @Test
    fun `gps fix is the only legal map position and gets converted to gcj02`() {
        val wgs = GeoPoint(39.9088, 116.3975)
        val result = resolveVehicleMapPoint(snapshot(gpsFix(wgs.lat, wgs.lng)), anchor = null)
        assertTrue(result is VehicleMapPoint.Gps)
        val point = result as VehicleMapPoint.Gps
        // 必须发生 WGS-84→GCJ-02 偏移，否则点位会偏几百米
        val offset = Math.abs(point.gcj02.lat - wgs.lat) + Math.abs(point.gcj02.lng - wgs.lng)
        assertTrue("车端 GPS 上图前必须做坐标转换，实际偏移 $offset", offset > 1e-3)
    }

    @Test
    fun `no gps falls back to estimate with mandatory wording`() {
        val anchor = EstimateAnchor(
            gcj02 = GeoPoint(39.9088, 116.3975),
            traveledAtAnchorM = 0.0,
            headingDeg = 0.0,
            label = "起点",
        )
        val result = resolveVehicleMapPoint(snapshot(gps = null, traveledM = 1.0), anchor)
        assertTrue(result is VehicleMapPoint.Estimated)
        val est = result as VehicleMapPoint.Estimated
        // 防冒充：文案必须同时含"估算"与"非地图定位"
        assertTrue("估算文案必须含「估算」", est.basis.contains("估算"))
        assertTrue("估算文案必须含「非地图定位」", est.basis.contains("非地图定位"))
        assertEquals(ESTIMATE_BASIS, est.basis)
    }

    @Test
    fun `no gps and no anchor yields unknown (never fabricate a point)`() {
        val result = resolveVehicleMapPoint(snapshot(gps = null), anchor = null)
        assertTrue(result is VehicleMapPoint.Unknown)
    }

    @Test
    fun `no_fix state falls back to estimate`() {
        val anchor = EstimateAnchor(GeoPoint(39.9088, 116.3975), 0.0, 0.0, "起点")
        val notFixed = VehicleGps(0.0, 0.0, state = GpsFixState.NO_FIX)
        val result = resolveVehicleMapPoint(snapshot(gps = notFixed), anchor)
        assertTrue(result is VehicleMapPoint.Estimated)
    }

    @Test
    fun `estimate projected forward along heading`() {
        val anchor = EstimateAnchor(
            gcj02 = GeoPoint(39.9088, 116.3975),
            traveledAtAnchorM = 0.0,
            headingDeg = 0.0, // 正北
            label = "起点",
        )
        val result = resolveVehicleMapPoint(snapshot(gps = null, traveledM = 100.0), anchor)
        val est = result as VehicleMapPoint.Estimated
        assertTrue("正北行驶 100m 后纬度应增加", est.gcj02.lat > anchor.gcj02.lat)
    }
}
