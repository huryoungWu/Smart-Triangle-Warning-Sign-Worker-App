package com.example.myapplication.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 统一按**北京时间（Asia/Shanghai）**格式化时间。
 *
 * ⚠️ 为什么需要它：设备时区可能为 UTC，若用 `SimpleDateFormat(..., Locale.getDefault())`
 * 按默认时区显示，会与北京时间**差 8 小时**。这里固定使用 Asia/Shanghai，保证全 App 时间一致。
 */
object TimeFormat {

    /** 全 App 统一时区（北京时间） */
    const val ZONE_ID: String = "Asia/Shanghai"

    private val TZ: TimeZone = TimeZone.getTimeZone(ZONE_ID)

    fun format(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault())
            .apply { timeZone = TZ }
            .format(Date(millis))

    /** 常用格式 */
    fun hhmmss(millis: Long): String = format("HH:mm:ss", millis)
    fun mmddHHmm(millis: Long): String = format("MM-dd HH:mm", millis)
    fun full(millis: Long): String = format("yyyy-MM-dd HH:mm", millis)

    /** 是否与 [now] 同一**自然日**（按北京时间判定，风险页「今天」筛选用） */
    fun isToday(millis: Long, now: Long = System.currentTimeMillis()): Boolean =
        format("yyyy-MM-dd", millis) == format("yyyy-MM-dd", now)
}
