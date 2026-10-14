package com.example.myapplication.core.data.device

import com.example.myapplication.core.data.api.CmdAck
import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.GpsFixState
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MotionState
import com.example.myapplication.core.model.vehicle.OdoInfo
import com.example.myapplication.core.model.vehicle.StopSource
import com.example.myapplication.core.model.vehicle.VehicleGps
import com.example.myapplication.core.model.vehicle.TaskInfo
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.model.vehicle.TaskSource
import com.example.myapplication.core.model.vehicle.VehicleMode
import org.json.JSONObject

/**
 * ESP32 REST/JSON 编解码（仅真机 HTTP 路径使用；JVM 单测不触碰）。
 * 假定接口形状见需求改造计划；字段缺失时回退到安全默认值，避免闪退（NFR 可靠性）。
 */
object DeviceJson {

    fun parseStatus(raw: String): DeviceSnapshot {
        val o = JSONObject(raw)
        val now = System.currentTimeMillis()
        val taskObj = o.optJSONObject("task")
        val odoObj = o.optJSONObject("odo")
        return DeviceSnapshot(
            deviceId = o.optString("deviceId", "unknown"),
            fw = o.optString("fw", "-"),
            mode = enumOrDefault(o.optString("mode"), VehicleMode.STANDBY),
            motion = enumOrDefault(o.optString("motion"), MotionState.IDLE),
            estopLocked = o.optBoolean("estopLocked", false),
            stopSource = enumOrDefault(o.optString("stopSource"), StopSource.NONE),
            light = enumOrDefault(o.optString("light"), LightMode.OFF),
            task = TaskInfo(
                id = taskObj?.optString("id", null),
                kind = taskObj?.let { enumOrNull<TaskKind>(it.optString("kind")) },
                source = enumOrDefault(taskObj?.optString("source"), TaskSource.APP),
                targetM = taskObj?.optDouble("targetM", 0.0) ?: 0.0,
                traveledM = taskObj?.optDouble("traveledM", 0.0) ?: 0.0,
                state = enumOrDefault(taskObj?.optString("state"), TaskRunState.IDLE),
                stage = taskObj?.optString("stage", null),
                resultNote = taskObj?.optString("resultNote", null),
                segments = parseSegments(taskObj?.optJSONArray("segments")),
                headingDeg = taskObj?.optDouble("heading", 0.0) ?: 0.0,
            ),
            odo = OdoInfo(
                leftPulse = odoObj?.optLong("leftPulse", 0L) ?: 0L,
                rightPulse = odoObj?.optLong("rightPulse", 0L) ?: 0L,
                traveledM = odoObj?.optDouble("traveledM", 0.0) ?: 0.0,
                speedMps = odoObj?.optDouble("speedMps", 0.0) ?: 0.0,
                diffPulse = odoObj?.optLong("diffPulse", 0L) ?: 0L,
            ),
            gps = parseGps(o.optJSONObject("gps")),
            batteryPercent = if (o.isNull("batteryPercent")) null else o.optInt("batteryPercent"),
            tilt = if (o.isNull("tilt")) null else o.optBoolean("tilt"),
            obstacle = if (o.isNull("obstacle")) null else o.optBoolean("obstacle"),
            deviceTs = o.optLong("ts", now),
            receivedAt = now,
        )
    }

    fun parseAck(raw: String): CmdAck {
        val o = JSONObject(raw)
        return CmdAck(
            accepted = o.optBoolean("accepted", false),
            cmdId = o.optString("cmdId", null),
            reason = o.optString("reason", null),
        )
    }

    /** 解析路段进度数组（旧固件无此字段 → 空列表，UI 退化为整体进度） */
    private fun parseSegments(arr: org.json.JSONArray?): List<com.example.myapplication.core.model.vehicle.SegmentState> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val action = runCatching {
                    com.example.myapplication.core.model.vehicle.SegmentAction
                        .valueOf(o.optString("action", "").uppercase())
                }.getOrNull() ?: continue
                add(
                    com.example.myapplication.core.model.vehicle.SegmentState(
                        action = action,
                        value = o.optDouble("value", 0.0),
                        state = enumOrDefault(
                            o.optString("state"),
                            com.example.myapplication.core.model.vehicle.SegmentRunState.PENDING,
                        ),
                        progress = o.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
                    ),
                )
            }
        }
    }

    /**
     * 解析可选车端 GPS 块（协议契约，供 ESP32 固件照做）：
     * ```json
     * "gps": { "lat": 39.9088, "lng": 116.3975, "acc": 5.0, "speed": 0.12,
     *          "heading": 180.0, "state": "fixed", "at": 1710000000000 }
     * ```
     * **坐标系 WGS-84**（车端模块原值）。无 GPS 硬件时**省略整个 gps 字段**（≠ 发 null 坐标）。
     * 缺字段、NaN、0/0 均视作无有效定位。
     */
    private fun parseGps(g: JSONObject?): VehicleGps? {
        if (g == null) return null
        val lat = g.optDouble("lat", Double.NaN)
        val lng = g.optDouble("lng", Double.NaN)
        val invalid = lat.isNaN() || lng.isNaN() ||
            (kotlin.math.abs(lat) < 1e-6 && kotlin.math.abs(lng) < 1e-6)
        if (invalid) return null
        return VehicleGps(
            wgs84Lat = lat,
            wgs84Lng = lng,
            accuracyM = if (g.isNull("acc")) null else g.optDouble("acc", 0.0).toFloat(),
            speedMps = if (g.isNull("speed")) null else g.optDouble("speed", 0.0),
            headingDeg = if (g.isNull("heading")) null else g.optDouble("heading", 0.0),
            state = enumOrDefault(g.optString("state"), GpsFixState.FIXED),
            at = g.optLong("at", 0L),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
        enumOrNull<T>(value) ?: default

    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? {
        if (value.isNullOrBlank()) return null
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
