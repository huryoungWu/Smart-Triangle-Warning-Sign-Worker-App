package com.example.myapplication

import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.geo.resolveDeploymentConePins
import com.example.myapplication.core.model.deployment.DeploymentDeviceState
import com.example.myapplication.core.model.deployment.DeploymentDeviceType
import com.example.myapplication.core.model.deployment.DeploymentEvent
import com.example.myapplication.core.model.deployment.DeploymentParams
import com.example.myapplication.core.model.deployment.DeploymentPhase
import com.example.myapplication.core.model.deployment.DeploymentPose
import com.example.myapplication.core.model.deployment.DeploymentSkip
import com.example.myapplication.core.model.deployment.DeploymentState
import com.example.myapplication.core.model.deployment.SkipDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeploymentMapPositionTest {

    private val anchor = GeoPoint(39.9, 116.4)

    private fun device(
        code: String,
        type: DeploymentDeviceType,
        order: Int,
        traveledM: Double,
        targetM: Double,
        arrived: Boolean = false,
        skipped: Boolean = false,
    ) = DeploymentDeviceState(
        deviceCode = code,
        name = code,
        deviceType = type,
        order = order,
        online = true,
        targetM = targetM,
        traveledM = traveledM,
        phase = when {
            skipped -> DeploymentPhase.SKIPPED_MANUAL
            arrived -> DeploymentPhase.ARRIVED
            else -> DeploymentPhase.MOVING
        },
        arrived = arrived,
        skip = if (skipped) {
            DeploymentSkip(skipped = true, disposition = SkipDisposition.REMOVED_FROM_ROUTE)
        } else {
            null
        },
    )

    private fun event(devices: List<DeploymentDeviceState>) = DeploymentEvent(
        deploymentId = "dep-test",
        name = "测试布设",
        lane = 2,
        state = DeploymentState.RUNNING,
        params = DeploymentParams(L = 10.0, d = 2.0, N = 1),
        devices = devices,
    )

    @Test
    fun `position follows each device traveled distance`() {
        val master = device("M-001", DeploymentDeviceType.MASTER_CONE, 0, 5.0, 10.0)
        val slave = device("S-001", DeploymentDeviceType.SLAVE_CONE, 1, 2.0, 8.0)
        val pins = resolveDeploymentConePins(event(listOf(master, slave)), anchor)

        assertEquals(2, pins.size)
        assertTrue(pins[0].isMaster)
        assertEquals("M-001", pins[0].deviceCode)
        assertEquals(5.0, CoordTransform.distanceM(anchor, pins[0].point), 0.2)
        assertEquals("S-001", pins[1].deviceCode)
        assertEquals(2.0, CoordTransform.distanceM(anchor, pins[1].point), 0.2)
        assertFalse(pins[1].isMaster)
    }

    @Test
    fun `arrived and skipped flags are preserved`() {
        val arrived = device("M-001", DeploymentDeviceType.MASTER_CONE, 0, 10.0, 10.0, arrived = true)
        val skipped = device("S-001", DeploymentDeviceType.SLAVE_CONE, 1, 3.0, 8.0, skipped = true)
        val pins = resolveDeploymentConePins(event(listOf(arrived, skipped)), anchor)

        assertTrue(pins[0].arrived)
        assertFalse(pins[0].skipped)
        assertTrue(pins[1].skipped)
        assertEquals(3.0, pins[1].traveledM, 0.001)
        assertEquals(8.0, pins[1].targetM, 0.001)
    }

    @Test
    fun `server pose overrides north projection`() {
        val master = device("M-001", DeploymentDeviceType.MASTER_CONE, 0, 10.0, 10.0)
            .copy(pos = DeploymentPose(3.0, 4.0), headingDeg = 90.0)
        val pin = resolveDeploymentConePins(event(listOf(master)), anchor).single()
        assertEquals(5.0, CoordTransform.distanceM(anchor, pin.point), 0.2)
        assertEquals(90.0, pin.headingDeg, 1e-9)
        assertTrue(pin.point.lng > anchor.lng)
        assertTrue(pin.point.lat > anchor.lat)
    }

    @Test
    fun `no anchor returns no pins`() {
        val master = device("M-001", DeploymentDeviceType.MASTER_CONE, 0, 5.0, 10.0)
        assertTrue(resolveDeploymentConePins(event(listOf(master)), null).isEmpty())
    }

    @Test
    fun `heading changes projection direction`() {
        val master = device("M-001", DeploymentDeviceType.MASTER_CONE, 0, 10.0, 10.0)
        val east = resolveDeploymentConePins(event(listOf(master)), anchor, headingDeg = 90.0).single()
        assertTrue(east.point.lng > anchor.lng)
        assertEquals(anchor.lat, east.point.lat, 0.0001)
        assertEquals(10.0, CoordTransform.distanceM(anchor, east.point), 0.2)
    }
}
