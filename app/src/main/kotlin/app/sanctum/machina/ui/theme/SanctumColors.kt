package app.sanctum.machina.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

data class SanctumColors(
    val bg: Color,
    val bgSunk: Color,
    val bgRaised: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkDim: Color,
    val hair: Color,
    val hairStrong: Color,
    val userBubble: Color,
    val accent: Color,
    val accentInk: Color,
    val danger: Color,
    val incognitoBg: Color,
    val incognitoInk: Color,
    val incognitoEdge: Color,
)

val SanctumLightColors = SanctumColors(
    bg = ParchmentBg,
    bgSunk = ParchmentBgSunk,
    bgRaised = ParchmentBgRaised,
    ink = InkDark,
    inkMuted = InkMutedLight,
    inkDim = InkDimLight,
    hair = HairLight,
    hairStrong = HairStrongLight,
    userBubble = UserBubbleLight,
    accent = SanctumAccentLight,
    accentInk = SanctumAccentInkLight,
    danger = SanctumDangerLight,
    incognitoBg = IncognitoBgLight,
    incognitoInk = IncognitoInkLight,
    incognitoEdge = SanctumIncognitoEdgeLight,
)

val SanctumDarkColors = SanctumColors(
    bg = NightBg,
    bgSunk = NightBgSunk,
    bgRaised = NightBgRaised,
    ink = InkLight,
    inkMuted = InkMutedDark,
    inkDim = InkDimDark,
    hair = HairDark,
    hairStrong = HairStrongDark,
    userBubble = UserBubbleDark,
    accent = SanctumAccentDark,
    accentInk = SanctumAccentInkDark,
    danger = SanctumDangerDark,
    incognitoBg = IncognitoBgDark,
    incognitoInk = IncognitoInkDark,
    incognitoEdge = SanctumIncognitoEdgeDark,
)

val LocalSanctumColors = compositionLocalOf { SanctumLightColors }

val MaterialTheme.sanctumColors: SanctumColors
    @Composable get() = LocalSanctumColors.current
