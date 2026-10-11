package com.example.myapplication.core.data.location

import com.example.myapplication.core.data.device.FakeDeviceRepository
import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 模拟手机定位（无真机/无定位信号时演示地图）。
 *
 * 基准点取 [BASE_GCJ02]，与 `FakeDeviceRepository.BASE_GCJ02` 同点，
 * 两者运动后经坐标转换应**重合** —— 这是坐标系转换正确性的最直观验证。
 */
class FakeLocationRepository : LocationRepository {

    private val _fixes = MutableSharedFlow<PhoneFix>(replay = 1, extraBufferCapacity = 8)
    override val fixes: Flow<PhoneFix> = _fixes.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var elapsed = 0.0
            while (isActive) {
                val p = CoordTransform.projectGcj02(
                    start = BASE_GCJ02,
                    headingDeg = FakeDeviceRepository.DEMO_HEADING_DEG,
                    distanceM = elapsed,
                )
                _fixes.tryEmit(
                    PhoneFix(
                        gcj02Lat = p.lat,
                        gcj02Lng = p.lng,
                        accuracyM = 8f,
                        speedMps = 0.8f,
                        bearingDeg = FakeDeviceRepository.DEMO_HEADING_DEG.toFloat(),
                        at = System.currentTimeMillis(),
                        sourceLabel = "模拟（演示）",
                    ),
                )
                delay(1000)
                elapsed += 0.8 // 约 0.8 m/s，与 Fake 车端同速
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** 演示基准点（GCJ-02），与 Fake 车端 GPS 基准同点 */
        val BASE_GCJ02: GeoPoint = FakeDeviceRepository.BASE_GCJ02
    }
}
