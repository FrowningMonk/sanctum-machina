package app.sanctum.machina.ui.modelmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onLoad: (String) -> Unit,
    viewModel: ModelManagerViewModel = hiltViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.OpenChat -> onLoad(event.modelName)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.model_manager_title)) })
        },
    ) { innerPadding ->
        ModelList(
            models = models,
            contentPadding = innerPadding,
            onDownload = viewModel::onDownload,
            onCancel = viewModel::onCancel,
            onLoad = viewModel::onLoad,
        )
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
            ModelStatusRow(
                downloadStatus = entry.downloadStatus,
                onDownload = onDownload,
                onCancel = onCancel,
                onLoad = onLoad,
            )
        }
    }
}

@Composable
private fun ModelStatusRow(
    downloadStatus: ModelDownloadStatus,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onLoad: () -> Unit,
) {
    when (downloadStatus.status) {
        ModelDownloadStatusType.NOT_DOWNLOADED,
        ModelDownloadStatusType.PARTIALLY_DOWNLOADED -> {
            StatusBadge(text = stringResource(R.string.model_status_not_downloaded))
            Spacer(Modifier.height(4.dp))
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
                        (downloadStatus.receivedBytes.toFloat() /
                            downloadStatus.totalBytes.coerceAtLeast(1))
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
            Button(onClick = onLoad, enabled = true) {
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
    Row {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}
