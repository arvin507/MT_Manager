package dev.fileforge.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF16624F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBCEBD9),
    onPrimaryContainer = Color(0xFF00211A),
    secondary = Color(0xFF4B635B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9DD),
    onSecondaryContainer = Color(0xFF07201A),
    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDFA0),
    onTertiaryContainer = Color(0xFF271900),
    background = Color(0xFFF7F8F5),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF7F8F5),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDCE5DF),
    onSurfaceVariant = Color(0xFF4A5551),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3EF),
    surfaceContainer = Color(0xFFEBEEE9),
    surfaceContainerHigh = Color(0xFFE5E9E4),
    outline = Color(0xFF75807B),
    outlineVariant = Color(0xFFD5DCD7),
    error = Color(0xFFB3261E)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val AppTypography = Typography().run {
    copy(
        titleMedium = titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
    )
}

@Composable
fun FileForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
