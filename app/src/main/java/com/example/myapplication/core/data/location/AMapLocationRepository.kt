package com.example.myapplication.core.data.location

import android.content.Context
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.example.myapplication.core.data.api.LocationRepository
import com.example.myapplication.core.data.api.PhoneFix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 高德定位实现（真实手机定位）。
 *
 * **隐私合规（高德硬要求）**：`AMapLocationClient` 必须在
 * `updatePrivacyShow` + `updatePrivacyAgree` **之后**才能实例化。
 * 因此本类**构造函数绝不 new client**，全部推迟到 [start]；
 * 否则 SDK 会拒绝工作或抛错。`MyAppApplication` 也不得预调 `updatePrivacyAgree(true)`。
 *
 * **坐标系**：高德定位 SDK 输出**即 GCJ-02**，字段直接以 `gcj02Lat/Lng` 命名，
 * 调用方禁止再过 `CoordTransform`（二次转换会偏移）。
 */
class AMapLocationRepository(
    private val context: Context,
) : LocationRepository {

    private val _fixes = MutableSharedFlow<PhoneFix>(replay = 1, extraBufferCapacity = 8)
    override val fixes: Flow<PhoneFix> = _fixes.asSharedFlow()

    @Volatile
    private var client: AMapLocationClient? = null

    private val listener = AMapLocationListener { loc ->
        if (loc != null && loc.errorCode == 0) {
            _fixes.tryEmit(
                PhoneFix(
                    gcj02Lat = loc.latitude,   // 高德输出 = GCJ-02，不再转换
                    gcj02Lng = loc.longitude,
                    accuracyM = if (loc.accuracy > 0f) loc.accuracy else null,
                    speedMps = if (loc.speed > 0f) loc.speed else null,
                    bearingDeg = if (loc.bearing > 0f) loc.bearing else null,
                    at = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    sourceLabel = if (loc.locationType == AMapLocation.LOCATION_TYPE_GPS) "GPS" else "网络",
                ),
            )
        }
    }

    override fun start() {
        if (client != null) return
        // 隐私合规 + setApiKey：必须先于 AMapLocationClient 构造（地图侧另有 MapsInitializer，见 MapSdkPrivacy）
        com.example.myapplication.core.ui.map.MapSdkPrivacy.initIfNeeded(context)
        client = AMapLocationClient(context).also { c ->
            c.setLocationOption(
                AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    interval = 2000L
                    isNeedAddress = false
                    isOnceLocation = false
                },
            )
            c.setLocationListener(listener)
            c.startLocation()
        }
    }

    override fun stop() {
        client?.stopLocation()
        client?.onDestroy()
        client = null
    }
}
