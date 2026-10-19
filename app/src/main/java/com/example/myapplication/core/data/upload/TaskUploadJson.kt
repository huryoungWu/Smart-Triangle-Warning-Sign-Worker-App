package com.example.myapplication.core.data.upload

import com.example.myapplication.core.model.vehicle.WorkRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记录上传载荷契约（给服务端照做）。
 *
 * ```
 * POST /api/v1/tasks
 * → 201 { "id": "srv-xxx", "receivedAt": 1710000000000 }
 * ```
 */
object TaskUploadJson {

    /**
     * 上传载荷：**所有字段显式出现**（服务端不再补默认值）。
     * 数值型缺失用 0，非数值型缺失用 JSON null。
     */
    fun toJson(record: WorkRecord, deviceCode: String): String {
        val segArr = JSONArray()
        record.route.forEach { seg ->
            segArr.put(
                JSONObject()
                    .put("action", seg.action.token)
                    .put("value", seg.value),
            )
        }
        return JSONObject()
            .put("device_code", deviceCode)
            .put("id", record.id)
            .put("name", record.name ?: JSONObject.NULL)
            .put("kind", record.kind.token)
            .put("mode", record.mode?.token ?: JSONObject.NULL)
            .put("lane", record.lane ?: 0)         // 数值型缺失用 0
            .put("startedAt", record.startedAt)
            .put("finishedAt", record.finishedAt)
            .put("segments", segArr)
            .put("targetM", record.targetM)
            .put("traveledM", record.traveledM)
            .put("deviceResult", record.deviceResult)
            .put("deviceResultNote", record.deviceResultNote ?: JSONObject.NULL)
            .put("verifyResult", record.verifyResult)
            .put("verifyNote", record.verifyNote ?: JSONObject.NULL)
            .put("abnormalNote", record.abnormalNote ?: JSONObject.NULL)
            .toString()
    }
}
