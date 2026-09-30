package com.mobilegamma.cakesync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Bakery palette: raspberry, caramel and mint on cream. */
private val Light = lightColorScheme(
    primary = Color(0xFFB83B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFF8D5B3E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC9),
    onSecondaryContainer = Color(0xFF331200),
    tertiary = Color(0xFF3F7D6E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4EFDF),
    onTertiaryContainer = Color(0xFF002019),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF221A1C),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF221A1C),
    surfaceVariant = Color(0xFFF4DDE2),
    onSurfaceVariant = Color(0xFF524346),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F2),
    surfaceContainer = Color(0xFFFBEAED),
    surfaceContainerHigh = Color(0xFFF5E4E7),
    surfaceContainerHighest = Color(0xFFEFDFE2),
    outline = Color(0xFF857376),
    outlineVariant = Color(0xFFD6C2C5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB1C5),
    onPrimary = Color(0xFF650030),
    primaryContainer = Color(0xFF8E2447),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFFFB68F),
    onSecondary = Color(0xFF522300),
    secondaryContainer = Color(0xFF6D3F22),
    onSecondaryContainer = Color(0xFFFFDBC9),
    tertiary = Color(0xFFA8D3C3),
    onTertiary = Color(0xFF0B372D),
    tertiaryContainer = Color(0xFF265045),
    onTertiaryContainer = Color(0xFFC4EFDF),
    background = Color(0xFF1A1113),
    onBackground = Color(0xFFF0DEE0),
    surface = Color(0xFF1A1113),
    onSurface = Color(0xFFF0DEE0),
    surfaceVariant = Color(0xFF524346),
    onSurfaceVariant = Color(0xFFD6C2C5),
    surfaceContainerLowest = Color(0xFF140C0E),
    surfaceContainerLow = Color(0xFF22191B),
    surfaceContainer = Color(0xFF271D1F),
    surfaceContainerHigh = Color(0xFF322829),
    surfaceContainerHighest = Color(0xFF3D3234),
    outline = Color(0xFFA08C8F),
    outlineVariant = Color(0xFF524346),
)

private val Base = Typography()

/** Serif headings give a bakery-menu feel; body text stays in the clean system sans. */
private val CakeTypography = Base.copy(
    displaySmall = Base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val CakeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun CakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = CakeTypography,
        shapes = CakeShapes,
        content = content,
    )
}

/** Gradients used for hero banners and creation cards. */
object CakeBrush {
    val hero = Brush.linearGradient(listOf(Color(0xFFFF8FAB), Color(0xFFFFB199), Color(0xFFFFD6A5)))
    val reel = Brush.linearGradient(listOf(Color(0xFFFF6F91), Color(0xFFFF9671)))
    val collage = Brush.linearGradient(listOf(Color(0xFF845EC2), Color(0xFFD65DB1)))
    val filter = Brush.linearGradient(listOf(Color(0xFFFFC75F), Color(0xFFFF9671)))
    val brand = Brush.linearGradient(listOf(Color(0xFF2C73D2), Color(0xFF0089BA)))
    val white = Brush.linearGradient(listOf(Color(0xFF00C9A7), Color(0xFF4D8076)))
    val crop = Brush.linearGradient(listOf(Color(0xFF9B89B3), Color(0xFF6F5F90)))
    val share = Brush.linearGradient(listOf(Color(0xFF008F7A), Color(0xFF0081CF)))
    val catalog = Brush.linearGradient(listOf(Color(0xFFB0A8B9), Color(0xFF4B4453)))
}
