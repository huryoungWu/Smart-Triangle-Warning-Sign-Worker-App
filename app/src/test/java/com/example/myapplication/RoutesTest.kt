package com.example.myapplication

import com.example.myapplication.navigation.Routes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 底栏可见性：带参数的路由模式必须先归一化 */
class RoutesTest {

    @Test
    fun `task publish pattern still shows bottom bar`() {
        assertTrue(Routes.showsBottomBar(Routes.TASK_PUBLISH))
        assertTrue(Routes.showsBottomBar("${Routes.TASK_PUBLISH}?${Routes.ARG_KIND}={${Routes.ARG_KIND}}"))
    }

    @Test
    fun `login hides bottom bar`() {
        assertFalse(Routes.showsBottomBar(Routes.LOGIN))
        assertFalse(Routes.showsBottomBar(null))
    }

    @Test
    fun `work type selector and both flow landings show bottom bar`() {
        assertTrue(Routes.showsBottomBar(Routes.WORK_TYPE))
        assertTrue(Routes.showsBottomBar(Routes.HOME))
        assertTrue(Routes.showsBottomBar(Routes.CONE_HOME))
        assertTrue(Routes.showsBottomBar(Routes.CONE_CONNECT))
    }
}
