package com.example.myapplication.core.ui.map

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.geo.MapRenderModel

/**
 * 高德 MapView 的 Compose 托管封装。
 *
 * **生命周期要点**（易错，勿改）：
 * - `MapView` 只 `remember` 创建一次，`onCreate(null)` 是官方允许的（相机状态放 ViewModel）
 * - `onResume`/`onPause` 由 LifecycleObserver 转发
 * - **`onDestroy` 只在 `onDispose` 调一次** —— Lifecycle onDestroy 与 Compose onDispose
 *   触发顺序随宿主变化，双调是常见崩溃源；用 runCatching 兜底
 * - 隐私同意门在 [com.example.myapplication.feature.map.MapScreen] 外层，未同意前不创建本组件
 *
 * 渲染用**增量 diff**（Marker 复用）而非 clear+add，防止状态刷新导致闪烁/内存抖动。
 */
/**
 * 通用地图标记点（如道路风险事故点位）。
 */
data class MapPin(
    val id: String,
    val point: GeoPoint,
    val title: String,
    val snippet: String,
    val colorArgb: Int,
)

@Composable
fun AMapCanvas(
    model: MapRenderModel,
    onMapReady: (AMap) -> Unit,
    modifier: Modifier = Modifier,
    extraMarkers: List<MapPin> = emptyList(),
    /** 是否用**五角星**标出「我的位置」（作业人员位置）；风险页需要，作业页默认不显示 */
    showPhoneMarker: Boolean = false,
    /** 相机缩放级别：作业页 17（近景），风险页放大到能覆盖 2 公里半径的点位 */
    cameraZoom: Float = DEFAULT_CAMERA_ZOOM,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 关键：隐私合规 + setApiKey 必须早于 MapView 构造（缺了会空白地图，不抛异常）
    MapSdkPrivacy.initIfNeeded(context)

    val mapView = remember {
        MapView(context).apply {
            onCreate(null as Bundle?)
            // 显式 resume：否则部分机型上瓦片不加载（地图灰白/空白）
            onResume()
        }
    }
    val aMapHolder = remember { mutableStateOf<AMap?>(null) }
    val markers = remember { mutableMapOf<String, Marker>() }
    val polylines = remember { mutableMapOf<String, Polyline>() }
    val cameraDone = remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                runCatching { mapView.onResume() }
            }

            override fun onPause(owner: LifecycleOwner) {
                runCatching { mapView.onPause() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 只在此销毁一次（勿在 observer.onDestroy 再调）
            runCatching { mapView.onDestroy() }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { _ -> mapView },   // factory 收到 Context，返回已 remember 的 MapView
        update = { _ ->
            val aMap = aMapHolder.value ?: mapView.map.also { aMapHolder.value = it; onMapReady(it) }
            renderMap(aMap, model, markers, polylines, cameraDone, extraMarkers, showPhoneMarker, cameraZoom)
        },
    )
}

private fun renderMap(
    aMap: AMap,
    model: MapRenderModel,
    markers: MutableMap<String, Marker>,
    polylines: MutableMap<String, Polyline>,
    cameraDone: androidx.compose.runtime.MutableState<Boolean>,
    extraMarkers: List<MapPin> = emptyList(),
    showPhoneMarker: Boolean = false,
    cameraZoom: Float = DEFAULT_CAMERA_ZOOM,
) {
    // ── 额外通用标记（事故点位等）──
    val extraIds = extraMarkers.map { it.id }.toSet()
    markers.keys.filter { it.startsWith("extra:") && it.removePrefix("extra:") !in extraIds }
        .forEach { markers.remove(it)?.remove() }
    extraMarkers.forEach { pin ->
        putMarker(
            aMap, markers, "extra:" + pin.id, pin.point,
            pin.title, pin.snippet, pin.colorArgb,
        )
    }

    // ---- 「我的位置」五角星（风险页：风险点位中要有自己所在位置的点位）----
    val phone = model.phone
    if (showPhoneMarker && phone != null) {
        putMarker(
            aMap, markers, KEY_PHONE_SELF, phone,
            "我的位置", "作业人员当前定位（手机）", COLOR_PHONE_SELF,
            icon = PHONE_STAR_BITMAP,
        )
    } else {
        markers.remove(KEY_PHONE_SELF)?.remove()
    }

    // ---- 小车（GPS）橙点：车端上报，已转 GCJ-02 ----
    // 地图只保留小车一颗位置球（作业人员点位/轨迹不再单独渲染；相机仍以人员位置为中心）
    val gpsPoint = model.vehicleGps
    if (gpsPoint != null) {
        putMarker(
            aMap, markers, KEY_VEHICLE_GPS, gpsPoint,
            "小车（GPS）", "车端 GPS 上报（WGS-84 转 GCJ-02 显示）", COLOR_VEHICLE_GPS,
        )
    } else {
        markers.remove(KEY_VEHICLE_GPS)?.remove()
    }

    // ---- 小车（行程估算）灰点 + 锚点虚线：**非地图定位** ----
    val est = model.vehicleEstimate
    if (est != null) {
        putMarker(
            aMap, markers, KEY_VEHICLE_EST, est,
            "小车（行程估算）", "由车轮编码器估算行驶距离推算 · 非地图定位", COLOR_ESTIMATE,
        )
        val anchor = model.estimateAnchor
        if (anchor != null) {
            val line = polylines.getOrPut(KEY_EST_LINE) {
                aMap.addPolyline(
                    PolylineOptions()
                        .setDottedLine(true)
                        .color(COLOR_ESTIMATE_LINE)
                        .width(12f)
                        .add(LatLng(anchor.lat, anchor.lng), LatLng(est.lat, est.lng)),
                )
            }
            line.points = listOf(LatLng(anchor.lat, anchor.lng), LatLng(est.lat, est.lng))
        }
    } else {
        markers.remove(KEY_VEHICLE_EST)?.remove()
        polylines.remove(KEY_EST_LINE)?.remove()
    }

    // ---- 轨迹（小车 GPS 轨迹；球已缩小，轨迹可见）----
    updateTrail(aMap, polylines, KEY_GPS_TRAIL, model.vehicleGpsTrail, COLOR_VEHICLE_GPS, dotted = false)

    // ---- 相机：首个有效 fix 定位一次；跟随模式下跟随手机 ----
    val target = when {
        model.followPhone -> model.phone
        !cameraDone.value -> model.phone ?: model.vehicleGps ?: model.vehicleEstimate
        else -> null
    }
    if (target != null) {
        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.lat, target.lng), cameraZoom))
        cameraDone.value = true
    }
}

private fun putMarker(
    aMap: AMap,
    markers: MutableMap<String, Marker>,
    key: String,
    point: GeoPoint,
    title: String,
    snippet: String,
    color: Int,
    /** 自定义图标（默认位置球）；仅在**新建**标记时生效 */
    icon: Bitmap? = null,
) {
    val latLng = LatLng(point.lat, point.lng)
    val existing = markers[key]
    if (existing != null) {
        existing.position = latLng
        existing.title = title
        existing.snippet = snippet
    } else {
        markers[key] = aMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(title)
                .snippet(snippet)
                .icon(BitmapDescriptorFactory.fromBitmap(icon ?: circleBitmap(color))),
        )
    }
}

/** 五角星位图：「我的位置」（作业人员自身位置），与小车球/事故点位区分 */
private fun starBitmap(color: Int): Bitmap {
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val center = size / 2f
    val outer = size / 2f - 4f
    val inner = outer * 0.42f
    val path = android.graphics.Path()
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) outer else inner
        // 起始角 -90°：第一个尖角朝上
        val angle = Math.toRadians(-90.0 + i * 36.0)
        val x = center + (radius * Math.cos(angle)).toFloat()
        val y = center + (radius * Math.sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    canvas.drawPath(
        path,
        Paint().apply {
            this.color = color
            isAntiAlias = true
            style = Paint.Style.FILL
        },
    )
    canvas.drawPath(
        path,
        Paint().apply {
            this.color = android.graphics.Color.WHITE
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 4f
        },
    )
    return bmp
}

private fun updateTrail(
    aMap: AMap,
    polylines: MutableMap<String, Polyline>,
    key: String,
    points: List<GeoPoint>,
    color: Int,
    dotted: Boolean,
) {
    if (points.size < 2) {
        polylines.remove(key)?.remove()
        return
    }
    val latLngs = points.map { LatLng(it.lat, it.lng) }
    val existing = polylines[key]
    if (existing != null) {
        existing.points = latLngs
    } else {
        polylines[key] = aMap.addPolyline(
            PolylineOptions()
                .setDottedLine(dotted)
                .color(color)
                .width(14f)
                .addAll(latLngs),
        )
    }
}

/** 位置球位图：**小尺寸**（48px），避免大球盖住轨迹线 */
private fun circleBitmap(color: Int): Bitmap {
    val size = 48
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint().apply {
        this.color = color
        isAntiAlias = true
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3f, paint)
    val ring = Paint().apply {
        this.color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3f, ring)
    return bmp
}

private const val KEY_VEHICLE_GPS = "vehicle_gps"
private const val KEY_VEHICLE_EST = "vehicle_est"
private const val KEY_GPS_TRAIL = "gps_trail"
private const val KEY_EST_LINE = "est_line"
private const val KEY_PHONE_SELF = "phone_self"
private val COLOR_VEHICLE_GPS = android.graphics.Color.parseColor("#E65100")
private val COLOR_ESTIMATE = android.graphics.Color.parseColor("#757575")
private val COLOR_ESTIMATE_LINE = android.graphics.Color.parseColor("#9E9E9E")
private val COLOR_PHONE_SELF = android.graphics.Color.parseColor("#1565C0")

/** 默认相机缩放级别（作业页近景） */
const val DEFAULT_CAMERA_ZOOM = 17f

/** 「我的位置」五角星位图（只建一次） */
private val PHONE_STAR_BITMAP: Bitmap by lazy { starBitmap(COLOR_PHONE_SELF) }
