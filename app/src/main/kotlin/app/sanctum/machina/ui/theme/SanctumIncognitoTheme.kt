package app.sanctum.machina.ui.theme

import androidx.compose.runtime.Composable

@Composable
fun SanctumIncognitoTheme(content: @Composable () -> Unit) {
    SanctumTheme(darkTheme = true, content = content)
}
