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
    primary = Color(0xFF5355DE), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E4FF), onPrimaryContainer = Color(0xFF252466),
    tertiary = Color(0xFF008A91), secondary = Color(0xFF536677), background = Color(0xFFF4F6FC), surface = Color(0xFFF4F6FC),
    surfaceVariant = Color(0xFFE8ECF6), onSurface = Color(0xFF171D32),
    onSurfaceVariant = Color(0xFF596078), outline = Color(0xFF767781), error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB5B5FF), onPrimary = Color(0xFF232154),
    primaryContainer = Color(0xFF32305E), onPrimaryContainer = Color(0xFFE5E4FF),
    tertiary = Color(0xFF74D9D6), secondary = Color(0xFFAFCCDA), background = Color(0xFF0B1020), surface = Color(0xFF0B1020),
    surfaceVariant = Color(0xFF20283E), onSurface = Color(0xFFF0F2FF),
    onSurfaceVariant = Color(0xFFB7BED5), outline = Color(0xFF90909A), error = Color(0xFFFFB4AB),
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
