package com.example.myapplication.core.data.record

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskMode
import com.example.myapplication.core.model.vehicle.TaskSource
import com.example.myapplication.core.model.vehicle.WorkRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.recordDataStore by preferencesDataStore(name = "work_records")

/**
 * 作业记录本地存储（M09）。
 *
 * **记录仅保存在本机手机（[WorkRecord.LOCAL_ONLY_NOTICE]），不得宣称已上传平台。**
 * 使用 DataStore + JSON 字符串：记录量级小、无查询需求，不引入 Room（保持零新增依赖）。
 */
class WorkRecordRepository(private val context: Context) {

    val records: Flow<List<WorkRecord>> = context.recordDataStore.data.map { prefs ->
        parseAll(prefs[KEY_JSON].orEmpty())
    }

    /** 任务结束时落一条记录（done/failed/canceled/link_loss/unknown） */
    suspend fun append(record: WorkRecord) {
        val current = records.first()
        val next = (listOf(record) + current).take(MAX_RECORDS)
        context.recordDataStore.edit { it[KEY_JSON] = toJson(next) }
    }

    /** 人工现场核验完成后追加核验段（与设备结果分开记录） */
    suspend fun updateVerify(id: String, verifyResult: String, verifyNote: String?) {
        val current = records.first()
        val next = current.map {
            if (it.id == id) it.copy(verifyResult = verifyResult, verifyNote = verifyNote) else it
        }
        context.recordDataStore.edit { it[KEY_JSON] = toJson(next) }
    }

    /** 补记异常备注（失联保护/编码器异常/左右偏差/超时） */
    suspend fun appendAbnormalNote(id: String, note: String) {
        val current = records.first()
        val next = current.map {
            if (it.id == id) {
                it.copy(abnormalNote = listOfNotNull(it.abnormalNote, note).joinToString("；"))
            } else it
        }
        context.recordDataStore.edit { it[KEY_JSON] = toJson(next) }
    }

    /** 设置上传状态（local/pending/failed） */
    suspend fun setUploadState(id: String, state: String) {
        val current = records.first()
        val next = current.map {
            if (it.id == id) it.copy(uploadState = state) else it
        }
        context.recordDataStore.edit { it[KEY_JSON] = toJson(next) }
    }

    /** 标记上传成功（HTTP：真实服务端单号；STUB：占位单号） */
    suspend fun setUploaded(id: String, serverId: String, uploadedAt: Long) {
        val current = records.first()
        val next = current.map {
            if (it.id == id) {
                it.copy(
                    uploadState = WorkRecord.UPLOAD_UPLOADED,
                    serverId = serverId,
                    uploadedAt = uploadedAt,
                )
            } else it
        }
        context.recordDataStore.edit { it[KEY_JSON] = toJson(next) }
    }

    private fun toJson(list: List<WorkRecord>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("startedAt", r.startedAt)
                    .put("finishedAt", r.finishedAt)
                    .put("kind", r.kind.name)
                    .put("source", r.source.name)
                    .put("targetM", r.targetM)
                    .put("traveledM", r.traveledM)
                    .put("deviceResult", r.deviceResult)
                    .put("deviceResultNote", r.deviceResultNote)
                    .put("verifyResult", r.verifyResult)
                    .put("verifyNote", r.verifyNote)
                    .put("abnormalNote", r.abnormalNote)
                    .put("name", r.name)
                    .put("mode", r.mode?.name)
                    .put("route", org.json.JSONArray().apply {
                        r.route.forEach { seg ->
                            put(
                                org.json.JSONObject()
                                    .put("action", seg.action.name)
                                    .put("value", seg.value),
                            )
                        }
                    })
                    .put("uploadState", r.uploadState)
                    .put("uploadedAt", r.uploadedAt ?: 0L)
                    .put("serverId", r.serverId),
            )
        }
        return arr.toString()
    }

    private fun parseRoute(arr: org.json.JSONArray?): List<com.example.myapplication.core.model.vehicle.RouteSegment> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val action = runCatching {
                    com.example.myapplication.core.model.vehicle.SegmentAction
                        .valueOf(o.optString("action"))
                }.getOrNull() ?: continue
                add(
                    com.example.myapplication.core.model.vehicle.RouteSegment(
                        action = action,
                        value = o.optDouble("value", 0.0),
                    ),
                )
            }
        }
    }

    private fun parseAll(raw: String): List<WorkRecord> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        WorkRecord(
                            id = o.optString("id"),
                            startedAt = o.optLong("startedAt"),
                            finishedAt = o.optLong("finishedAt"),
                            kind = TaskKind.valueOf(o.optString("kind", TaskKind.FIXED_DISTANCE.name)),
                            source = TaskSource.valueOf(o.optString("source", TaskSource.APP.name)),
                            targetM = o.optDouble("targetM", 0.0),
                            traveledM = o.optDouble("traveledM", 0.0),
                            deviceResult = o.optString("deviceResult", WorkRecord.RESULT_UNKNOWN),
                            deviceResultNote = o.optString("deviceResultNote", null),
                            verifyResult = o.optString("verifyResult", WorkRecord.VERIFY_NOT_DONE),
                            verifyNote = o.optString("verifyNote", null),
                            abnormalNote = o.optString("abnormalNote", null),
                            name = o.optString("name", null),
                            mode = o.optString("mode", null)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { runCatching { TaskMode.valueOf(it) }.getOrNull() },
                            route = parseRoute(o.optJSONArray("route")),
                            uploadState = o.optString("uploadState", WorkRecord.UPLOAD_LOCAL),
                            uploadedAt = o.optLong("uploadedAt", 0L).takeIf { it > 0 },
                            serverId = o.optString("serverId", null),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private companion object {
        val KEY_JSON = stringPreferencesKey("work_records_json")
        const val MAX_RECORDS = 200
    }
}
