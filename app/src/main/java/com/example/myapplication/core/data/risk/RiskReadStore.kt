package com.example.myapplication.core.data.risk

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.riskReadDataStore by preferencesDataStore(
    name = "risk_read",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 新风险弹窗的**本地已读表**（仅本机，不上传服务端）。
 *
 * 沿用工程既有做法：Preferences DataStore + 单个 JSON 字符串，不引入 Room/Gson。
 * JSON 结构：
 * ```
 * {"v":1,"baselined":true,"read":{"r-1000":1750000000000,"r-snap-0042":1750000600000}}
 * ```
 * - `baselined`：首次运行静默基线是否已建立。
 * - `read`：事故 id -> 已读时间戳。
 */
class RiskReadStore(private val context: Context) {

    /** 一次读取到的已读快照；`baselined` 与 `readIds` 来自同一次 DataStore 读取。 */
    data class Snapshot(
        val readIds: Set<String>,
        val baselined: Boolean,
    )

    /** 读取失败（IO 异常等）返回 null，调用方本轮静默跳过。 */
    suspend fun snapshot(): Snapshot? {
        val prefs = runCatching { context.riskReadDataStore.data.first() }.getOrNull() ?: return null
        val stored = parse(prefs[KEY_STATE])
        return Snapshot(readIds = stored.readAt.keys, baselined = stored.baselined)
    }

    /** 增量标记已读（关闭弹窗时整批写入）。 */
    suspend fun markRead(ids: Collection<String>) {
        update { stored ->
            val now = System.currentTimeMillis()
            stored.copy(
                readAt = stored.readAt + ids.filter { it.isNotBlank() }.associateWith { now },
            )
        }
    }

    /** 首次静默基线：`baselined=true` 与这批 id 在同一个 edit 中原子写入。 */
    suspend fun markBaseline(ids: Collection<String>) {
        update { stored ->
            val now = System.currentTimeMillis()
            stored.copy(
                readAt = stored.readAt + ids.filter { it.isNotBlank() }.associateWith { now },
                baselined = true,
            )
        }
    }

    /** 切换服务端时复位（不同服务端的风险 id 会重复）。 */
    suspend fun clear() {
        context.riskReadDataStore.edit { it.remove(KEY_STATE) }
    }

    /** 所有写操作统一走这里：在 [edit] 内部读取当前值，避免读改写竞态。 */
    private suspend fun update(transform: (Stored) -> Stored) {
        context.riskReadDataStore.edit { prefs ->
            val next = prune(transform(parse(prefs[KEY_STATE])))
            prefs[KEY_STATE] = toJson(next)
        }
    }

    /** 只保留最近写入的 [MAX_READ_ENTRIES] 条，防止无限增长。 */
    private fun prune(stored: Stored): Stored = stored.copy(
        readAt = stored.readAt.entries
            .sortedByDescending { it.value }
            .take(MAX_READ_ENTRIES)
            .associate { it.key to it.value },
    )

    private fun parse(raw: String?): Stored {
        if (raw.isNullOrBlank()) return Stored(emptyMap(), baselined = false)
        return runCatching {
            val root = JSONObject(raw)
            val readObj = root.optJSONObject("read")
            val readAt = buildMap {
                if (readObj != null) {
                    val keys = readObj.keys()
                    while (keys.hasNext()) {
                        val id = keys.next()
                        val readAtMillis = readObj.optLong(id, 0L)
                        if (id.isNotBlank() && readAtMillis > 0L) put(id, readAtMillis)
                    }
                }
            }
            Stored(readAt = readAt, baselined = root.optBoolean("baselined", false))
        }.getOrDefault(Stored(emptyMap(), baselined = false))
    }

    private fun toJson(stored: Stored): String = JSONObject()
        .put("v", 1)
        .put("baselined", stored.baselined)
        .put(
            "read",
            JSONObject().apply {
                stored.readAt.forEach { (id, readAtMillis) -> put(id, readAtMillis) }
            },
        )
        .toString()

    private data class Stored(
        val readAt: Map<String, Long>,
        val baselined: Boolean,
    )

    private companion object {
        /** 已读 id 上限：单条 id 约十几字节，1000 条约几十 KB，足够覆盖演示与日常使用。 */
        const val MAX_READ_ENTRIES = 1000

        val KEY_STATE = stringPreferencesKey("risk_read_state")
    }
}
