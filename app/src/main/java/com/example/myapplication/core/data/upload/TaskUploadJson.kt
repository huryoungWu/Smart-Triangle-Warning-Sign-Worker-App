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

    fun toJson(record: WorkRecord): String {
        val segArr = JSONArray()
        record.route.forEach { seg ->
            segArr.put(
                JSONObject()
                    .put("action", seg.action.token)
                    .put("value", seg.value),
            )
        }
        return JSONObject()
            .put("id", record.id)
            .put("name", record.name)
            .put("kind", record.kind.token)
            .put("mode", record.mode?.token)
            .put("startedAt", record.startedAt)
            .put("finishedAt", record.finishedAt)
            .put("segments", segArr)
            .put("targetM", record.targetM)
            .put("traveledM", record.traveledM)
            .put("deviceResult", record.deviceResult)
            .put("deviceResultNote", record.deviceResultNote)
            .put("verifyResult", record.verifyResult)
            .put("verifyNote", record.verifyNote)
            .put("abnormalNote", record.abnormalNote)
            .toString()
    }
}
