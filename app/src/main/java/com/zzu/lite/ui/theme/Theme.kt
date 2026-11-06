package com.zzu.lite.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 郑大绿
val ZzuGreen = Color(0xFF1B5E43)
val ZzuGreenDark = Color(0xFF0F3D2C)
val ZzuGreenContainer = Color(0xFFA8D5BE)

private val LightColors = lightColorScheme(
    primary = ZzuGreen,
    onPrimary = Color.White,
    primaryContainer = ZzuGreenContainer,
    onPrimaryContainer = ZzuGreenDark,
    secondary = Color(0xFF4A6357),
    secondaryContainer = Color(0xFFCDE9D8),
    onSecondaryContainer = Color(0xFF07271A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DD8B0),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF1B5E43),
    onPrimaryContainer = ZzuGreenContainer,
    secondary = Color(0xFFB1CCBD),
    secondaryContainer = Color(0xFF324B3E),
    onSecondaryContainer = Color(0xFFCDE9D8)
)

@Composable
fun ZzuLiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
