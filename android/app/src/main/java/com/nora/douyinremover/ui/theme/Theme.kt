package com.nora.douyinremover.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A84FF),
    secondary = Color(0xFF5E5CE6),
    tertiary = Color(0xFFFF9F0A),
    background = Color(0xFFF7F7FA),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFFF3B30)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    secondary = Color(0xFF5E5CE6),
    tertiary = Color(0xFFFF9F0A),
    background = Color(0xFF101014),
    surface = Color(0xFF1C1C22),
    error = Color(0xFFFF453A)
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
        content = content
    )
}
