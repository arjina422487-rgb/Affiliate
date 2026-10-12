package com.ownerlink.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared lightweight glass treatment: no bitmap blur or per-frame background rendering. */
@Composable
internal fun GlassBackdrop(content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background).drawBehind {
        drawCircle(Brush.radialGradient(listOf(colors.primary.copy(alpha = .18f), Color.Transparent),
            center = Offset(size.width * .9f, size.height * .08f), radius = size.width * .95f),
            radius = size.width * .95f, center = Offset(size.width * .9f, size.height * .08f))
        drawCircle(Brush.radialGradient(listOf(colors.tertiary.copy(alpha = .13f), Color.Transparent),
            center = Offset(0f, size.height * .72f), radius = size.width * .85f),
            radius = size.width * .85f, center = Offset(0f, size.height * .72f))
    }, content = content)
}

@Composable
internal fun Modifier.glassPanel(radius: Int = 28): Modifier {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(radius.dp)
    return clip(shape).background(Brush.linearGradient(listOf(
        colors.surface.copy(alpha = .94f), colors.surface.copy(alpha = .78f),
        colors.primaryContainer.copy(alpha = .35f))))
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .55f),
            colors.outline.copy(alpha = .13f), colors.primary.copy(alpha = .20f))), shape)
}

@Composable
internal fun AdaptivePane(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxSize(), content = content)
    }
}
