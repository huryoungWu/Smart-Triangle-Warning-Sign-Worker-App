package com.example.myapplication

import com.example.myapplication.core.data.device.FakeDeviceRepository
import com.example.myapplication.core.data.device.FakeFault
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MotionState
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.model.vehicle.VehicleMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模拟设备行为单测（车端已验证能力的软件侧对应） */
class FakeDeviceRepositoryTest {

    @Test
    fun `link loss makes status unreachable (M01)`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.fault = FakeFault.LinkLoss
        val result = repo.fetchStatus()
        assertTrue(result.isFailure)
    }

    @Test
    fun `estop locks motion commands (M08 stop priority)`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.sendEstop()
        val status = repo.fetchStatus().getOrThrow()
        assertTrue(status.estopLocked)
        assertEquals(VehicleMode.SAFE_STOP, status.mode)

        // 急停锁定后运动指令被拒
        val move = repo.sendMove(com.example.myapplication.core.model.vehicle.MoveDir.FWD).getOrThrow()
        assertFalse(move.accepted)

        // 急停不自动关灯（M07 灯光与运动分离）
        assertEquals(LightMode.OFF, status.light)
    }

    @Test
    fun `resume unlocks but does not auto resume task (M08)`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.sendEstop()
        repo.sendResume()
        val status = repo.fetchStatus().getOrThrow()
        assertFalse(status.estopLocked)
        assertEquals(VehicleMode.STANDBY, status.mode)
        assertEquals(TaskRunState.IDLE, status.task.state)
    }

    @Test
    fun `task rejects out-of-range distance (M05 only 0_5 1 2 m)`() = runBlocking {
        val repo = FakeDeviceRepository()
        val ack = repo.startTask(TaskKind.FIXED_DISTANCE, 1.5).getOrThrow()
        assertFalse(ack.accepted)
    }

    @Test
    fun `duplicate task start rejected (M05 no second task)`() = runBlocking {
        val repo = FakeDeviceRepository()
        val first = repo.startTask(TaskKind.FIXED_DISTANCE, 1.0).getOrThrow()
        assertTrue(first.accepted)
        val second = repo.startTask(TaskKind.FIXED_DISTANCE, 2.0).getOrThrow()
        assertFalse(second.accepted)
    }

    @Test
    fun `command rejected fault surfaces reason`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.fault = FakeFault.CommandRejected
        val ack = repo.sendStop().getOrThrow()
        assertFalse(ack.accepted)
        assertTrue(ack.reason != null)
    }

    @Test
    fun `odo reset refused while task running (M09)`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.startTask(TaskKind.FIXED_DISTANCE, 2.0)
        val ack = repo.resetOdo().getOrThrow()
        assertFalse(ack.accepted)
    }

    @Test
    fun `light modes are exclusive and persisted in status (M07)`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.setLight(LightMode.BLINK)
        val status = repo.fetchStatus().getOrThrow()
        assertEquals(LightMode.BLINK, status.light)
    }

    @Test
    fun `manual move then stop returns to standby`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.sendMove(com.example.myapplication.core.model.vehicle.MoveDir.FWD)
        assertEquals(MotionState.MOVING, repo.fetchStatus().getOrThrow().motion)
        repo.sendStop()
        val after = repo.fetchStatus().getOrThrow()
        assertEquals(MotionState.IDLE, after.motion)
        assertEquals(VehicleMode.STANDBY, after.mode)
    }
}
