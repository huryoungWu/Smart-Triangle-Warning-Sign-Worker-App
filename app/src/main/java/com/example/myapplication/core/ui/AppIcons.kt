package com.example.myapplication.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自绘业务图标。
 *
 * `material-icons-core` 只有 49 个基础图标（无 link / plug / bluetooth / wifi），
 * 而 `material-icons-extended` 已因体积与 IDE 索引问题移除，
 * 因此「连接设备」等业务语义图标在这里按 Material 规范自绘。
 */
object AppIcons {

    /** 连接（链条）—— 用于「连接设备」 */
    val Connect: ImageVector by lazy {
        ImageVector.Builder(
            name = "Connect",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color(0xFF000000))) {
                // 左环
                moveTo(3.9f, 12f)
                curveTo(3.9f, 10.29f, 5.29f, 8.9f, 7f, 8.9f)
                lineTo(11f, 8.9f)
                lineTo(11f, 7f)
                lineTo(7f, 7f)
                curveTo(4.24f, 7f, 2f, 9.24f, 2f, 12f)
                curveTo(2f, 14.76f, 4.24f, 17f, 7f, 17f)
                lineTo(11f, 17f)
                lineTo(11f, 15.1f)
                lineTo(7f, 15.1f)
                curveTo(5.29f, 15.1f, 3.9f, 13.71f, 3.9f, 12f)
                close()
                // 中间连杆
                moveTo(8f, 13f)
                lineTo(16f, 13f)
                lineTo(16f, 11f)
                lineTo(8f, 11f)
                close()
                // 右环
                moveTo(17f, 7f)
                lineTo(13f, 7f)
                lineTo(13f, 8.9f)
                lineTo(17f, 8.9f)
                curveTo(18.71f, 8.9f, 20.1f, 10.29f, 20.1f, 12f)
                curveTo(20.1f, 13.71f, 18.71f, 15.1f, 17f, 15.1f)
                lineTo(13f, 15.1f)
                lineTo(13f, 17f)
                lineTo(17f, 17f)
                curveTo(19.76f, 17f, 22f, 14.76f, 22f, 12f)
                curveTo(22f, 9.24f, 19.76f, 7f, 17f, 7f)
                close()
            }
        }.build()
    }

    /** 断开（断链）—— 用于「断开连接」 */
    val Disconnect: ImageVector by lazy {
        ImageVector.Builder(
            name = "Disconnect",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color(0xFF000000))) {
                // 左环
                moveTo(3.9f, 12f)
                curveTo(3.9f, 10.29f, 5.29f, 8.9f, 7f, 8.9f)
                lineTo(9f, 8.9f)
                lineTo(9f, 7f)
                lineTo(7f, 7f)
                curveTo(4.24f, 7f, 2f, 9.24f, 2f, 12f)
                curveTo(2f, 14.76f, 4.24f, 17f, 7f, 17f)
                lineTo(9f, 17f)
                lineTo(9f, 15.1f)
                lineTo(7f, 15.1f)
                curveTo(5.29f, 15.1f, 3.9f, 13.71f, 3.9f, 12f)
                close()
                // 右环
                moveTo(17f, 7f)
                lineTo(15f, 7f)
                lineTo(15f, 8.9f)
                lineTo(17f, 8.9f)
                curveTo(18.71f, 8.9f, 20.1f, 10.29f, 20.1f, 12f)
                curveTo(20.1f, 13.71f, 18.71f, 15.1f, 17f, 15.1f)
                lineTo(15f, 15.1f)
                lineTo(15f, 17f)
                lineTo(17f, 17f)
                curveTo(19.76f, 17f, 22f, 14.76f, 22f, 12f)
                curveTo(22f, 9.24f, 19.76f, 7f, 17f, 7f)
                close()
            }
        }.build()
    }
}
