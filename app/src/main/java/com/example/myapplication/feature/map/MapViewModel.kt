package com.example.myapplication.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.MyAppApplication
import com.example.myapplication.core.data.api.DeviceRepository
import com.example.myapplication.core.data.api.LocationRepository
import com.example.myapplication.core.data.api.PhoneFix
import com.example.myapplication.core.data.api.TrackPoint
import com.example.myapplication.core.data.api.TrackSource
import com.example.myapplication.core.data.prefs.SettingsRepository
import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.EstimateAnchor
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.geo.MapRenderModel
import com.example.myapplication.core.geo.VehicleMapPoint
import com.example.myapplication.core.geo.resolveVehicleMapPoint
import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MapUiState(
    val privacyAccepted: Boolean = false,
    val locating: Boolean = false,
    val followPhone: Boolean = true,
    val phoneTrail: List<TrackPoint> = emptyList(),
    val vehicleTrail: List<GeoPoint> = emptyList(),
    val anchor: EstimateAnchor? = null,
    val lastVehiclePoint: VehicleMapPoint = VehicleMapPoint.Unknown,
)

/**
 * 地图状态机：手机定位采集、轨迹缓冲、小车位置解析、模拟锚点维护。
 *
 * **小车位置模拟规则**（无车端 GPS 时）：
 * - 起点 = 作业人员当前位置
 * - 行进方向 = 作业人员方向；**作业人员方向未知时默认正北**
 * - 随 `odo.traveledM` 沿该方向推进（行程估算，标注"非地图定位"）
 *
 * Activity 级共享（与 VehicleViewModel 同款 Factory）。
 */
class MapViewModel(
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceRepository: DeviceRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _phoneFix = MutableStateFlow<PhoneFix?>(null)
    val phoneFix: StateFlow<PhoneFix?> = _phoneFix.asStateFlow()

    /** 最近一次车端快照（供小车位置解析） */
    private val lastSnapshot = MutableStateFlow<DeviceSnapshot?>(null)

    /** 作业人员方向（度，正北=0）。未知时保持默认正北 */
    private var operatorHeadingDeg: Double = 0.0

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(privacyAccepted = settingsRepository.amapPrivacyAccepted.first()) }
        }
    }

    /** 隐私同意门：同意后落库，此后才允许创建 MapView / 定位 SDK */
    fun acceptPrivacy() {
        viewModelScope.launch {
            settingsRepository.acceptAmapPrivacy()
            _uiState.update { it.copy(privacyAccepted = true) }
        }
    }

    /** 权限与隐私都就绪后开始定位（幂等） */
    fun startLocating() {
        if (_uiState.value.locating) return
        _uiState.update { it.copy(locating = true) }
        locationRepository.start()
        viewModelScope.launch {
            locationRepository.fixes.collect { fix ->
                _phoneFix.value = fix
                onOperatorFix(fix)
            }
        }
    }

    fun stopLocating() {
        locationRepository.stop()
        _uiState.update { it.copy(locating = false) }
    }

    fun toggleFollow() {
        _uiState.update { it.copy(followPhone = !it.followPhone) }
    }

    /** 作业人员定位更新：建模拟锚点、同步模拟车基准与方向 */
    private fun onOperatorFix(fix: PhoneFix) {
        // 方向：有有效移动方向则用，否则保持默认正北
        if ((fix.speedMps ?: 0f) > 0.5f && fix.bearingDeg != null && fix.bearingDeg in 0f..360f) {
            operatorHeadingDeg = fix.bearingDeg.toDouble()
        }

        val traveledNow = lastSnapshot.value?.odo?.traveledM ?: 0.0

        // 模拟小车基准点 = 作业人员位置（无车端 GPS 时的推算起点）
        _uiState.update { state ->
            val anchor = state.anchor
            if (anchor == null) {
                state.copy(
                    anchor = EstimateAnchor(
                        gcj02 = fix.point,
                        traveledAtAnchorM = traveledNow,
                        headingDeg = operatorHeadingDeg,
                        label = "起点（作业人员位置）",
                    ),
                )
            } else {
                // 已有锚点：只更新方向，保持起点不变（否则任务中起点会漂）
                state.copy(anchor = anchor.copy(headingDeg = operatorHeadingDeg))
            }
        }

        // 模拟车端（Fake 设备）的推算基准与方向跟随作业人员
        (deviceRepository as? com.example.myapplication.core.data.device.FakeDeviceRepository)?.let { fake ->
            fake.gpsBaseGcj02 = fix.point
            fake.headingDeg = operatorHeadingDeg
        }
        // 服务端实现：把手机位置随状态查询上报（服务端据此把"车端 GPS"锚定在作业人员附近，联调约定）
        (deviceRepository as? com.example.myapplication.core.data.device.ServerDeviceRepository)?.let { srv ->
            srv.phoneLat = fix.gcj02Lat
            srv.phoneLng = fix.gcj02Lng
        }

        appendPhoneTrail(fix)
        recomputeVehiclePoint()
    }

    /** 车端快照更新（任务页面每次推进都推入） */
    fun onVehicleSnapshot(snapshot: DeviceSnapshot) {
        lastSnapshot.value = snapshot

        // 无锚点时，用当前手机定位兜底建锚点（保证小车起点=作业人员位置）
        if (_uiState.value.anchor == null) {
            _phoneFix.value?.let { fix ->
                _uiState.update {
                    it.copy(
                        anchor = EstimateAnchor(
                            gcj02 = fix.point,
                            traveledAtAnchorM = snapshot.odo.traveledM,
                            headingDeg = operatorHeadingDeg,
                            label = "起点（作业人员位置）",
                        ),
                    )
                }
            }
        }
        recomputeVehiclePoint()
    }

    private fun recomputeVehiclePoint() {
        val snapshot = lastSnapshot.value
        val anchor = _uiState.value.anchor
        if (snapshot == null) return
        val point = resolveVehicleMapPoint(snapshot, anchor)
        _uiState.update { state ->
            val trail = when (point) {
                is VehicleMapPoint.Gps -> state.vehicleTrail + point.gcj02
                is VehicleMapPoint.Estimated -> state.vehicleTrail + point.gcj02
                VehicleMapPoint.Unknown -> state.vehicleTrail
            }
            state.copy(lastVehiclePoint = point, vehicleTrail = trail.takeLast(TRAIL_MAX))
        }
    }

    private fun appendPhoneTrail(fix: PhoneFix) {
        _uiState.update { state ->
            val list = state.phoneTrail
            val last = list.lastOrNull()?.point
            val shouldAppend = last == null ||
                CoordTransform.distanceM(last, fix.point) >= 2.0 ||
                fix.at - (list.lastOrNull()?.at ?: 0) >= 5000
            state.copy(
                phoneTrail = if (shouldAppend) {
                    (list + TrackPoint(fix.point, TrackSource.PHONE, fix.at)).takeLast(TRAIL_MAX)
                } else {
                    list
                },
            )
        }
    }

    /** 组装 SDK 无关的渲染模型（全 GCJ-02） */
    fun renderModel(): MapRenderModel {
        val s = _uiState.value
        val phone = _phoneFix.value?.point
        val vp = s.lastVehiclePoint
        return MapRenderModel(
            phone = phone,
            phoneTrail = s.phoneTrail.map { it.point },
            vehicleGps = (vp as? VehicleMapPoint.Gps)?.gcj02,
            vehicleGpsTrail = if (vp is VehicleMapPoint.Gps) s.vehicleTrail else emptyList(),
            vehicleEstimate = (vp as? VehicleMapPoint.Estimated)?.gcj02,
            estimateAnchor = (vp as? VehicleMapPoint.Estimated)?.anchor,
            followPhone = s.followPhone,
            vehicleHasGps = vp is VehicleMapPoint.Gps,
        )
    }

    companion object {
        private const val TRAIL_MAX = 2000

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyAppApplication
                val sl = app.serviceLocator
                MapViewModel(sl.locationRepository, sl.settingsRepository, sl.deviceRepository)
            }
        }
    }
}
