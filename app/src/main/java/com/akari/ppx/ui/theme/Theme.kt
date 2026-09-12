package com.akari.ppx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun BaseTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) darkColors(
        secondaryVariant = Color(0xFFFF87AA), primary = Color(0xFFFF87AA), secondary = Color(0xFFFFCF66),
        background = Color(0xFF17171C), surface = Color(0xFF23232B),
        onPrimary = Color(0xFF331321), onSecondary = Color(0xFF331321)
    ) else lightColors(
        secondaryVariant = Color(0xFFBB315F), primary = Color(0xFFBB315F), secondary = Color(0xFFAD6C00),
        background = Color(0xFFF7F6F9), surface = Color.White,
        onPrimary = Color.White, onSecondary = Color.White, onSurface = Color(0xFF24232B)
    )
    MaterialTheme(colors = palette, typography = Typography, shapes = Shapes, content = content)
}
