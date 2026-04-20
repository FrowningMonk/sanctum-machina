package app.sanctum.machina.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import app.sanctum.machina.R

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val CormorantGaramond = GoogleFont("Cormorant Garamond")
private val Inter = GoogleFont("Inter")
private val JetBrainsMono = GoogleFont("JetBrains Mono")

val CormorantGaramondFamily = FontFamily(
    Font(googleFont = CormorantGaramond, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = CormorantGaramond, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = CormorantGaramond, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = CormorantGaramond, fontProvider = provider, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(googleFont = CormorantGaramond, fontProvider = provider, weight = FontWeight.Medium, style = FontStyle.Italic),
)

val InterFamily = FontFamily(
    Font(googleFont = Inter, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = Inter, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = Inter, fontProvider = provider, weight = FontWeight.SemiBold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(googleFont = JetBrainsMono, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = JetBrainsMono, fontProvider = provider, weight = FontWeight.Medium),
)

val SanctumTypography = Typography(
    // Display — Cormorant Garamond 500, 40sp
    displayLarge = TextStyle(
        fontFamily = CormorantGaramondFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.01).sp,
    ),
    // Headline large — Cormorant 26sp
    headlineLarge = TextStyle(
        fontFamily = CormorantGaramondFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.01).sp,
    ),
    // Headline medium — Cormorant 22sp
    headlineMedium = TextStyle(
        fontFamily = CormorantGaramondFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).sp,
    ),
    // Headline small — Cormorant 22sp secondary
    headlineSmall = TextStyle(
        fontFamily = CormorantGaramondFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    // Body — Inter
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // Kicker / chips — JetBrains Mono 10sp
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = TextUnit(0.18f, TextUnitType.Em),
    ),
)
