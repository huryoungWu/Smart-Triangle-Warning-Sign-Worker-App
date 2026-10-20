package com.example.myapplication.navigation

/**
 * 全部页面路由。
 *
 * 主流程：登录 → 设备连接 → 开始作业 → 任务发布表单 → 任务执行页 → 上传
 */
object Routes {
    /** 主流程第 ⓪ 站：登录（演示状态可跳过） */
    const val LOGIN = "login"

    /** 主流程第一站：设备连接（独立页，连接成功才能进作业页） */
    const val CONNECT = "connect"

    // 底部 Tab
    const val HOME = "home"
    const val RECORDS = "records"
    const val SETTINGS = "settings"

    // 压栈页
    const val TASK_PUBLISH = "task_publish"
    const val TASK_EXEC = "task_exec"

    /** 锥桶协同布设：向导 / 执行 / 记录 */
    const val DEPLOYMENT_CREATE = "deployment_create"
    const val DEPLOYMENT_EXEC = "deployment_exec"
    const val DEPLOYMENT_RECORDS = "deployment_records"

    const val MAP = "map"
    const val DEBUG = "debug"
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
        CONNECT, HOME, RISK, RECORDS, SETTINGS, TASK_PUBLISH, TASK_EXEC,
        DEPLOYMENT_CREATE, DEPLOYMENT_EXEC, DEPLOYMENT_RECORDS,
        MAP, DEBUG, DEVICE_INFO, ABOUT, ACCIDENT_DETAIL, SERVER_CONFIG,
    )

}
