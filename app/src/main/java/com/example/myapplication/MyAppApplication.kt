package com.example.myapplication

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import com.example.myapplication.di.ServiceLocator

class MyAppApplication : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        forceChineseLocale()
        serviceLocator = ServiceLocator(this)
    }

    /**
     * 按应用强制中文（Android 13+「按应用语言」，配合 res/xml/locales_config.xml）。
     *
     * 系统权限弹窗（相机「While using the app / Only this time」、定位等）在 Android 13+
     * 会跟随**应用语言**显示：演示机若为英文系统，弹窗会整段英文，与本 APP 中文界面不一致。
     * 这里在启动时把应用语言固定为 zh-CN，使系统弹窗一律中文。
     *
     * 用户若已在系统设置里显式改过本应用语言（applicationLocales 非空），则不覆盖。
     * 注：Android 12 及以下系统弹窗只能跟随系统语言，应用侧无法强制，属于系统限制。
     */
    private fun forceChineseLocale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val localeManager = getSystemService(LocaleManager::class.java) ?: return
        if (localeManager.applicationLocales.isEmpty) {
            localeManager.setApplicationLocales(LocaleList.forLanguageTags("zh-CN"))
        }
    }
}
