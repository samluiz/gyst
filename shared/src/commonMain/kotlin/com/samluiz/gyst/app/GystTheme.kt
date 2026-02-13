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
    primary = Color(0xFFE4E4E7),
    onPrimary = Color(0xFF111113),
    primaryContainer = Color(0xFF2A2A2E),
    onPrimaryContainer = Color(0xFFF3F3F5),
    secondary = Color(0xFFB8B8BF),
    onSecondary = Color(0xFF121214),
    tertiary = Color(0xFFCBCBD2),
    onTertiary = Color(0xFF121214),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFF6F6F7),
    surface = Color(0xFF17171A),
    onSurface = Color(0xFFF0F0F2),
    surfaceVariant = Color(0xFF232328),
    onSurfaceVariant = Color(0xFFD0D0D6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF4A4A52),
)

private val GystLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1E1E22),
    onPrimary = Color(0xFFF9F9FA),
    primaryContainer = Color(0xFFE7E7EA),
    onPrimaryContainer = Color(0xFF19191C),
    secondary = Color(0xFF3A3A40),
    onSecondary = Color(0xFFF8F8FA),
    background = Color(0xFFF4F4F6),
    onBackground = Color(0xFF131316),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFE9E9ED),
    onSurfaceVariant = Color(0xFF47474F),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF9B9BA5),
)

private val GystTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.2.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
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
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.7.sp,
    ),
)

private val GystShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
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
