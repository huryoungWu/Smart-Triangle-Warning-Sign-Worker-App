package com.example.myapplication.core.data.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.core.model.vehicle.DeviceSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.deviceRegistryDataStore by preferencesDataStore(name = "device_registry")

/**
 * 用户添加设备的本机登记（设备连接页「添加设备」：手动编号 / 扫码）。
 *
 * 内置设备列表来自 [DeviceRepository.listDevices]，两者在 ViewModel 合并展示；
 * 添加的设备仅保存在本机（DataStore + JSON，模式同 [com.example.myapplication.core.data.record.WorkRecordRepository]）。
 */
class DeviceRegistryRepository(private val context: Context) {

    val devices: Flow<List<DeviceSummary>> = context.deviceRegistryDataStore.data.map { prefs ->
        parseAll(prefs[KEY_JSON].orEmpty())
    }

    /** 登记设备（同编号去重，已存在则不重复写） */
    suspend fun add(summary: DeviceSummary) {
        val current = devices.first()
        if (current.any { it.id.equals(summary.id, ignoreCase = true) }) return
        val next = current + summary
        context.deviceRegistryDataStore.edit { it[KEY_JSON] = toJson(next) }
    }

    /** 删除登记的设备 */
    suspend fun remove(id: String) {
        val next = devices.first().filterNot { it.id.equals(id, ignoreCase = true) }
        context.deviceRegistryDataStore.edit { it[KEY_JSON] = toJson(next) }
    }

    private fun toJson(list: List<DeviceSummary>): String {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("batteryPercent", d.batteryPercent),
            )
        }
        return arr.toString()
    }

    private fun parseAll(raw: String): List<DeviceSummary> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val summary = DeviceSummary(
                        id = id,
                        name = o.optString("name").ifBlank { "自定义设备" },
                        started = false,
                        available = true,
                        batteryPercent = o.optInt("batteryPercent", -1),
                    )
                    this@buildList.add(summary)
                }
            }
        }.getOrElse { emptyList() }
    }

    private companion object {
        val KEY_JSON = stringPreferencesKey("device_registry_json")
    }
}
