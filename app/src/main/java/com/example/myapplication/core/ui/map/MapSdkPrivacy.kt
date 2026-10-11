package com.example.myapplication.core.ui.map

import android.content.Context
import android.content.pm.PackageManager
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer

/**
 * 高德 SDK 初始化统一入口（幂等）——**必须早于任何地图/定位业务调用**。
 *
 * 两件事缺一不可：
 * 1. **隐私合规**：地图与定位是**两个不同的类**，都要调
 *    - 地图：`com.amap.api.maps.MapsInitializer.updatePrivacyShow/updatePrivacyAgree`
 *    - 定位：`com.amap.api.location.AMapLocationClient.updatePrivacyShow/updatePrivacyAgree`
 *    （只调其一，对应 SDK 会静默拒绝工作：地图表现为空白瓦片）
 * 2. **显式注入 Key**：`MapsInitializer.setApiKey(...)` / `AMapLocationClient.setApiKey(...)`
 *    仅靠 Manifest meta-data（`com.amap.api.v2.apikey`）在部分机型/版本上不生效，
 *    高德官方排障指引要求在业务调用前用 setApiKey 显式设置。
 *
 * Key 从 Manifest meta-data 读取（单一事实源），不重复写死在源码里。
 */
object MapSdkPrivacy {

    private const val MANIFEST_KEY_NAME = "com.amap.api.v2.apikey"
    private const val PLACEHOLDER_KEY = "REPLACE_WITH_YOUR_AMAP_KEY"

    @Volatile
    private var initialized = false

    /** 已注入的 Key（诊断用），null = 未配置或仍为占位符 */
    @Volatile
    var injectedApiKey: String? = null
        private set

    fun initIfNeeded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext

            // 1) 隐私合规（两套 SDK 都要）
            MapsInitializer.updatePrivacyShow(appContext, true, true)
            MapsInitializer.updatePrivacyAgree(appContext, true)
            AMapLocationClient.updatePrivacyShow(appContext, true, true)
            AMapLocationClient.updatePrivacyAgree(appContext, true)

            // 2) 显式注入 Key（高德排障指引：调用业务前 setApiKey）
            val key = readManifestKey(appContext)
            if (key != null) {
                MapsInitializer.setApiKey(key)
                AMapLocationClient.setApiKey(key)
                injectedApiKey = key
            }
            initialized = true
        }
    }

    /** 从 Manifest meta-data 读 Key（与 build.gradle 注入的 ${AMAP_KEY} 同源） */
    private fun readManifestKey(context: Context): String? = runCatching {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        info.metaData?.getString(MANIFEST_KEY_NAME)
    }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != PLACEHOLDER_KEY }

    fun isInitialized(): Boolean = initialized
}
