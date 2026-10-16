package com.example.myapplication.core.data.media

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.core.model.media.TaskSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.snapshotDataStore by preferencesDataStore(name = "task_snapshots")

/** 快照本地存储（任务过程抓拍） */
interface SnapshotRepository {
    val snapshots: Flow<List<TaskSnapshot>>
    suspend fun capture(snapshot: TaskSnapshot)
}

/**
 * 本地实现：DataStore + JSON，保存抓拍记录。
 * 图片当前为占位；接摄像头后在此存图片文件并写入 [TaskSnapshot.imageUri]。
 */
class LocalSnapshotRepository(private val context: Context) : SnapshotRepository {

    override val snapshots: Flow<List<TaskSnapshot>> = context.snapshotDataStore.data.map { prefs ->
        parseAll(prefs[KEY_JSON].orEmpty())
    }

    override suspend fun capture(snapshot: TaskSnapshot) {
        val current = snapshots.first()
        val next = (listOf(snapshot) + current).take(MAX)
        context.snapshotDataStore.edit { it[KEY_JSON] = toJson(next) }
    }

    private fun toJson(list: List<TaskSnapshot>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("capturedAt", s.capturedAt)
                    .put("taskName", s.taskName)
                    .put("lane", s.lane ?: 0)          // 作业车道：0 = 未填写/未标注
                    .put("eventLabel", s.eventLabel)
                    .put("imageUri", s.imageUri)
                    .put("note", s.note),
            )
        }
        return arr.toString()
    }

    private fun parseAll(raw: String): List<TaskSnapshot> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        TaskSnapshot(
                            id = o.optString("id"),
                            capturedAt = o.optLong("capturedAt"),
                            taskName = o.optString("taskName"),
                            lane = o.optInt("lane", 0).takeIf { it > 0 },
                            eventLabel = o.optString("eventLabel"),
                            imageUri = o.optString("imageUri", null),
                            note = o.optString("note", null),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private companion object {
        val KEY_JSON = stringPreferencesKey("task_snapshots_json")
        const val MAX = 50
    }
}
