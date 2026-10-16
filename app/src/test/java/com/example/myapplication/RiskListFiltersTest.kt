package com.example.myapplication

import com.example.myapplication.core.data.risk.FakeRiskRepository
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.model.risk.AccidentType
import com.example.myapplication.core.model.risk.RiskSort
import com.example.myapplication.core.model.risk.RiskTimeScope
import com.example.myapplication.core.model.risk.applyRiskView
import com.example.myapplication.core.util.TimeFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 风险页列表口径单测（演示数据 + 筛选/排序）：
 * 1. **每条风险都必须带占用车道**（第几车道），无车道的不入列表
 * 2. **默认「今天」6 条**；切「全部」才看到历史数据
 * 3. 默认按距离**从近到远**；可切「按时间新→旧」
 */
class RiskListFiltersTest {

    private val center = GeoPoint(39.9928, 116.4004)
    private val dayMs = 24 * 60 * 60 * 1000L

    private fun accident(
        id: String,
        distanceM: Double,
        occurredAt: Long,
        lane: Int? = 1,
    ) = Accident(
        id = id,
        title = "测试风险 $id",
        type = AccidentType.REAR_END,
        distanceM = distanceM,
        lat = center.lat,
        lng = center.lng,
        occurredAt = occurredAt,
        description = "-",
        lane = lane,
    )

    @Test
    fun `今天筛选排除历史数据，全部则保留`() {
        val now = System.currentTimeMillis()
        val list = listOf(
            accident("today", 400.0, now - 30 * 60_000L),
            accident("yesterday", 300.0, now - dayMs),
            accident("old", 200.0, now - 3 * dayMs),
        )

        val today = list.applyRiskView(RiskTimeScope.TODAY, RiskSort.DISTANCE, now)
        assertEquals(listOf("today"), today.map { it.id })

        val all = list.applyRiskView(RiskTimeScope.ALL, RiskSort.DISTANCE, now)
        assertEquals(3, all.size)
    }

    @Test
    fun `无车道信息的条目不进列表`() {
        val now = System.currentTimeMillis()
        val list = listOf(
            accident("has-lane", 400.0, now - 10 * 60_000L, lane = 2),
            accident("no-lane", 200.0, now - 5 * 60_000L, lane = null),
        )

        val shown = list.applyRiskView(RiskTimeScope.TODAY, RiskSort.DISTANCE, now)
        assertEquals(listOf("has-lane"), shown.map { it.id })
        assertEquals(
            listOf("has-lane"),
            list.applyRiskView(RiskTimeScope.ALL, RiskSort.RECENT, now).map { it.id },
        )
    }

    @Test
    fun `排序：按距离近到远，按时间新到旧`() {
        val now = System.currentTimeMillis()
        val list = listOf(
            accident("far", 1800.0, now - 10 * 60_000L),
            accident("near", 200.0, now - 90 * 60_000L),
            accident("mid", 900.0, now - 40 * 60_000L),
        )

        assertEquals(
            listOf("near", "mid", "far"),
            list.applyRiskView(RiskTimeScope.TODAY, RiskSort.DISTANCE, now).map { it.id },
        )
        assertEquals(
            listOf("far", "mid", "near"),
            list.applyRiskView(RiskTimeScope.TODAY, RiskSort.RECENT, now).map { it.id },
        )
    }

    @Test
    fun `演示数据：今天 6 条且每条都带车道，全部 8 条`() = runBlocking {
        val all = FakeRiskRepository().getAccidents(center, 2.0)

        assertTrue("演示数据不得出现无车道的条目", all.all { it.lane != null })
        assertEquals(8, all.size)

        val today = all.applyRiskView(RiskTimeScope.TODAY, RiskSort.DISTANCE)
        assertEquals("默认（今天）应显示 6 条", 6, today.size)
        assertTrue("演示数据全部落在 2 公里范围内", all.all { it.distanceM <= 2000.0 })
        assertTrue("按距离排序应从近到远", today.map { it.distanceM } == today.map { it.distanceM }.sorted())

        val older = all.filterNot { TimeFormat.isToday(it.occurredAt) }
        assertEquals("历史数据 2 条（切「全部」可见）", 2, older.size)
    }
}
