package app.sanctum.machina.ui.about

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.sanctum.machina.BuildConfig
import app.sanctum.machina.R
import app.sanctum.machina.logexport.ExportSource
import app.sanctum.machina.logexport.LogExportManager
import app.sanctum.machina.logexport.TapCounter
import app.sanctum.machina.ui.chat.SafeMarkdown
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Hardcoded asset name — never from state/user input (D17 path-traversal protection).
private const val ABOUT_ASSET = "about.md"

// Suggested filename pattern for SAF export, mirrors CrashReportActivity (Task 4).
private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmm"

/**
 * Hilt-injected ViewModel that bridges [AboutScreen] to [LogExportManager].
 *
 * Kept in this file rather than a standalone `AboutViewModel.kt` because its
 * entire surface is one `suspend fun` that wraps two calls to
 * [LogExportManager] — splitting it out would just add a file without adding
 * reuse. Obtained in the composable via [hiltViewModel] through the
 * `MainActivity`'s `LocalViewModelStoreOwner`.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val logExportManager: LogExportManager,
) : ViewModel() {

    /**
     * Builds the About-path export (live logcat) and writes it to the
     * SAF-picked URI. Returns [Result.failure] wrapping the original
     * [IOException] so the caller can render one Snackbar for the write path
     * without leaking the exception or its stack trace into UI state.
     */
    suspend fun buildAndWrite(uri: Uri): Result<Unit> = try {
        val content = logExportManager.buildExport(ExportSource.About)
        logExportManager.writeTo(uri, content)
        Result.success(Unit)
    } catch (e: IOException) {
        Result.failure(e)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val fallback = stringResource(R.string.about_load_failed)
    val successMessage = stringResource(R.string.log_export_success_toast)
    val errorMessage = stringResource(R.string.log_export_error_toast)

    val markdown by produceState(initialValue = "", key1 = context, key2 = fallback) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open(ABOUT_ASSET).bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                fallback
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var launching by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    val tapCounter = remember { TapCounter(nowNanos = { System.nanoTime() }) }

    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        coroutineScope.launch {
            try {
                if (uri != null) {
                    viewModel.buildAndWrite(uri)
                        .onSuccess { snackbarHostState.showSnackbar(successMessage) }
                        .onFailure { snackbarHostState.showSnackbar(errorMessage) }
                }
            } finally {
                launching = false
            }
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            Text(
                text = stringResource(R.string.about_diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Button(
                onClick = {
                    launching = true
                    val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ROOT).format(Date())
                    saveLogLauncher.launch("sanctum-log-$timestamp.txt")
                },
                enabled = !launching,
            ) {
                Text(stringResource(R.string.log_export_save_button))
            }
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
