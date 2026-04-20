package app.sanctum.machina.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val SanctumShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

private fun sanctumLightColorScheme() = lightColorScheme(
    background = ParchmentBg,
    surface = ParchmentBgRaised,
    surfaceVariant = ParchmentBgSunk,
    onBackground = InkDark,
    onSurface = InkDark,
    onSurfaceVariant = InkMutedLight,
    primary = SanctumAccentLight,
    onPrimary = SanctumAccentInkLight,
    error = SanctumDangerLight,
    outline = HairStrongLight,
    outlineVariant = HairLight,
)

private fun sanctumDarkColorScheme() = darkColorScheme(
    background = NightBg,
    surface = NightBgRaised,
    surfaceVariant = NightBgSunk,
    onBackground = InkLight,
    onSurface = InkLight,
    onSurfaceVariant = InkMutedDark,
    primary = SanctumAccentDark,
    onPrimary = SanctumAccentInkDark,
    error = SanctumDangerDark,
    outline = HairStrongDark,
    outlineVariant = HairDark,
)

@Composable
fun SanctumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val sanctumColors = if (darkTheme) SanctumDarkColors else SanctumLightColors
    val colorScheme = if (darkTheme) sanctumDarkColorScheme() else sanctumLightColorScheme()

    CompositionLocalProvider(LocalSanctumColors provides sanctumColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SanctumTypography,
            shapes = SanctumShapes,
            content = content,
        )
    }
}
