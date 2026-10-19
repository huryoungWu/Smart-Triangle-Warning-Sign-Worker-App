package com.example.myapplication.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.BuildConfig
import com.example.myapplication.core.model.vehicle.DistanceRange
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.util.FALLBACK_SERVER_BASE_URL
import com.example.myapplication.core.util.normalizeServerUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/**
 * 应用设置（M10）：默认距离档、启动提示时间、默认灯光模式，以及**服务端地址**。
 * **修改设置不立即启动车辆**，车端仍校验参数范围与启动条件。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_DISTANCE_M = intPreferencesKey("default_distance_m")
        val PROMPT_SECONDS = intPreferencesKey("prompt_seconds")
        val DEFAULT_LIGHT = stringPreferencesKey("default_light")
        val USE_FAKE_DEVICE = booleanPreferencesKey("use_fake_device")
        val AMAP_PRIVACY_ACCEPTED = booleanPreferencesKey("amap_privacy_accepted")
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
    }

    // ═══════════════════ 服务端地址（设置 → 服务器地址） ═══════════════════
    //
    // 为什么要有内存缓存而不是每次都读 Flow：各 HTTP 仓库在发出请求时要**同步**拿到地址
    // （见 ServiceLocator 的 baseUrlProvider）。DataStore 是异步的，所以这里把地址复制一份到
    // @Volatile 字段（权威读路径：无锁、无协程），DataStore 只作为持久化与 UI 订阅来源。

    @Volatile
    private var cachedServerBaseUrl: String = compileTimeServerBaseUrl()

    /** 当前生效的服务端地址（同步读；写入见 [saveServerBaseUrl]） */
    val currentServerBaseUrl: String get() = cachedServerBaseUrl

    /** 响应式视图：地址变更后立刻发射，供界面与视频流 URL 跟随 */
    val serverBaseUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.SERVER_BASE_URL]?.let(::normalizeServerUrl) ?: compileTimeServerBaseUrl()
    }

    /**
     * 启动期**同步**读一次，把上次保存的地址灌进内存缓存。
     *
     * 唯一允许阻塞主线程的地方：不这样做的话，首个请求可能带着编译期默认值打出去，
     * 变成时序问题。量级 1–50 ms（读一个约 150 字节的文件），与
     * [com.example.myapplication.MyAppApplication] 里 `forceChineseLocale()` 的两次同步 Binder 同预算。
     *
     * `runCatching` 是必要的：`preferencesDataStore` 未安装 corruptionHandler，
     * 文件损坏会抛 CorruptionException —— 宁可回落默认值也不能在 onCreate 崩。
     */
    fun hydrateServerBaseUrlBlocking() {
        cachedServerBaseUrl = runCatching { runBlocking { serverBaseUrl.first() } }
            .getOrDefault(compileTimeServerBaseUrl())
    }

    /** 保存新地址。返回 false = 地址非法，**未做任何改动** */
    suspend fun saveServerBaseUrl(raw: String): Boolean {
        val canonical = normalizeServerUrl(raw) ?: return false
        context.settingsDataStore.edit { it[Keys.SERVER_BASE_URL] = canonical }
        cachedServerBaseUrl = canonical
        return true
    }

    /** 清除用户覆盖，回到编译期默认（返回恢复后的地址） */
    suspend fun resetServerBaseUrl(): String {
        context.settingsDataStore.edit { it.remove(Keys.SERVER_BASE_URL) }
        cachedServerBaseUrl = compileTimeServerBaseUrl()
        return cachedServerBaseUrl
    }

    /** 编译期默认地址（local.properties 的 SERVER_BASE_URL）；非法则回落模拟器地址 */
    private fun compileTimeServerBaseUrl(): String =
        normalizeServerUrl(BuildConfig.DEFAULT_SERVER_BASE_URL) ?: FALLBACK_SERVER_BASE_URL

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

    /** 默认定距布置距离（米），范围 10-100 */
    val defaultDistanceM: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[Keys.DEFAULT_DISTANCE_M] ?: DistanceRange.DEFAULT_M).coerceIn(
            DistanceRange.MIN_M, DistanceRange.MAX_M,
        )
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

    suspend fun saveDefaultDistanceM(meters: Int) {
        val v = meters.coerceIn(DistanceRange.MIN_M, DistanceRange.MAX_M)
        context.settingsDataStore.edit { it[Keys.DEFAULT_DISTANCE_M] = v }
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
