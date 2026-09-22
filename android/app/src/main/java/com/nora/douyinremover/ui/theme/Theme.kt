package com.nora.douyinremover.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.nora.douyinremover.R

/**
 * 霞鹜文楷 轻便版（LXGW WenKai Lite），SIL OFL 1.1 开源协议。
 * https://github.com/lxgw/LxgwWenKai-Lite
 */
val WenKaiFamily = FontFamily(
    Font(R.font.lxgw_wenkai_lite_regular, FontWeight.Normal),
    Font(R.font.lxgw_wenkai_lite_regular, FontWeight.Medium),
    Font(R.font.lxgw_wenkai_lite_regular, FontWeight.SemiBold),
    Font(R.font.lxgw_wenkai_lite_regular, FontWeight.Bold)
)

/**
 * B 站风格 Typography：标题加粗、正文舒展。
 */
private val WenKaiTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = WenKaiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
)

/**
 * B 站配色体系：
 * - 主色：哔哩哔哩粉 #FB7299
 * - 辅色：天蓝 #00AEEC
 * - 点缀：动态红 #FF6699 / 大会员金 #FFB027
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFFB7299),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE9F0),
    onPrimaryContainer = Color(0xFF5C0F2E),
    secondary = Color(0xFF00AEEC),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F5FF),
    onSecondaryContainer = Color(0xFF00344A),
    tertiary = Color(0xFFFFB027),
    onTertiary = Color(0xFF442B00),
    background = Color(0xFFF6F7F8),
    onBackground = Color(0xFF18191C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18191C),
    surfaceVariant = Color(0xFFF1F2F3),
    onSurfaceVariant = Color(0xFF9499A0),
    surfaceTint = Color(0xFFFB7299),
    inverseSurface = Color(0xFF2B2E33),
    inverseOnSurface = Color(0xFFF1F2F3),
    error = Color(0xFFFA5A5A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFECEC),
    onErrorContainer = Color(0xFF6B0000),
    outline = Color(0xFFE3E5E7),
    outlineVariant = Color(0xFFF1F2F3)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFB7299),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6B2440),
    onPrimaryContainer = Color(0xFFFFD9E4),
    secondary = Color(0xFF00AEEC),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF004B66),
    onSecondaryContainer = Color(0xFFC7ECFF),
    tertiary = Color(0xFFFFB027),
    onTertiary = Color(0xFF442B00),
    background = Color(0xFF111214),
    onBackground = Color(0xFFE3E5E7),
    surface = Color(0xFF1D1E20),
    onSurface = Color(0xFFE3E5E7),
    surfaceVariant = Color(0xFF2B2E33),
    onSurfaceVariant = Color(0xFF9499A0),
    surfaceTint = Color(0xFFFB7299),
    inverseSurface = Color(0xFFE3E5E7),
    inverseOnSurface = Color(0xFF2B2E33),
    error = Color(0xFFFF8585),
    onError = Color(0xFF5C0000),
    errorContainer = Color(0xFF6B0000),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = Color(0xFF3A3D42),
    outlineVariant = Color(0xFF2B2E33)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun DouyinRemoverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WenKaiTypography,
        shapes = AppShapes,
        content = content
    )
}
