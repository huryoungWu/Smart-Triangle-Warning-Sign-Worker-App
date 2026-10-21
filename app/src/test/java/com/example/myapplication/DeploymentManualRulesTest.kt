package com.example.myapplication

import com.example.myapplication.core.model.deployment.DeploymentDeviceState
import com.example.myapplication.core.model.deployment.DeploymentDeviceType
import com.example.myapplication.core.model.deployment.DeploymentEvent
import com.example.myapplication.core.model.deployment.DeploymentParams
import com.example.myapplication.core.model.deployment.DeploymentPhase
import com.example.myapplication.core.model.deployment.DeploymentSkip
import com.example.myapplication.core.model.deployment.DeploymentState
import com.example.myapplication.core.model.deployment.SkipDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeploymentManualRulesTest {

    private fun device(
        code: String,
        type: DeploymentDeviceType,
        order: Int,
        phase: DeploymentPhase = DeploymentPhase.STOPPED,
        online: Boolean = true,
        arrived: Boolean = false,
        skipped: Boolean = false,
        traveledM: Double = 1.0,
        targetM: Double = 5.0,
    ) = DeploymentDeviceState(
        deviceCode = code,
        name = code,
        deviceType = type,
        order = order,
        online = online,
        targetM = targetM,
        traveledM = traveledM,
        phase = phase,
        arrived = arrived,
        skip = if (skipped) {
            DeploymentSkip(skipped = true, disposition = SkipDisposition.REMOVED_FROM_ROUTE)
        } else {
            null
        },
    )

    private fun event(
        devices: List<DeploymentDeviceState>,
        state: DeploymentState = DeploymentState.PAUSED,
    ) = DeploymentEvent(
        deploymentId = "dep-manual",
        name = "manual",
        lane = 2,
        state = state,
        params = DeploymentParams(L = 10.0, d = 2.0, N = 1),
        devices = devices,
    )

    @Test
    fun `master is default when eligible`() {
        val master = device("M", DeploymentDeviceType.MASTER_CONE, 0)
        val slave = device("S", DeploymentDeviceType.SLAVE_CONE, 1)
        val ev = event(listOf(master, slave))
        assertEquals("M", ev.defaultJogDeviceCode())
        assertTrue(ev.jogEligible("M"))
        assertTrue(ev.jogEligible("S"))
    }

    @Test
    fun `fallback to first eligible when master is arrived`() {
        val master = device(
            "M", DeploymentDeviceType.MASTER_CONE, 0,
            phase = DeploymentPhase.ARRIVED, arrived = true,
        )
        val slave = device("S", DeploymentDeviceType.SLAVE_CONE, 1)
        assertEquals("S", event(listOf(master, slave)).defaultJogDeviceCode())
    }

    @Test
    fun `running event is not eligible`() {
        val master = device("M", DeploymentDeviceType.MASTER_CONE, 0)
        val ev = event(listOf(master), state = DeploymentState.RUNNING)
        assertFalse(ev.jogEligible("M"))
    }

    @Test
    fun `offline skipped arrived and at-target are not eligible`() {
        val offline = device("OFF", DeploymentDeviceType.MASTER_CONE, 0, online = false)
        val skipped = device("SKIP", DeploymentDeviceType.SLAVE_CONE, 1, skipped = true)
        val arrived = device(
            "ARR", DeploymentDeviceType.SLAVE_CONE, 2,
            phase = DeploymentPhase.ARRIVED, arrived = true,
        )
        val atTarget = device(
            "MAX", DeploymentDeviceType.SLAVE_CONE, 3,
            traveledM = 5.0, targetM = 5.0,
        )
        val ev = event(listOf(offline, skipped, arrived, atTarget))
        assertFalse(ev.device("OFF")!!.manualEligible)
        assertFalse(ev.device("SKIP")!!.manualEligible)
        assertFalse(ev.device("ARR")!!.manualEligible)
        assertFalse(ev.device("MAX")!!.manualEligible)
        assertFalse(ev.jogEligible("OFF"))
        assertNull(ev.defaultJogDeviceCode())
    }

    @Test
    fun `not started device is not eligible`() {
        val notStarted = device(
            "NS", DeploymentDeviceType.SLAVE_CONE, 1,
            phase = DeploymentPhase.NOT_STARTED,
        )
        assertFalse(event(listOf(notStarted)).jogEligible("NS"))
    }
}
