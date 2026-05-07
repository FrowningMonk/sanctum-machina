package app.sanctum.machina.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sanctum.machina.BuildConfig
import app.sanctum.machina.R
import app.sanctum.machina.logexport.TapCounter
import app.sanctum.machina.ui.chat.SafeMarkdown
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val fallback = stringResource(R.string.about_load_failed)
    // Asset filename comes from a string resource (about_en.md / about_ru.md
    // depending on locale). The value is hardcoded in strings.xml, never
    // composed from user input — D17 path-traversal protection preserved.
    val assetName = stringResource(R.string.about_asset)

    val markdown by produceState(initialValue = "", key1 = assetName, key2 = fallback) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open(assetName).bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                fallback
            }
        }
    }

    var showDialog by remember { mutableStateOf(false) }
    val tapCounter = remember { TapCounter(nowNanos = { System.nanoTime() }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SafeMarkdown(text = markdown)
            HorizontalDivider()
            AboutFooter(
                onVersionTap = { if (tapCounter.tap()) showDialog = true },
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.dev_crash_dialog_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Order matters (Decision 9): close the dialog before throwing so
                        // Compose exits the current composition cleanly. The throw runs
                        // directly on the UI thread — no Handler/Thread/launch wrapper —
                        // so it propagates into CrashHandler via the real UI-callback path.
                        showDialog = false
                        throw RuntimeException("test crash from About")
                    },
                ) {
                    Text(stringResource(R.string.dev_crash_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
private fun AboutFooter(onVersionTap: () -> Unit) {
    val versionName = BuildConfig.VERSION_NAME
        .takeIf { it.isNotBlank() }
        ?: stringResource(R.string.about_version_unknown)
    val versionInteractionSource = remember { MutableInteractionSource() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.about_version_format, versionName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(
                interactionSource = versionInteractionSource,
                indication = null,
                onClick = onVersionTap,
            ),
        )
        Text(
            text = stringResource(R.string.about_attribution),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
