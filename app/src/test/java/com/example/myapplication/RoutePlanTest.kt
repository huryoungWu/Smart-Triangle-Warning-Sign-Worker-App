package com.example.myapplication

import com.example.myapplication.core.model.vehicle.RouteSegment
import com.example.myapplication.core.model.vehicle.SegmentAction
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskMode
import com.example.myapplication.core.model.vehicle.TaskSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 任务规格校验规则（发布表单的门） */
class RoutePlanTest {

    /** 默认带合法作业车道（第 2 车道），专注于路线校验的用例直接用 */
    private fun spec(vararg segs: RouteSegment) = specWithLane(2, *segs)

    private fun specWithLane(lane: Int?, vararg segs: RouteSegment) = TaskSpec(
        kind = TaskKind.FIXED_DISTANCE,
        mode = TaskMode.AUTO,
        lane = lane,
        segments = segs.toList(),
    )

    @Test
    fun `empty route is rejected`() {
        val errors = spec().validate()
        assertTrue(errors.any { it.contains("路线不能为空") })
    }

    @Test
    fun `valid route passes`() {
        val errors = spec(
            RouteSegment(action = SegmentAction.FWD, value = 20.0),
            RouteSegment(action = SegmentAction.TURN, value = -90.0),
            RouteSegment(action = SegmentAction.FWD, value = 20.0),
        ).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `segment distance out of range is rejected`() {
        val errors = spec(RouteSegment(action = SegmentAction.FWD, value = 0.5)).validate()
        assertTrue(errors.any { it.contains("第 1 段") })
    }

    @Test
    fun `turn angle out of range is rejected`() {
        val errors = spec(RouteSegment(action = SegmentAction.TURN, value = 200.0)).validate()
        assertTrue(errors.any { it.contains("转向角度") })
    }

    @Test
    fun `too many segments is rejected`() {
        val segs = List(11) { RouteSegment(action = SegmentAction.FWD, value = 5.0) }
        val errors = spec(*segs.toTypedArray()).validate()
        assertTrue(errors.any { it.contains("10 段") })
    }

    @Test
    fun `total travel over 200 m is rejected`() {
        // 5 段 × 45 米 = 225 米（每段合法但合计超限）
        val segs = List(5) { RouteSegment(action = SegmentAction.FWD, value = 45.0) }
        val errors = spec(*segs.toTypedArray()).validate()
        assertTrue(errors.any { it.contains("超过上限") })
    }

    @Test
    fun `missing lane is rejected`() {
        val errors = specWithLane(null, RouteSegment(action = SegmentAction.FWD, value = 20.0)).validate()
        assertTrue(errors.any { it.contains("作业车道") })
    }

    @Test
    fun `lane out of range is rejected`() {
        val errors = specWithLane(9, RouteSegment(action = SegmentAction.FWD, value = 20.0)).validate()
        assertTrue(errors.any { it.contains("作业车道") })
    }

    @Test
    fun `lane within range passes`() {
        val errors = specWithLane(8, RouteSegment(action = SegmentAction.FWD, value = 20.0)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `lane label reads naturally`() {
        assertEquals("第 3 车道", specWithLane(3).laneLabel())
        assertEquals("未填写", specWithLane(null).laneLabel())
    }

    @Test
    fun `route summary reads naturally`() {
        val s = spec(
            RouteSegment(action = SegmentAction.FWD, value = 20.0),
            RouteSegment(action = SegmentAction.TURN, value = -90.0),
            RouteSegment(action = SegmentAction.FWD, value = 20.0),
        )
        assertEquals("向前 20 米 → 左转 90° → 向前 20 米", s.routeSummary())
    }

    @Test
    fun `default specs match the two entry types`() {
        assertEquals(TaskMode.AUTO, TaskSpec.defaultFixed().mode)
        assertEquals(TaskMode.SEGMENT_CONFIRM, TaskSpec.defaultManual().mode)
        assertTrue(TaskSpec.defaultManual().segments.size == 3)
    }
}
