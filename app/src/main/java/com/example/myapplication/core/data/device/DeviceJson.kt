package com.example.myapplication.core.data.device

import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MotionState
import com.example.myapplication.core.model.vehicle.OdoInfo
import com.example.myapplication.core.model.vehicle.StopSource
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
            ),
            odo = OdoInfo(
                leftPulse = odoObj?.optLong("leftPulse", 0L) ?: 0L,
                rightPulse = odoObj?.optLong("rightPulse", 0L) ?: 0L,
                traveledM = odoObj?.optDouble("traveledM", 0.0) ?: 0.0,
                speedMps = odoObj?.optDouble("speedMps", 0.0) ?: 0.0,
                diffPulse = odoObj?.optLong("diffPulse", 0L) ?: 0L,
            ),
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

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
        enumOrNull<T>(value) ?: default

    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? {
        if (value.isNullOrBlank()) return null
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
