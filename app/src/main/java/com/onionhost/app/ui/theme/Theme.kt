package com.onionhost.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF64D6BE),
    secondary = OnionGreen,
    tertiary = Color(0xFFB6E9DF),
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    onPrimary = Color(0xFF00382F),
    onSecondary = Color(0xFF003B1B),
    onTertiary = Color(0xFF073C34),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF526365),
    error = Color(0xFFFFB4AB)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF075E54),
    secondary = Color(0xFF25A957),
    tertiary = OnionGreen,
    background = ChatBackground,
    surface = ChatSurface,
    surfaceVariant = Color(0xFFF0F5F4),
    onPrimary = Color.White,
    onSecondary = Color(0xFF052B1A),
    onTertiary = Color(0xFF003B1B),
    onBackground = ChatText,
    onSurface = ChatText,
    onSurfaceVariant = ChatSecondaryText,
    outline = ChatDivider,
    error = Color(0xFFB3261E)
)

@Composable
fun OnionHostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
