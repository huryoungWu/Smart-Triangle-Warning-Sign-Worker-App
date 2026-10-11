package com.example.myapplication.navigation

/** 全部页面路由（新规格：无登录、无地图） */
object Routes {
    // 底部 Tab
    const val HOME = "home"
    const val RECORDS = "records"
    const val SETTINGS = "settings"

    // 压栈页
    const val MANUAL = "manual"
    const val PRECHECK = "precheck"
    const val DEPLOY = "deploy"
    const val ONETOUCH = "onetouch"
    const val DEBUG = "debug"
    const val DEVICE_INFO = "device_info"
    const val MAP = "map"

    /** 预检页入口参数：deploy（定距布置）/ onetouch（一键布置） */
    const val ARG_KIND = "kind"
    const val KIND_DEPLOY = "deploy"
    const val KIND_ONETOUCH = "onetouch"

    val tabs = setOf(HOME, RECORDS, SETTINGS)

    /** 需常驻急停入口的页面（M08：首页和运动页）—— 地图页无运动指令，不列入 */
    val estopPages = setOf(HOME, MANUAL, PRECHECK, DEPLOY, ONETOUCH)
}
