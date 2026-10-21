package com.example.myapplication.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 固定品牌色板：不启用动态取色，保证现场不同手机上的安全语义一致。 */
private val LightColorScheme = lightColorScheme(
    primary = BrandNavy,
    onPrimary = Color.White,
    primaryContainer = BrandNavyContainer,
    onPrimaryContainer = Color(0xFF062A3B),
    secondary = Color(0xFF4F6070),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE7EE),
    onSecondaryContainer = Color(0xFF111D26),
    tertiary = WarnOrange,
    onTertiary = Color.White,
    tertiaryContainer = WarnOrangeBg,
    onTertiaryContainer = Color(0xFF3B1A05),
    background = AppBackgroundLight,
    onBackground = AppOnSurfaceLight,
    surface = AppSurfaceLight,
    onSurface = AppOnSurfaceLight,
    surfaceVariant = AppSurfaceVariantLight,
    onSurfaceVariant = AppOnSurfaceVariantLight,
    outline = Color(0xFF8A98A2),
    outlineVariant = AppOutlineLight,
    error = WarningRed,
    onError = Color.White,
    errorContainer = WarningRedBg,
    onErrorContainer = Color(0xFF410002),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8FCBE8),
    onPrimary = Color(0xFF00344A),
    primaryContainer = BrandNavyContainerDark,
    onPrimaryContainer = Color(0xFFCDE9F7),
    secondary = Color(0xFFB6C5D0),
    onSecondary = Color(0xFF21313B),
    secondaryContainer = Color(0xFF33434E),
    onSecondaryContainer = Color(0xFFD2E2EC),
    tertiary = Color(0xFFFFB77C),
    onTertiary = Color(0xFF4A2500),
    tertiaryContainer = Color(0xFF6A3A00),
    onTertiaryContainer = Color(0xFFFFDCC2),
    background = AppBackgroundDark,
    onBackground = AppOnSurfaceDark,
    surface = AppSurfaceDark,
    onSurface = AppOnSurfaceDark,
    surfaceVariant = AppSurfaceVariantDark,
    onSurfaceVariant = AppOnSurfaceVariantDark,
    outline = Color(0xFF87949C),
    outlineVariant = AppOutlineDark,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 统一圆角：卡片 18dp、按钮 14dp、弹窗 28dp。 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 统一间距刻度（4dp 基准）。 */
object AppSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

@Composable
fun MyApplicationTheme(
    // 深色语义色令牌尚未完整覆盖，先固定浅色，避免半成品深色模式影响现场可读性。
    // 后续补齐语义色深色变体后，再恢复 isSystemInDarkTheme()。
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
