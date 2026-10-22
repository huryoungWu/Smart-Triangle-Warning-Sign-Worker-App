package com.example.myapplication

import com.example.myapplication.core.model.deployment.DeploymentDeviceSummary
import com.example.myapplication.core.model.deployment.DeploymentDraft
import com.example.myapplication.core.model.deployment.DeploymentDeviceType
import com.example.myapplication.core.model.deployment.DeploymentParams
import com.example.myapplication.core.model.deployment.DeploymentTurn
import com.example.myapplication.core.model.deployment.DeploymentTurnDir
import com.example.myapplication.core.model.deployment.DeploymentTurnDraft
import com.example.myapplication.core.model.deployment.SelectedSlave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锥桶协同布设参数/顺序/预览校验（对应 TC-DEP-001～004、022、023、031 的纯函数部分） */
class DeploymentDraftTest {

    private fun dev(code: String, deviceType: DeploymentDeviceType) = DeploymentDeviceSummary(
        deviceCode = code,
        name = code,
        deviceType = deviceType,
        bindingCode = "123456",
        online = true,
        available = true,
    )

    private fun draft(
        L: String,
        d: String,
        n: Int,
        lane: Int? = 2,
        orders: List<Int>? = null,
    ): DeploymentDraft {
        val slaves = (1..n).map { i ->
            SelectedSlave(dev("S$i", DeploymentDeviceType.SLAVE_CONE), orders?.getOrNull(i - 1) ?: i)
        }
        return DeploymentDraft(
            name = "test",
            lane = lane,
            master = dev("M", DeploymentDeviceType.MASTER_CONE),
            slaves = slaves,
            lText = L,
            dText = d,
            precheckSafety = true,
            precheckOrder = true,
        )
    }

    @Test
    fun `targets are L minus k times d`() {
        val d = draft("60", "10", 3)
        assertTrue(d.validate().isEmpty())
        assertEquals(60.0, d.targetFor(0)!!, 1e-9)
        assertEquals(50.0, d.targetFor(1)!!, 1e-9)
        assertEquals(40.0, d.targetFor(2)!!, 1e-9)
        assertEquals(30.0, d.targetFor(3)!!, 1e-9)
        assertEquals(listOf(60.0, 50.0, 40.0, 30.0), d.preview.map { it.targetM })
    }

    @Test
    fun `d must be greater than zero`() {
        val errors = draft("60", "0", 3).validate()
        assertTrue(errors.any { it.contains("d 必须大于 0") })
    }

    @Test
    fun `L must be at least N times d`() {
        val errors = draft("39", "10", 4).validate()
        assertTrue(errors.any { it.contains("L 必须 ≥ N×d") })
    }

    @Test
    fun `L equal N times d allows last slave target zero`() {
        val d = draft("40", "10", 4)
        assertTrue(d.validate().isEmpty())
        assertEquals(0.0, d.targetFor(4)!!, 1e-9)
        assertTrue(d.preview.last().targetM == 0.0)
    }

    @Test
    fun `last slave target may be below d`() {
        val d = draft("53", "10", 5)
        assertTrue(d.validate().isEmpty())
        assertEquals(3.0, d.targetFor(5)!!, 1e-9)
    }

    @Test
    fun `orders must be contiguous from one`() {
        val errors = draft("60", "10", 3, orders = listOf(1, 2, 4)).validate()
        assertTrue(errors.any { it.contains("连续的 1..3") })
    }

    @Test
    fun `duplicate slave order is rejected`() {
        val errors = draft("60", "10", 2, orders = listOf(1, 1)).validate()
        assertTrue(errors.any { it.contains("连续的 1..2") })
    }

    @Test
    fun `device errors ignore event info`() {
        val d = draft("60", "10", 2, lane = null)
        assertTrue(d.eventErrors().any { it.contains("作业车道") })
        assertTrue(d.deviceErrors().isEmpty())
    }

    @Test
    fun `event errors ignore device selection`() {
        val d = DeploymentDraft(
            name = "test",
            lane = 2,
            master = null,
            slaves = emptyList(),
            lText = "60",
            dText = "10",
        )
        assertTrue(d.eventErrors().isEmpty())
        assertTrue(d.deviceErrors().any { it.contains("请选择主桶") })
    }

    @Test
    fun `master only deployment is valid without slaves`() {
        val d = DeploymentDraft(
            name = "test",
            lane = 2,
            master = dev("M", DeploymentDeviceType.MASTER_CONE),
            slaves = emptyList(),
            lText = "60",
            dText = "",
            precheckSafety = true,
            precheckOrder = true,
        )
        assertTrue(d.validate().isEmpty())
        assertEquals(1.0, d.d!!, 1e-9)
        assertEquals(listOf(60.0), d.preview.map { it.targetM })
        assertTrue(d.canStart())
    }

    @Test
    fun `master cannot be a slave`() {
        val master = dev("M", DeploymentDeviceType.MASTER_CONE)
        val d = DeploymentDraft(
            lane = 2,
            master = master,
            slaves = listOf(SelectedSlave(master, 1)),
            lText = "60",
            dText = "10",
            precheckSafety = true,
            precheckOrder = true,
        )
        assertTrue(d.validate().any { it.contains("主桶不能同时作为从桶") })
    }

    @Test
    fun `lane is required and bounded`() {
        assertTrue(draft("60", "10", 1, lane = null).validate().any { it.contains("作业车道") })
        assertTrue(draft("60", "10", 1, lane = 9).validate().any { it.contains("作业车道") })
    }

    @Test
    fun `precision is at most 0_1 m`() {
        assertTrue(draft("60.05", "10", 1).validate().any { it.contains("精度") })
    }

    @Test
    fun `canStart requires both precheck confirmations`() {
        val d = draft("60", "10", 1).copy(precheckSafety = false)
        assertFalse(d.canStart())
        assertTrue(draft("60", "10", 1).canStart())
    }

    @Test
    fun `N must be between zero and five`() {
        // 0 从桶 = 仅主桶执行，合法
        val none = draft("60", "10", 0)
        assertTrue(none.validate().isEmpty())
        val tooMany = draft("100", "10", 6)
        assertTrue(tooMany.validate().any { it.contains("不能超过 5") })
    }

    // ── 多处 90° 转弯 ──

    private fun turnRow(dir: DeploymentTurnDir, at: String, id: Long = 0L) =
        DeploymentTurnDraft(dir = dir, atText = at, id = id)

    private fun params(
        turns: List<DeploymentTurn> = emptyList(),
        legacy: DeploymentTurn? = null,
        turnsValid: Boolean = true,
    ) = DeploymentParams(
        L = 60.0,
        d = 10.0,
        N = 2,
        turns = turns,
        turn = legacy,
        turnsValid = turnsValid,
    )

    @Test
    fun `straight is the default`() {
        val d = draft("60", "10", 2)
        assertTrue(d.turnErrors().isEmpty())
        assertTrue(d.validate().isEmpty())
        assertTrue(d.parsedTurns.isEmpty())
        assertTrue(d.turnsEchoMatches(params()))
        assertFalse(d.turnsEchoMatches(params(turns = listOf(DeploymentTurn(DeploymentTurnDir.LEFT, 30.0)))))
    }

    @Test
    fun `valid turn lists are accepted`() {
        val one = draft("60", "10", 2).copy(turns = listOf(turnRow(DeploymentTurnDir.LEFT, "30")))
        assertTrue(one.turnErrors().isEmpty())
        assertTrue(one.validate().isEmpty())
        assertTrue(one.canStart())
        assertEquals(listOf(DeploymentTurn(DeploymentTurnDir.LEFT, 30.0)), one.parsedTurns)

        val two = draft("60", "10", 2).copy(
            turns = listOf(
                turnRow(DeploymentTurnDir.LEFT, "20"),
                turnRow(DeploymentTurnDir.RIGHT, "40.5"),
            ),
        )
        assertTrue(two.turnErrors().isEmpty())
        assertEquals(
            listOf(
                DeploymentTurn(DeploymentTurnDir.LEFT, 20.0),
                DeploymentTurn(DeploymentTurnDir.RIGHT, 40.5),
            ),
            two.parsedTurns,
        )

        val five = draft("60", "10", 2).copy(
            turns = (1..5).map { turnRow(DeploymentTurnDir.LEFT, (it * 10).toString()) },
        )
        assertTrue(five.turnErrors().isEmpty())
        assertTrue(five.canStart())
    }

    @Test
    fun `turn point must be entered within 0 and L`() {
        fun errors(at: String) = draft("60", "10", 2)
            .copy(turns = listOf(turnRow(DeploymentTurnDir.LEFT, at)))
            .turnErrors()

        assertTrue(errors("").any { it.contains("请输入转弯点") })
        assertTrue(errors("0").any { it.contains("大于 0") })
        assertTrue(errors("-1").any { it.contains("大于 0") })
        assertTrue(errors("60").any { it.contains("小于 L") })
        assertTrue(errors("61").any { it.contains("小于 L") })
        assertTrue(errors("20.05").any { it.contains("精度") })
    }

    @Test
    fun `turn points must be strictly increasing`() {
        val descending = draft("60", "10", 2).copy(
            turns = listOf(
                turnRow(DeploymentTurnDir.LEFT, "30"),
                turnRow(DeploymentTurnDir.RIGHT, "20"),
            ),
        )
        assertTrue(descending.turnErrors().any { it.contains("第 2 个转弯：必须大于第 1 个转弯点") })

        val duplicate = draft("60", "10", 2).copy(
            turns = listOf(
                turnRow(DeploymentTurnDir.LEFT, "30"),
                turnRow(DeploymentTurnDir.RIGHT, "30"),
            ),
        )
        assertTrue(duplicate.turnErrors().any { it.contains("第 2 个转弯：必须大于第 1 个转弯点") })
    }

    @Test
    fun `at most five turns are allowed`() {
        val six = draft("60", "10", 2).copy(
            turns = (1..6).map { turnRow(DeploymentTurnDir.LEFT, (it * 5).toString()) },
        )
        assertTrue(six.turnErrors().any { it.contains("不能超过 5 处") })
        assertFalse(six.canStart())
    }

    @Test
    fun `turn errors do not leak into L and d field errors`() {
        val d = draft("60", "10", 2).copy(turns = listOf(turnRow(DeploymentTurnDir.LEFT, "70")))
        assertTrue(d.step2Errors().isEmpty())
        assertTrue(d.turnErrors().isNotEmpty())
        assertFalse(d.canStart())
    }

    @Test
    fun `turn echo must match what the server confirmed`() {
        val single = draft("60", "10", 2).copy(turns = listOf(turnRow(DeploymentTurnDir.LEFT, "30")))
        val e = DeploymentTurn(DeploymentTurnDir.LEFT, 30.0)
        assertTrue(single.turnsEchoMatches(params(turns = listOf(e))))
        assertTrue(single.turnsEchoMatches(params(legacy = e)))
        assertTrue(single.turnsEchoMatches(params(turns = listOf(e), legacy = e)))
        assertFalse(
            single.turnsEchoMatches(
                params(turns = listOf(DeploymentTurn(DeploymentTurnDir.RIGHT, 30.0)), legacy = e),
            ),
        )
        assertFalse(
            single.turnsEchoMatches(
                params(turns = listOf(e), legacy = DeploymentTurn(DeploymentTurnDir.RIGHT, 30.0)),
            ),
        )
        assertFalse(single.turnsEchoMatches(params()))
        assertFalse(single.turnsEchoMatches(params(turns = listOf(e), turnsValid = false)))

        val multi = draft("60", "10", 2).copy(
            turns = listOf(
                turnRow(DeploymentTurnDir.LEFT, "20"),
                turnRow(DeploymentTurnDir.RIGHT, "40"),
            ),
        )
        val m1 = DeploymentTurn(DeploymentTurnDir.LEFT, 20.0)
        val m2 = DeploymentTurn(DeploymentTurnDir.RIGHT, 40.0)
        assertTrue(multi.turnsEchoMatches(params(turns = listOf(m1, m2))))
        assertFalse(multi.turnsEchoMatches(params(turns = listOf(m2, m1))))
        assertFalse(multi.turnsEchoMatches(params(turns = listOf(m1))))
        assertFalse(multi.turnsEchoMatches(params(turns = listOf(m1, m2), legacy = m1)))

        val straight = draft("60", "10", 2)
        assertTrue(straight.turnsEchoMatches(params()))
        assertFalse(straight.turnsEchoMatches(params(legacy = e)))
    }
}
