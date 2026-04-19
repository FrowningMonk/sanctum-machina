package app.sanctum.machina.ui.modelmanager

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sanctum.machina.R
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.crash.RestartCrashBanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// Suggested filename pattern for SAF export — mirrors AboutScreen (Task 6)
// and CrashReportActivity (Task 4); Locale.ROOT keeps digits ASCII.
private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmm"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onLoad: (String) -> Unit,
    onAbout: () -> Unit,
    viewModel: ModelManagerViewModel = hiltViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val hasUnresolvedCrash by viewModel.hasUnresolvedCrash.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.OpenChat -> onLoad(event.modelName)
            }
        }
    }

    // Decision 6: filesystem is the source of truth. Re-read on entry so an
    // external change (a crash between sessions, manual file deletion) is
    // picked up even though CrashState is a long-lived @Singleton.
    LaunchedEffect(Unit) { viewModel.refreshCrashState() }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val successMessage = stringResource(R.string.log_export_success_toast)
    val errorMessage = stringResource(R.string.log_export_error_toast)

    // In-flight guard for repeated save-log taps before the SAF dialog returns.
    // Reset in `finally` below regardless of the result branch.
    var launching by remember { mutableStateOf(false) }

    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        coroutineScope.launch {
            try {
                if (uri != null) {
                    viewModel.saveLogAndClearCrash(uri)
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
                title = { Text(stringResource(R.string.model_manager_title)) },
                actions = {
                    IconButton(onClick = onAbout) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.action_about),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (hasUnresolvedCrash) {
                RestartCrashBanner(
                    launching = launching,
                    onSaveClick = {
                        launching = true
                        val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ROOT).format(Date())
                        saveLogLauncher.launch("sanctum-log-$timestamp.txt")
                    },
                    onDismissClick = viewModel::dismissCrashBanner,
                )
            }
            ModelList(
                models = models,
                contentPadding = PaddingValues(0.dp),
                onDownload = viewModel::onDownload,
                onCancel = viewModel::onCancel,
                onLoad = viewModel::onLoad,
            )
        }
    }
}

@Composable
private fun ModelList(
    models: List<ModelEntry>,
    contentPadding: PaddingValues,
    onDownload: (ModelEntry) -> Unit,
    onCancel: (String) -> Unit,
    onLoad: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(models, key = { it.model.name }) { entry ->
            ModelCard(
                entry = entry,
                onDownload = { onDownload(entry) },
                onCancel = { onCancel(entry.model.name) },
                onLoad = { onLoad(entry.model.name) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entry.model.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.model_size_gb_format,
                    entry.model.sizeInBytes / 1_073_741_824.0f,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelStatusSection(
                downloadStatus = entry.downloadStatus,
                onDownload = onDownload,
                onCancel = onCancel,
                onLoad = onLoad,
            )
        }
    }
}

@Composable
private fun ModelStatusSection(
    downloadStatus: ModelDownloadStatus,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onLoad: () -> Unit,
) {
    when (downloadStatus.status) {
        ModelDownloadStatusType.NOT_DOWNLOADED,
        ModelDownloadStatusType.PARTIALLY_DOWNLOADED -> {
            StatusBadge(text = stringResource(R.string.model_status_not_downloaded))
            Button(onClick = onDownload) {
                Text(stringResource(R.string.btn_download))
            }
        }
        ModelDownloadStatusType.IN_PROGRESS,
        ModelDownloadStatusType.UNZIPPING -> {
            StatusBadge(text = stringResource(R.string.model_status_downloading))
            if (downloadStatus.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (downloadStatus.receivedBytes.toFloat() / downloadStatus.totalBytes)
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.model_download_progress_format,
                        (downloadStatus.receivedBytes / 1_048_576L).toInt(),
                        (downloadStatus.totalBytes / 1_048_576L).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = onCancel) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
        ModelDownloadStatusType.SUCCEEDED -> {
            StatusBadge(text = stringResource(R.string.model_status_downloaded))
            Button(onClick = onLoad) {
                Text(stringResource(R.string.btn_load))
            }
        }
        ModelDownloadStatusType.FAILED -> {
            StatusBadge(
                text = stringResource(R.string.model_status_failed),
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.model_error_prefix, downloadStatus.errorMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onDownload) {
                Text(stringResource(R.string.btn_retry))
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}
