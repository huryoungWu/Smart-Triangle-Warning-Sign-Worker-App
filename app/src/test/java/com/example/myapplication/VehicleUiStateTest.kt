package com.example.myapplication

import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.MotionState
import com.example.myapplication.core.model.vehicle.OdoInfo
import com.example.myapplication.core.model.vehicle.StopSource
import com.example.myapplication.core.model.vehicle.TaskInfo
import com.example.myapplication.core.model.vehicle.TaskRunState
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.VehicleMode
import com.example.myapplication.core.model.vehicle.VehicleUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态派生规则单测（规格第 4 节） */
class VehicleUiStateTest {

    private fun snapshot(
        mode: VehicleMode = VehicleMode.STANDBY,
        motion: MotionState = MotionState.IDLE,
        estopLocked: Boolean = false,
        taskState: TaskRunState = TaskRunState.IDLE,
    ) = DeviceSnapshot(
        deviceId = "TC-001",
        fw = "0.3.1",
        mode = mode,
        motion = motion,
        estopLocked = estopLocked,
        stopSource = StopSource.NONE,
        light = LightMode.OFF,
        task = TaskInfo(state = taskState),
        odo = OdoInfo(),
    )

    private fun connected(snap: DeviceSnapshot) = VehicleUiState(
        connection = ConnectionPhase.CONNECTED,
        snapshot = snap,
        lastUpdateAt = System.currentTimeMillis(),
    )

    @Test
    fun `motion commands disabled when disconnected`() {
        val state = VehicleUiState(connection = ConnectionPhase.LOST, snapshot = snapshot())
        assertFalse(state.motionCommandsEnabled)
    }

    @Test
    fun `motion commands disabled when estop locked`() {
        val state = connected(snapshot(estopLocked = true))
        assertFalse(state.motionCommandsEnabled)
    }

    @Test
    fun `motion commands disabled during auto task (no manual preemption, M04)`() {
        val state = connected(snapshot(mode = VehicleMode.AUTO, taskState = TaskRunState.RUNNING))
        assertFalse(state.motionCommandsEnabled)
    }

    @Test
    fun `motion commands enabled in manual standby`() {
        val state = connected(snapshot(mode = VehicleMode.MANUAL))
        assertTrue(state.motionCommandsEnabled)
    }

    @Test
    fun `task start blocked while another task running (M05 repeat click guard)`() {
        val state = connected(snapshot(taskState = TaskRunState.RUNNING))
        assertFalse(state.taskStartEnabled)
    }

    @Test
    fun `task start blocked when estop locked`() {
        val state = connected(snapshot(estopLocked = true))
        assertFalse(state.taskStartEnabled)
    }

    @Test
    fun `task start allowed in standby connected`() {
        val state = connected(snapshot())
        assertTrue(state.taskStartEnabled)
    }

    @Test
    fun `device conditions require online stationary not locked no task (M03)`() {
        val ok = connected(snapshot())
        assertTrue(ok.deviceConditions().all { it.second })

        val locked = connected(snapshot(estopLocked = true))
        val reasons = locked.deviceConditionFailureReasons()
        assertTrue(reasons.any { it.contains("未急停锁定") })

        val moving = connected(snapshot(motion = MotionState.MOVING))
        assertFalse(moving.deviceConditions().all { it.second })
    }

    @Test
    fun `stale data flagged after 3 seconds (M09 non-realtime)`() {
        val state = VehicleUiState(lastUpdateAt = System.currentTimeMillis() - 5000L)
        assertTrue(state.isStale())
        val fresh = VehicleUiState(lastUpdateAt = System.currentTimeMillis())
        assertFalse(fresh.isStale())
    }

    @Test
    fun `precheck is a single combined confirmation`() {
        val unchecked = com.example.myapplication.core.model.vehicle.PrecheckHuman()
        assertFalse(unchecked.allChecked)
        assertEquals(1, unchecked.uncheckedItems().size)

        val checked = com.example.myapplication.core.model.vehicle.PrecheckHuman(confirmed = true)
        assertTrue(checked.allChecked)
        assertTrue(checked.uncheckedItems().isEmpty())
    }
}
