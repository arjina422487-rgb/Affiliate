package com.ownerlink.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object PrivatChatTokens {
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 16.dp
    val SpaceLg = 24.dp
    val RadiusSm = 10.dp
    val RadiusMd = 18.dp
    val RadiusLg = 28.dp
    val TouchTarget = 48.dp
    val Avatar = 48.dp
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFFCF4058), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDFE5), onPrimaryContainer = Color(0xFF6B1830),
    secondary = Color(0xFF3779B8), secondaryContainer = Color(0xFFE3F0FF),
    tertiary = Color(0xFFFF8995), background = Color(0xFFF5F8FD), surface = Color(0xFFFEFEFF),
    surfaceVariant = Color(0xFFEDF2F9), onSurface = Color(0xFF102345),
    onSurfaceVariant = Color(0xFF536A88), outline = Color(0xFF8796AD), error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF9CA9), onPrimary = Color(0xFF581426),
    primaryContainer = Color(0xFF5C283A), onPrimaryContainer = Color(0xFFFFDDE5),
    secondary = Color(0xFF9BCAFF), secondaryContainer = Color(0xFF223B59),
    tertiary = Color(0xFFF2A3AD), background = Color(0xFF101A2C), surface = Color(0xFF1C2940),
    surfaceVariant = Color(0xFF2B3950), onSurface = Color(0xFFF1F5FF),
    onSurfaceVariant = Color(0xFFB7C8DF), outline = Color(0xFF8E9CB3), error = Color(0xFFFFB4AB),
)

private val PrivatTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 39.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

private val PrivatShapes = Shapes(
    small = RoundedCornerShape(PrivatChatTokens.RadiusSm),
    medium = RoundedCornerShape(PrivatChatTokens.RadiusMd),
    large = RoundedCornerShape(PrivatChatTokens.RadiusLg),
)

@Composable
fun PrivatChatTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val target = if (dark) DarkScheme else LightScheme
    @Composable fun animated(color: Color) = animateColorAsState(color, tween(260), label = "theme-color").value
    val scheme = target.copy(
        primary = animated(target.primary), onPrimary = animated(target.onPrimary),
        primaryContainer = animated(target.primaryContainer), onPrimaryContainer = animated(target.onPrimaryContainer),
        secondary = animated(target.secondary), background = animated(target.background), surface = animated(target.surface),
        surfaceVariant = animated(target.surfaceVariant), onSurface = animated(target.onSurface),
        onSurfaceVariant = animated(target.onSurfaceVariant), outline = animated(target.outline), error = animated(target.error),
    )
    MaterialTheme(
        colorScheme = scheme,
        shapes = PrivatShapes,
        typography = PrivatTypography,
        content = content,
    )
}
