package com.example.myapplication.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.core.model.vehicle.DistancePreset
import com.example.myapplication.core.model.vehicle.LightMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/**
 * 应用设置（M10）：默认距离档、启动提示时间、默认灯光模式。
 * **修改设置不立即启动车辆**，车端仍校验参数范围与启动条件。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_DISTANCE = stringPreferencesKey("default_distance")
        val PROMPT_SECONDS = intPreferencesKey("prompt_seconds")
        val DEFAULT_LIGHT = stringPreferencesKey("default_light")
        val USE_FAKE_DEVICE = booleanPreferencesKey("use_fake_device")
        val AMAP_PRIVACY_ACCEPTED = booleanPreferencesKey("amap_privacy_accepted")
    }

    /**
     * 高德 SDK 隐私合规门：用户同意前不得实例化 MapView / AMapLocationClient。
     * 一次性同意，落库后不再弹。
     */
    val amapPrivacyAccepted: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.AMAP_PRIVACY_ACCEPTED] ?: false
    }

    suspend fun acceptAmapPrivacy() {
        context.settingsDataStore.edit { it[Keys.AMAP_PRIVACY_ACCEPTED] = true }
    }

    val defaultDistance: Flow<DistancePreset> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_DISTANCE]?.let { name ->
            DistancePreset.entries.firstOrNull { it.name == name }
        } ?: DistancePreset.M1
    }

    /** 启动提示时间（秒），用于一键布置倒计时 */
    val promptSeconds: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.PROMPT_SECONDS] ?: DEFAULT_PROMPT_SECONDS
    }

    val defaultLight: Flow<LightMode> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_LIGHT]?.let { name ->
            LightMode.entries.firstOrNull { it.name == name }
        } ?: LightMode.OFF
    }

    /** true = 模拟设备（无真机演示），false = 真实 HTTP（192.168.4.1） */
    val useFakeDevice: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.USE_FAKE_DEVICE] ?: true
    }

    suspend fun saveDefaultDistance(preset: DistancePreset) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_DISTANCE] = preset.name }
    }

    suspend fun savePromptSeconds(seconds: Int) {
        context.settingsDataStore.edit { it[Keys.PROMPT_SECONDS] = seconds.coerceIn(1, 30) }
    }

    suspend fun saveDefaultLight(mode: LightMode) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_LIGHT] = mode.name }
    }

    suspend fun saveUseFakeDevice(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.USE_FAKE_DEVICE] = value }
    }

    companion object {
        const val DEFAULT_PROMPT_SECONDS = 5
    }
}
