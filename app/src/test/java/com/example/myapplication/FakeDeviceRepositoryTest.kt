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
    fun `segment distance must be within 1 to 50 m`() = runBlocking {
        val repo = FakeDeviceRepository()
        // 新语义：每段 1~50 米（多段可累计到 200 米）
        assertFalse(repo.startTask(TaskKind.FIXED_DISTANCE, 0.5).getOrThrow().accepted)    // < 1
        assertFalse(repo.startTask(TaskKind.FIXED_DISTANCE, 60.0).getOrThrow().accepted)   // > 50
        assertTrue(repo.startTask(TaskKind.FIXED_DISTANCE, 30.0).getOrThrow().accepted)    // 合法
    }

    @Test
    fun `duplicate task start rejected (M05 no second task)`() = runBlocking {
        val repo = FakeDeviceRepository()
        val first = repo.startTask(TaskKind.FIXED_DISTANCE, 30.0).getOrThrow()
        assertTrue(first.accepted)
        val second = repo.startTask(TaskKind.FIXED_DISTANCE, 50.0).getOrThrow()
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
        repo.startTask(TaskKind.FIXED_DISTANCE, 50.0)
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

    @Test
    fun `recover brings car back to operator position`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.sendMove(com.example.myapplication.core.model.vehicle.MoveDir.FWD)
        Thread.sleep(300)
        repo.fetchStatus() // 推进 tick
        val before = repo.fetchStatus().getOrThrow().odo.traveledM
        assertTrue("应先离开起点", before > 0.0)

        repo.sendStop()
        val ack = repo.startRecover().getOrThrow()
        assertTrue(ack.accepted)
        // 多次 tick 推进回收
        repeat(30) { repo.fetchStatus(); Thread.sleep(60) }
        val after = repo.fetchStatus().getOrThrow()
        assertTrue("回收后应回到起点", after.odo.traveledM < 0.05)
        assertEquals(TaskRunState.DONE, after.task.state)
    }

    @Test
    fun `gps disabled by default (matches current hardware, forces estimate path)`() = runBlocking {
        val repo = FakeDeviceRepository()
        val status = repo.fetchStatus().getOrThrow()
        assertTrue(status.gps == null)
    }

    @Test
    fun `gps enabled outputs wgs84 that converts to demo base (same-point overlap)`() = runBlocking {
        val repo = FakeDeviceRepository()
        repo.gpsEnabled = true
        val status = repo.fetchStatus().getOrThrow()
        val gps = status.gps
        assertTrue(gps != null)

        // 车端给 WGS-84；转换回 GCJ-02 后应与演示基准点重合（验证转换链路）
        val gcj = com.example.myapplication.core.geo.CoordTransform.wgs84ToGcj02(
            com.example.myapplication.core.geo.GeoPoint(gps!!.wgs84Lat, gps.wgs84Lng),
        )
        val distance = com.example.myapplication.core.geo.CoordTransform.distanceM(
            gcj,
            FakeDeviceRepository.BASE_GCJ02,
        )
        assertTrue("转换后应与基准点重合（<1m），实际 ${distance}m", distance < 1.0)
    }
}
