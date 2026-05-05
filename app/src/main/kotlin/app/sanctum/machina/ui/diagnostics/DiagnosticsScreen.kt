package app.sanctum.machina.ui.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sanctum.machina.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// Suggested filename pattern for SAF export, mirrors AboutScreen / CrashReportActivity.
private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmm"

/**
 * Phase-3.5 diagnostics screen — pinned in the drawer between «Модели» and «О приложении».
 *
 * Two sections:
 *  * **RAM** — last-init snapshot (one of four variants per Decision 12) and
 *    free-RAM (1-second tick driven by [DiagnosticsViewModel]).
 *  * **Логи** — SAF-export of the diagnostic `.txt` (header + crash.log +
 *    errors.log + errors.log.1 + logcat). Migrated whole from `AboutScreen`
 *    per Decision 11 — same snackbar UX.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val successMessage = stringResource(R.string.log_export_success_toast)
    val errorMessage = stringResource(R.string.log_export_error_toast)
    var launching by remember { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.diagnostics_title)) },
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
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.diagnostics_section_ram),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = state.lastInitText)
                Text(text = state.freeRamText)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.diagnostics_section_logs),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(
                    onClick = {
                        launching = true
                        val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ROOT)
                            .format(Date())
                        saveLogLauncher.launch("sanctum-log-$timestamp.txt")
                    },
                    enabled = !launching,
                ) {
                    Text(stringResource(R.string.log_export_save_button))
                }
            }
        }
    }
}
