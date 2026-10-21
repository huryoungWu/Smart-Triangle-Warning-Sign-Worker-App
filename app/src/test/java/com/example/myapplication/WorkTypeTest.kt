package com.example.myapplication

import com.example.myapplication.navigation.Routes
import com.example.myapplication.navigation.WorkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 登录后作业类型分流的路由契约 */
class WorkTypeTest {

    @Test
    fun `token roundtrip`() {
        assertEquals(WorkType.TRIANGLE, WorkType.fromToken("triangle"))
        assertEquals(WorkType.CONE, WorkType.fromToken("cone"))
        assertNull(WorkType.fromToken(null))
        assertNull(WorkType.fromToken("unknown"))
    }

    @Test
    fun `start and landing routes`() {
        assertEquals(Routes.CONNECT, WorkType.TRIANGLE.startRoute)
        assertEquals(Routes.HOME, WorkType.TRIANGLE.landingRoute)
        assertEquals(Routes.CONE_CONNECT, WorkType.CONE.startRoute)
        assertEquals(Routes.CONE_HOME, WorkType.CONE.landingRoute)
    }

    @Test
    fun `owns only its own flow routes`() {
        assertTrue(WorkType.TRIANGLE.owns(Routes.CONNECT))
        assertTrue(WorkType.TRIANGLE.owns(Routes.HOME))
        assertTrue(WorkType.TRIANGLE.owns(Routes.TASK_PUBLISH))
        assertTrue(WorkType.TRIANGLE.owns(Routes.TASK_EXEC))
        assertTrue(WorkType.TRIANGLE.owns(Routes.MAP))

        assertTrue(WorkType.CONE.owns(Routes.CONE_CONNECT))
        assertTrue(WorkType.CONE.owns(Routes.CONE_HOME))
        assertTrue(WorkType.CONE.owns(Routes.DEPLOYMENT_PUBLISH))
        assertTrue(WorkType.CONE.owns(Routes.DEPLOYMENT_EXEC))

        assertFalse(WorkType.TRIANGLE.owns(Routes.CONE_HOME))
        assertFalse(WorkType.CONE.owns(Routes.HOME))
        assertFalse(WorkType.CONE.owns(Routes.RECORDS))
        assertFalse(WorkType.TRIANGLE.owns(Routes.DEPLOYMENT_RECORDS))
    }

    @Test
    fun `owns ignores query parameters`() {
        assertTrue(WorkType.TRIANGLE.owns("${Routes.TASK_PUBLISH}?kind={kind}"))
    }
}
