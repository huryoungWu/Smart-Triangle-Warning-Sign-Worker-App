package com.example.myapplication.navigation

/**
 * 登录后选择的两类作业：三角警示牌单设备作业 / 锥桶协同布设。
 *
 * 选择结果只保存在本次会话（AppNavHost 的 rememberSaveable），不写入 DataStore；
 * 登出、切换服务器或重新进入登录页时清空。
 */
enum class WorkType(val token: String, val label: String) {
    TRIANGLE("triangle", "三脚架布设"),
    CONE("cone", "锥桶协同布设"),
    ;

    /** 选定类型后进入的第一页：三角牌连接设备，锥桶连接/绑定设备 */
    val startRoute: String
        get() = when (this) {
            TRIANGLE -> Routes.CONNECT
            CONE -> Routes.CONE_CONNECT
        }

    /** 底栏「作业」Tab 指向的流程主页 */
    val landingRoute: String
        get() = when (this) {
            TRIANGLE -> Routes.HOME
            CONE -> Routes.CONE_HOME
        }

    /** 当前路由是否属于本作业流程（用于底栏选中态） */
    fun owns(route: String?): Boolean {
        val r = route?.substringBefore('?') ?: return false
        return when (this) {
            TRIANGLE -> r in setOf(
                Routes.CONNECT,
                Routes.HOME,
                Routes.TASK_PUBLISH,
                Routes.TASK_EXEC,
                Routes.MAP,
            )

            CONE -> r in setOf(
                Routes.CONE_CONNECT,
                Routes.CONE_HOME,
                Routes.DEPLOYMENT_PUBLISH,
                Routes.DEPLOYMENT_EXEC,
            )
        }
    }

    companion object {
        fun fromToken(v: String?): WorkType? = entries.firstOrNull { it.token == v }
    }
}
