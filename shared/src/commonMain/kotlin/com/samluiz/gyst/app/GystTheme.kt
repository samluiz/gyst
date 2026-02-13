package com.samluiz.gyst.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GystColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFD166),
    onPrimary = Color(0xFF1A1202),
    primaryContainer = Color(0xFF4D3907),
    onPrimaryContainer = Color(0xFFFFE8B0),
    secondary = Color(0xFF73EBD2),
    onSecondary = Color(0xFF07201A),
    tertiary = Color(0xFFA7C0FF),
    onTertiary = Color(0xFF0D1C3A),
    background = Color(0xFF0A0D12),
    onBackground = Color(0xFFF4F7FD),
    surface = Color(0xFF131922),
    onSurface = Color(0xFFF1F5FB),
    surfaceVariant = Color(0xFF263346),
    onSurfaceVariant = Color(0xFFD7E2F3),
    error = Color(0xFFFF7B8B),
    onError = Color(0xFF2F0A10),
    outline = Color(0xFF4D617F),
)

private val GystLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF6A4E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD978),
    onPrimaryContainer = Color(0xFF231A00),
    secondary = Color(0xFF006A57),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF181B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D22),
    surfaceVariant = Color(0xFFE3E8F0),
    onSurfaceVariant = Color(0xFF4A5567),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val GystTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.2.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.7.sp,
    ),
)

private val GystShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun GystTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (isDark) GystColors else GystLightColors,
        typography = GystTypography,
        shapes = GystShapes,
        content = content,
    )
}
