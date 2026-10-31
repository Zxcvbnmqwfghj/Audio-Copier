package com.audiocopier.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.*
import com.audiocopier.app.R

object Design {
    val Blue = Color(0xFF007DB9)
    val Nav = Color(0xFF1500FF)
    val Red = Color(0xFFD33339)
    val Help = Color(0xFF888888)
    val ButtonLeft = Color(0xFF1675AA)
    val ButtonRight = Color(0xFF50A5D8)
}
val LocalDark = staticCompositionLocalOf { false }
@OptIn(ExperimentalTextApi::class)
@Composable
fun AudioCopierTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val family = FontFamily(
        Font(R.font.cairo, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.slant(0f))),
        Font(R.font.cairo, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.slant(0f))),
        Font(R.font.cairo, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.slant(0f))),
    )
    val typography = Typography().run { copy(
        displayLarge = displayLarge.copy(fontFamily = family), displayMedium = displayMedium.copy(fontFamily = family), displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family), headlineMedium = headlineMedium.copy(fontFamily = family), headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family), titleMedium = titleMedium.copy(fontFamily = family), titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family), bodyMedium = bodyMedium.copy(fontFamily = family), bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family), labelMedium = labelMedium.copy(fontFamily = family), labelSmall = labelSmall.copy(fontFamily = family),
    ) }
    val ink = if (dark) Color.White else Color.Black
    val paper = if (dark) Color(0xFF080A0C) else Color.White
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme.copy(
            primary = Design.Blue, onPrimary = ink, secondary = Design.Blue,
            background = paper, onBackground = ink, surface = paper, onSurface = ink,
            surfaceVariant = paper, onSurfaceVariant = ink, outline = ink, error = Design.Red,
        ), typography = typography, content = content)
    }
}
