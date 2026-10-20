package com.example.myapplication

import com.example.myapplication.core.data.risk.FakeRiskRepository
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.model.risk.AccidentType
import com.example.myapplication.core.model.risk.RiskAlertRules
import com.example.myapplication.core.model.risk.RiskSort
import com.example.myapplication.core.model.risk.RiskTimeScope
import com.example.myapplication.core.model.risk.applyRiskView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新风险弹窗口径单测：
 * 1. 只返回「今天 + 带车道 + 未读」的条目
 * 2. 按距离近→远排序，第一条即弹窗展示项
 * 3. 首次静默基线只对非空列表生效
 * 4. Fake 数据源 id 跨轮询稳定（否则每次都会当成新风险）
 */
class RiskAlertRulesTest {

    private val now = System.currentTimeMillis()
    private val dayMs = 24 * 60 * 60 * 1000L

    private fun accident(
        id: String,
        distanceM: Double,
        occurredAt: Long = now,
        lane: Int? = 1,
    ) = Accident(
        id = id,
        title = "测试风险 $id",
        type = AccidentType.REAR_END,
        distanceM = distanceM,
        lat = 39.9928,
        lng = 116.4004,
        occurredAt = occurredAt,
        description = "-",
        lane = lane,
    )

    @Test
    fun `未读的新风险才返回`() {
        val list = listOf(accident("read", 100.0), accident("new", 200.0))

        val result = RiskAlertRules.pickNewRisks(list, setOf("read"), now)

        assertEquals(listOf("new"), result.map { it.id })
    }

    @Test
    fun `全部已读则返回空`() {
        val list = listOf(accident("a", 100.0), accident("b", 200.0))

        assertTrue(RiskAlertRules.pickNewRisks(list, setOf("a", "b"), now).isEmpty())
    }

    @Test
    fun `无车道或非今天不返回`() {
        val list = listOf(
            accident("no_lane", 100.0, lane = null),
            accident("yesterday", 200.0, occurredAt = now - dayMs),
        )

        assertTrue(RiskAlertRules.pickNewRisks(list, emptySet(), now).isEmpty())
    }

    @Test
    fun `空白 id 被忽略且重复 id 保留最近一条`() {
        val list = listOf(
            accident("", 50.0),
            accident("dup", 500.0),
            accident("dup", 200.0),
        )

        val result = RiskAlertRules.pickNewRisks(list, emptySet(), now)

        assertEquals(1, result.size)
        assertEquals("dup", result.first().id)
        assertEquals(200.0, result.first().distanceM, 0.001)
    }

    @Test
    fun `按距离从近到远排序`() {
        val list = listOf(
            accident("far", 1800.0),
            accident("near", 200.0),
            accident("mid", 900.0),
        )

        val result = RiskAlertRules.pickNewRisks(list, emptySet(), now)

        assertEquals(listOf("near", "mid", "far"), result.map { it.id })
    }

    @Test
    fun `首次基线只对非空列表生效`() {
        val list = listOf(accident("a", 100.0))

        assertTrue(RiskAlertRules.shouldEstablishBaseline(baselined = false, fetched = list))
        assertFalse(RiskAlertRules.shouldEstablishBaseline(baselined = false, fetched = emptyList()))
        assertFalse(RiskAlertRules.shouldEstablishBaseline(baselined = true, fetched = list))
    }

    @Test
    fun `FakeRiskRepository 的 id 跨轮询稳定`() = runBlocking {
        val repo = FakeRiskRepository()
        val center = GeoPoint(39.9928, 116.4004)

        val first = repo.getAccidents(center, 2.0)
        val second = repo.getAccidents(center, 2.0)

        assertEquals(first.map { it.id }, second.map { it.id })
        assertTrue(first.all { it.id.isNotBlank() })
        // 今天 6 条（默认「今天」筛选下的可见条数）
        assertEquals(6, first.applyRiskView(RiskTimeScope.TODAY, RiskSort.DISTANCE).size)
    }
}
