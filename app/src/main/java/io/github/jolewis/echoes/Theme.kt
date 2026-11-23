package io.github.jolewis.echoes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 设计规范 v1.0「深空静谧 Quiet Cosmic」的色板。 */
object Tokens {
    val Bg = Color(0xFF101827)
    val Surface = Color(0xFF1C2940)
    val Elevated = Color(0xFF253550)
    val Primary = Color(0xFF97BBFF)
    val Secondary = Color(0xFFCEB6FF)
    val Accent = Color(0xFFE7C68C)
    val OnBg = Color(0xFFF3F6FD)
    val Muted = Color(0xFFB3C2D9)
    val Divider = Color(0xFF2A3A56)
}

private val Scheme = darkColorScheme(
    primary = Tokens.Primary,
    onPrimary = Color(0xFF0B1530),
    secondary = Tokens.Secondary,
    onSecondary = Color(0xFF1A1030),
    tertiary = Tokens.Accent,
    background = Tokens.Bg,
    onBackground = Tokens.OnBg,
    surface = Tokens.Surface,
    onSurface = Tokens.OnBg,
    surfaceVariant = Tokens.Elevated,
    onSurfaceVariant = Tokens.Muted,
    outline = Tokens.Divider,
)

@Composable
fun EchoesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
