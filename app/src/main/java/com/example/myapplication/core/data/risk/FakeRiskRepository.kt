package com.example.myapplication.core.data.risk

import com.example.myapplication.core.data.api.RiskRepository
import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.model.risk.AccidentType
import com.example.myapplication.core.util.TimeFormat
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.TimeZone

/**
 * 演示数据源：按中心点生成周围事故点。
 * 无真实事故数据源时使用（`ServiceLocator.RISK_SOURCE = FAKE`）。
 *
 * 演示口径：
 * - **每条都带 `lane`（第几车道）**：无车道信息的风险条目不入列表（界面要显示「第 N 车道」）
 * - **今天 6 条**（默认「今天」筛选下的可见条数）+ **历史 2 条**（切「全部」可见，用于验证筛选）
 * - 距离均 ≤ 2 公里（风险页搜索半径），方位各不相同，便于验证「按距离近→远 / 按时间新→旧」排序
 */
class FakeRiskRepository : RiskRepository {

    override suspend fun getAccidents(center: GeoPoint, radiusKm: Double): List<Accident> {
        delay(300)
        val now = System.currentTimeMillis()
        val todayStart = startOfTodayShanghai(now)
        return seeds
            .withIndex()
            .filter { it.value.km <= radiusKm }
            .map { (index, s) ->
                val point = CoordTransform.projectGcj02(center, s.headingDeg, s.km * 1000.0)
                Accident(
                    // 固定 id（与 Mock 服务端 r-1000… 一致）：新风险弹窗靠 id 判定已读，
                    // 每次随机 id 会被当成"永远的新风险"反复弹窗
                    id = "r-${1000 + index}",
                    title = s.title,
                    type = s.type,
                    distanceM = s.km * 1000.0,
                    lat = point.lat,
                    lng = point.lng,
                    occurredAt = occurredAt(now, todayStart, index, s),
                    description = s.desc,
                    lane = s.lane,
                    hasPhoto = s.photo,
                    hasVideo = s.video,
                )
            }
            .sortedBy { it.distanceM }
    }

    /**
     * 发生时间：
     * - 今天的条目按「多少分钟前」倒推；若倒推跨过零点，则贴到当天 0 点之后（保证仍属「今天」筛选）
     * - 历史条目按「几天前的同一时段」倒推
     */
    private fun occurredAt(now: Long, todayStart: Long, index: Int, s: Seed): Long =
        if (s.daysAgo == 0) {
            val back = now - s.minutesAgo * 60_000L
            if (back >= todayStart) {
                back
            } else {
                (todayStart + (index + 1) * 60_000L).coerceAtMost(now)
            }
        } else {
            now - s.daysAgo * DAY_MS - s.minutesAgo * 60_000L
        }

    private fun startOfTodayShanghai(now: Long): Long =
        Calendar.getInstance(TimeZone.getTimeZone(TimeFormat.ZONE_ID)).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private data class Seed(
        /** 距中心点距离（公里）；风险页搜索半径 2 公里，超出即不显示 */
        val km: Double,
        val headingDeg: Double,
        val type: AccidentType,
        val title: String,
        val desc: String,
        val photo: Boolean,
        val video: Boolean = true,
        /** 占用车道（第几车道，1~8）；演示数据每条必带（界面展示「第 N 车道」） */
        val lane: Int,
        /** 距今多少分钟（`daysAgo == 0` 时用于「今天」的条目，若跨零点会贴回当天 0 点后） */
        val minutesAgo: Long,
        /** 距今几天；0 = 今天，>0 = 历史数据（默认「今天」筛选下不显示） */
        val daysAgo: Int = 0,
    )

    private companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** 6 条今天的演示事故（全部带车道）+ 2 条历史（切「全部」可见） */
        private val seeds = listOf(
            Seed(0.35, 25.0, AccidentType.BREAKDOWN, "小车抛锚占用第 4 车道", "一辆白色小轿车因发动机故障停在第 4 车道，未设置警示标志，后方车辆请注意避让。", photo = false, lane = 4, minutesAgo = 8),
            Seed(0.70, 140.0, AccidentType.REAR_END, "两车追尾，占用第 1、2 车道", "高峰时段两辆小客车发生追尾，双方驾驶员无受伤，车辆停在第 1、2 车道，正在等待拖车。", photo = true, lane = 1, minutesAgo = 26),
            Seed(1.10, 210.0, AccidentType.CONSTRUCTION, "路面养护施工，占用第 2 车道", "路面沥青摊铺作业，施工区域长约 300 米、占用第 2 车道，已布设锥桶与警示灯，请提前变道。", photo = true, video = false, lane = 2, minutesAgo = 55),
            Seed(1.40, 75.0, AccidentType.ROLLOVER, "货车侧翻，占用第 2、3 车道", "一辆轻型货车因雨天路滑发生侧翻，货物散落占用第 2、3 车道，现场正在清理。", photo = true, lane = 2, minutesAgo = 92),
            Seed(1.70, 300.0, AccidentType.DEBRIS, "第 3 车道内有散落物", "第 3 车道内有较大面积散落物（疑似轮胎碎片），请减速通过并注意避让。", photo = false, video = false, lane = 3, minutesAgo = 130),
            Seed(1.95, 340.0, AccidentType.OTHER, "路面油污，占用第 3 车道", "前方路面有车辆漏油，油污带长约 50 米、占用第 3 车道，路面湿滑，请减速慢行、避免急刹。", photo = true, video = false, lane = 3, minutesAgo = 165),
            // ── 历史数据（默认「今天」筛选下不显示，切「全部」可见）──
            Seed(0.60, 95.0, AccidentType.CONSTRUCTION, "夜间施工围挡未撤，占用第 2 车道", "施工围挡与锥桶未及时撤除，占用第 2 车道，请提前变道绕行。", photo = true, video = false, lane = 2, minutesAgo = 9 * 60, daysAgo = 1),
            Seed(1.50, 190.0, AccidentType.REAR_END, "早高峰追尾事故，占用第 1 车道", "早高峰时段两车追尾，车辆停在第 1 车道等待处理，后方车辆请注意避让。", photo = true, video = false, lane = 1, minutesAgo = 7 * 60, daysAgo = 3),
        )
    }
}
