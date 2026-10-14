package com.example.myapplication.core.data.risk

import com.example.myapplication.core.data.api.RiskRepository
import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.model.risk.AccidentType
import kotlinx.coroutines.delay

/**
 * 演示数据源：按中心点生成周围事故点。
 * 无真实事故数据源时使用（`ServiceLocator.RISK_SOURCE = FAKE`）。
 */
class FakeRiskRepository : RiskRepository {

    override suspend fun getAccidents(center: GeoPoint, radiusKm: Double): List<Accident> {
        delay(300)
        // 6 条演示事故，方位与距离不同，用于验证「按距离从近到远排序」
        val seeds = listOf(
            Seed(0.4, 25.0, AccidentType.BREAKDOWN, "小车抛锚占用最右车道", "一辆白色小轿车因发动机故障停在最右车道，未设置警示标志，后方车辆请注意避让。", false),
            Seed(1.1, 140.0, AccidentType.REAR_END, "两车追尾，占用左侧两车道", "高峰时段两辆小客车发生追尾，双方驾驶员无受伤，车辆停在左侧两条车道，正在等待拖车。", true),
            Seed(2.3, 210.0, AccidentType.CONSTRUCTION, "路面养护施工，单向半幅封闭", "路面沥青摊铺作业，施工区域长约 300 米，已布设锥桶与警示灯，请提前变道。", true),
            Seed(3.6, 75.0, AccidentType.ROLLOVER, "货车侧翻，货物散落", "一辆轻型货车因雨天路滑发生侧翻，货物散落占用两条车道，现场正在清理。", true),
            Seed(5.2, 300.0, AccidentType.DEBRIS, "车道内有散落物", "前方车道内有较大面积散落物（疑似轮胎碎片），请减速通过并注意避让。", false),
            Seed(7.8, 340.0, AccidentType.OTHER, "前方长下坡，事故多发路段", "该路段为长下坡弯道，历史事故多发，请控制车速、保持车距。", true),
        )
        val now = System.currentTimeMillis()
        return seeds
            .filter { it.km <= radiusKm }
            .map { s ->
                val point = CoordTransform.projectGcj02(center, s.headingDeg, s.km * 1000.0)
                Accident(
                    title = s.title,
                    type = s.type,
                    distanceM = s.km * 1000.0,
                    lat = point.lat,
                    lng = point.lng,
                    occurredAt = now - (s.km * 6 * 60_000L).toLong(),
                    description = s.desc,
                    hasPhoto = s.photo,
                    hasVideo = s.video,
                )
            }
            .sortedBy { it.distanceM }
    }

    private data class Seed(
        val km: Double,
        val headingDeg: Double,
        val type: AccidentType,
        val title: String,
        val desc: String,
        val photo: Boolean,
        val video: Boolean = true,
    )
}
