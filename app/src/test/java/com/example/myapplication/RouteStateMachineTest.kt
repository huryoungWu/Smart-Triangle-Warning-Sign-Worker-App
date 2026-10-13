package com.example.myapplication

import com.example.myapplication.core.data.device.FakeDeviceRepository
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.RouteSegment
import com.example.myapplication.core.model.vehicle.SegmentAction
import com.example.myapplication.core.model.vehicle.SegmentRunState
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskMode
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.data.upload.StubTaskUploadRepository
import com.example.myapplication.core.model.vehicle.WorkRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 路线分段状态机（发布即执行、无启动按钮） */
class RouteStateMachineTest {

    private fun seg(a: SegmentAction, v: Double) = RouteSegment(action = a, value = v)

    /** 用 tickScale 加速推进，无需真实等待 */
    private suspend fun FakeDeviceRepository.tick(times: Int = 1) {
        tickScale = 200.0
        repeat(times) { fetchStatus() }
        tickScale = 1.0
    }

    @Test
    fun `auto mode runs all segments then completes`() = runBlocking {
        val repo = FakeDeviceRepository()
        val ack = repo.startRoute(
            TaskKind.FIXED_DISTANCE, TaskMode.AUTO,
            listOf(seg(SegmentAction.FWD, 3.0), seg(SegmentAction.TURN, 30.0), seg(SegmentAction.FWD, 3.0)),
            LightMode.BLINK,
        ).getOrThrow()
        assertTrue(ack.accepted)

        repo.tick(20)
        val s = repo.fetchStatus().getOrThrow()
        assertEquals(TaskRunState.DONE, s.task.state)
        assertTrue(s.task.segments.all { it.state == SegmentRunState.DONE })
    }

    @Test
    fun `segment confirm mode waits for confirmation`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.startRoute(
            TaskKind.MANUAL, TaskMode.SEGMENT_CONFIRM,
            listOf(seg(SegmentAction.FWD, 3.0), seg(SegmentAction.FWD, 3.0)),
            LightMode.OFF,
        ).getOrThrow()

        repo.tick(2)
        val s1 = repo.fetchStatus().getOrThrow()
        assertEquals(SegmentRunState.WAITING_CONFIRM, s1.task.segments[0].state)

        repo.confirmSegment().getOrThrow()
        repo.tick(12)
        val s2 = repo.fetchStatus().getOrThrow()
        assertEquals(SegmentRunState.DONE, s2.task.segments[0].state)
        // 第二段应停在待确认
        assertEquals(SegmentRunState.WAITING_CONFIRM, s2.task.segments[1].state)
    }

    @Test
    fun `wait segment counts down then completes`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.startRoute(
            TaskKind.FIXED_DISTANCE, TaskMode.AUTO,
            listOf(seg(SegmentAction.WAIT, 1.0)),
            LightMode.OFF,
        ).getOrThrow()
        repo.tick(6)
        val s = repo.fetchStatus().getOrThrow()
        assertEquals(TaskRunState.DONE, s.task.state)
    }

    @Test
    fun `turn segment changes heading but not distance`() = runBlocking {
        val repo = FakeDeviceRepository()
        val before = repo.fetchStatus().getOrThrow()
        repo.startRoute(
            TaskKind.FIXED_DISTANCE, TaskMode.AUTO,
            listOf(seg(SegmentAction.TURN, 60.0)),
            LightMode.OFF,
        ).getOrThrow()
        repo.tick(10)
        val after = repo.fetchStatus().getOrThrow()
        assertEquals(TaskRunState.DONE, after.task.state)
        assertTrue("转向应改变航向", after.task.headingDeg != before.task.headingDeg)
        assertEquals("转向不应增加里程", 0.0, after.odo.traveledM, 0.01)
    }

    @Test
    fun `failed segment skips the rest`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.startRoute(
            TaskKind.FIXED_DISTANCE, TaskMode.AUTO,
            listOf(seg(SegmentAction.FWD, 50.0), seg(SegmentAction.FWD, 20.0)),
            LightMode.OFF,
        ).getOrThrow()
        repo.cancelTask().getOrThrow()
        val s = repo.fetchStatus().getOrThrow()
        assertEquals(TaskRunState.CANCELED, s.task.state)
        assertEquals(SegmentRunState.SKIPPED, s.task.segments[1].state)
    }

    @Test
    fun `manual tweak does not cancel a waiting-confirm task`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.startRoute(
            TaskKind.MANUAL, TaskMode.SEGMENT_CONFIRM,
            listOf(seg(SegmentAction.FWD, 10.0), seg(SegmentAction.FWD, 10.0)),
            LightMode.OFF,
        ).getOrThrow()
        repo.tick(2)
        // 段间手动微调
        repo.sendMove(com.example.myapplication.core.model.vehicle.MoveDir.FWD).getOrThrow()
        repo.tick(2)
        repo.sendStop().getOrThrow()
        val s = repo.fetchStatus().getOrThrow()
        assertEquals("微调不应取消等待中的任务", TaskRunState.RUNNING, s.task.state)
    }

    @Test
    fun `empty route is rejected by device`() = runBlocking {
        val repo = FakeDeviceRepository()
        val ack = repo.startRoute(TaskKind.FIXED_DISTANCE, TaskMode.AUTO, emptyList(), LightMode.OFF).getOrThrow()
        assertFalse(ack.accepted)
    }

    @Test
    fun `stub upload can succeed and fail`() = runBlocking {
        val repo = StubTaskUploadRepository()
        val record = WorkRecord(
            id = "rec-1", startedAt = 0, finishedAt = 1,
            kind = TaskKind.FIXED_DISTANCE, source = com.example.myapplication.core.model.vehicle.TaskSource.APP,
            targetM = 20.0, traveledM = 20.0, deviceResult = WorkRecord.RESULT_DONE,
        )
        val ok = repo.upload(record)
        assertTrue(ok.isSuccess)

        repo.failNext = true
        assertTrue(repo.upload(record).isFailure)
        // 失败注入是一次性的
        assertTrue(repo.upload(record).isSuccess)
    }
}
