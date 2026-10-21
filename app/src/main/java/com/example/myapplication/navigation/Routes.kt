package com.example.myapplication.navigation

/**
 * 全部页面路由。
 *
 * 主流程：登录 → 作业类型选择 →（三角牌 / 锥桶协同）→ 设备连接 → 作业主页 → 任务发布 → 执行
 */
object Routes {
    /** 主流程第 ⓪ 站：登录 */
    const val LOGIN = "login"

    /** 登录后第一站：作业类型选择（三角牌 / 锥桶协同） */
    const val WORK_TYPE = "work_type"

    /** 三角警示牌：设备连接（独立页，连接成功才能进作业页） */
    const val CONNECT = "connect"

    // 底部 Tab
    const val HOME = "home"
    const val RECORDS = "records"
    const val SETTINGS = "settings"

    // 压栈页
    const val TASK_PUBLISH = "task_publish"
    const val TASK_EXEC = "task_exec"

    /** 锥桶协同布设：设备连接 / 作业主页 / 任务发布 / 执行 / 记录 */
    const val CONE_CONNECT = "cone_connect"
    const val CONE_HOME = "cone_home"
    const val DEPLOYMENT_PUBLISH = "deployment_publish"
    const val DEPLOYMENT_EXEC = "deployment_exec"
    const val DEPLOYMENT_RECORDS = "deployment_records"

    const val MAP = "map"
    const val DEVICE_INFO = "device_info"
    const val ABOUT = "about"

    /** 服务器地址（设置 → 服务器地址）：运行时切换服务端，无需重新装包 */
    const val SERVER_CONFIG = "server_config"
    const val RISK = "risk"
    const val ACCIDENT_DETAIL = "accident_detail"

    /** 任务发布表单参数：fixed（定距布置）/ manual（手动遥控） */
    /** 事故详情页参数 */
    const val ARG_ACCIDENT_ID = "accidentId"

    const val ARG_KIND = "kind"
    const val KIND_FIXED = "fixed"
    const val KIND_MANUAL = "manual"

    val tabs = setOf(HOME, RISK, RECORDS, SETTINGS)

    /** 显示底部导航栏的页面：**除登录页外全部显示** */
    val bottomBarPages = setOf(
        WORK_TYPE, CONNECT, HOME, RISK, RECORDS, SETTINGS, TASK_PUBLISH, TASK_EXEC,
        CONE_CONNECT, CONE_HOME, DEPLOYMENT_PUBLISH, DEPLOYMENT_EXEC, DEPLOYMENT_RECORDS,
        MAP, DEVICE_INFO, ABOUT, ACCIDENT_DETAIL, SERVER_CONFIG,
    )

    /**
     * 当前路由是否显示底栏。
     *
     * `currentBackStackEntry.destination.route` 会返回带参数的模式
     * （如 `task_publish?kind={kind}`），必须先按 `?` 归一化再比较。
     */
    fun showsBottomBar(route: String?): Boolean =
        route?.substringBefore('?') in bottomBarPages
}
