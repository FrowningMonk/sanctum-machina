package app.sanctum.machina.ui.modelmanager

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import app.sanctum.machina.ui.SanctumIcons
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
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val hasUnresolvedCrash by viewModel.hasUnresolvedCrash.collectAsStateWithLifecycle()
    val defaultModelId by viewModel.defaultModelId.collectAsStateWithLifecycle()
    val embedderDeleteDialog by viewModel.embedderDeleteDialog.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.OpenQuickChat -> onLoad(event.modelId)
                is NavEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // Decision 6: filesystem is the source of truth. Re-read on entry so an
    // external change (a crash between sessions, manual file deletion) is
    // picked up even though CrashState is a long-lived @Singleton.
    LaunchedEffect(Unit) { viewModel.refreshCrashState() }

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
                rows = rows,
                defaultModelId = defaultModelId,
                contentPadding = PaddingValues(0.dp),
                onDownload = viewModel::onDownload,
                onCancel = viewModel::onCancel,
                onLoad = viewModel::onLoad,
                onSetDefault = viewModel::setDefaultModel,
                onDeleteEmbedder = viewModel::onDeleteEmbedderClick,
            )
        }

        embedderDeleteDialog?.let { dialog ->
            EmbedderDeleteDialog(
                state = dialog,
                onConfirm = viewModel::onConfirmEmbedderDelete,
                onDismiss = viewModel::onDismissEmbedderDelete,
            )
        }
    }
}

@Composable
private fun EmbedderDeleteDialog(
    state: EmbedderDeleteDialogState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val noUndoLine = stringResource(R.string.model_manager_embedder_delete_warning_no_undo)
    val body: String = when (state) {
        is EmbedderDeleteDialogState.Confirm ->
            stringResource(R.string.model_manager_embedder_delete_body_empty)
        is EmbedderDeleteDialogState.WarningWithProjects ->
            stringResource(
                R.string.model_manager_embedder_delete_body_with_projects,
                // Names are sanitised + bounded per [sanitizeProjectNameForDialog] so a
                // hostile project name (BiDi override, control chars, extreme length) can't
                // bury / flip the «Действие необратимо» line that follows.
                state.projectNames.joinToString(separator = ", ") {
                    sanitizeProjectNameForDialog(it)
                },
            )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_manager_embedder_delete_title)) },
        text = {
            // Explicit verticalScroll on the body Column (tasks/10.md line 144). M3
            // AlertDialog's text slot does NOT scroll its slot content by default; without
            // this, a list of 10+ projects buries the «Действие необратимо» line behind the
            // action buttons on short viewports.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(body)
                Text(noUndoLine)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.model_manager_embedder_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.model_manager_embedder_delete_cancel))
            }
        },
    )
}

/**
 * Task 10 / security-auditor-1 M1: defence-in-depth sanitisation of user-controlled project
 * names when rendered inside the embedder delete-confirm dialog (a destructive-action
 * surface where misleading the user is the threat model). Strips:
 *
 *  - **BiDi/Unicode formatting controls** (`‎…‏`, `‪…‮`, `⁦…⁩`)
 *    — these can flip surrounding text or reorder the «Действие необратимо» warning.
 *  - **Control / line-break characters** (` …`, ``) — newlines disrupt
 *    the Column layout and let an attacker hide content past the visible region.
 *  - **Length cap** (160 chars, 1-based truncation marker `…`) — bounds the dialog body
 *    even when sister code (e.g. `ProjectCreateViewModel`) hasn't yet capped at input.
 *
 * Intentionally *not* attempting normalisation / homoglyph filtering — that's a far broader
 * surface and belongs at the input boundary in `ProjectCreateViewModel`. This is a local
 * hardening of the destructive-action render path.
 */
private const val PROJECT_NAME_DISPLAY_CAP = 160
private val DIALOG_UNSAFE_CHARS = Regex(
    // Order: BiDi formatting controls, then C0/C1 control chars (excludes printable ASCII).
    "[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u0000-\\u001F\\u007F]"
)
private fun sanitizeProjectNameForDialog(name: String): String {
    val stripped = DIALOG_UNSAFE_CHARS.replace(name, "")
    return if (stripped.length > PROJECT_NAME_DISPLAY_CAP) {
        stripped.take(PROJECT_NAME_DISPLAY_CAP) + "…"
    } else {
        stripped
    }
}

@Composable
private fun ModelList(
    rows: List<ModelRowState>,
    defaultModelId: String,
    contentPadding: PaddingValues,
    onDownload: (ModelEntry) -> Unit,
    onCancel: (String) -> Unit,
    onLoad: (String) -> Unit,
    onSetDefault: (String, String) -> Unit,
    onDeleteEmbedder: (String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Compose identity keys on Model.name (guaranteed unique in the registry); isDefault/onSetDefault
        // below operate on Model.modelId (HF path, empty string pre-Task-2).
        items(rows, key = { it.entry.model.name }) { row ->
            val entry = row.entry
            ModelCard(
                entry = entry,
                gate = row.gate,
                isDefault = entry.model.modelId.isNotEmpty() &&
                    entry.model.modelId == defaultModelId,
                onDownload = { onDownload(entry) },
                onCancel = { onCancel(entry.model.name) },
                onLoad = { onLoad(entry.model.modelId) },
                onSetDefault = { onSetDefault(entry.model.modelId, entry.model.name) },
                onDeleteEmbedder = { onDeleteEmbedder(entry.model.modelId, entry.model.name) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    gate: GateDecision,
    isDefault: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onLoad: () -> Unit,
    onSetDefault: () -> Unit,
    onDeleteEmbedder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Each card owns its overflow menu state — a single top-level variable would reopen every
    // row when the user taps any row's overflow button.
    var overflowExpanded by remember { mutableStateOf(false) }
    val isEmbedder = entry.isEmbedder()

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDefault) {
                    Icon(
                        imageVector = SanctumIcons.IconStarFill,
                        contentDescription = stringResource(R.string.model_manager_default_badge_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        // Padding first, size second — otherwise padding would eat the 20dp drawable area.
                        modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    )
                }
                Text(
                    text = entry.model.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // Overflow menu: hidden for embedder rows (no «Сделать по умолчанию» — would
                // corrupt the chat-model contract). The «Удалить эмбеддер» action lives in
                // ModelStatusSection instead so it sits next to the row-state CTA, where users
                // expect destructive actions for an installed file.
                if (
                    !isEmbedder &&
                    entry.downloadStatus.status == ModelDownloadStatusType.SUCCEEDED &&
                    !isDefault
                ) {
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.model_manager_overflow_desc),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.model_manager_set_default)) },
                                onClick = {
                                    overflowExpanded = false
                                    onSetDefault()
                                },
                            )
                        }
                    }
                }
            }
            if (isEmbedder) {
                Text(
                    text = stringResource(R.string.model_manager_embedder_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                gate = gate,
                isEmbedder = isEmbedder,
                isBundled = entry.model.bundled,
                onDownload = onDownload,
                onCancel = onCancel,
                onLoad = onLoad,
                onDeleteEmbedder = onDeleteEmbedder,
            )
        }
    }
}

@Composable
private fun ModelStatusSection(
    downloadStatus: ModelDownloadStatus,
    gate: GateDecision,
    isEmbedder: Boolean,
    isBundled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onLoad: () -> Unit,
    onDeleteEmbedder: () -> Unit,
) {
    when (downloadStatus.status) {
        ModelDownloadStatusType.NOT_DOWNLOADED,
        ModelDownloadStatusType.PARTIALLY_DOWNLOADED -> {
            StatusBadge(text = stringResource(R.string.model_status_not_downloaded))
            if (gate.allowed) {
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.btn_download))
                }
            } else {
                // Hardcoded onClick = {} matches the disabled visual; defence-in-depth lives in
                // ModelManagerViewModel.onDownload, which short-circuits the same predicate.
                Button(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.btn_download))
                }
                Text(
                    text = formatRamShortage(gate.totalBytes, gate.minGb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            // Task 17: bundled embedder row replaces «Status: Downloaded» with «Bundled» —
            // «Downloaded» would be a lie (the file was never downloaded) and obscures the
            // build-time provenance the About-screen Licenses section commits us to surface.
            if (isEmbedder && isBundled) {
                StatusBadge(text = stringResource(R.string.model_manager_embedder_bundled))
            } else {
                StatusBadge(text = stringResource(R.string.model_status_downloaded))
            }
            if (isEmbedder) {
                if (isBundled) {
                    // No delete action — the file is owned by the APK install, not by app data;
                    // reclamation requires uninstall. Surfaced visually only.
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.model_manager_embedder_bundled)) },
                    )
                } else {
                    // Downloadable embedder (legacy / future row) — disabled «in use» chip plus
                    // a destructive delete that cascades through ModelRegistry.delete.
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.model_manager_embedder_in_use)) },
                    )
                    Button(onClick = onDeleteEmbedder) {
                        Text(stringResource(R.string.model_manager_embedder_delete_action))
                    }
                }
            } else {
                Button(onClick = onLoad) {
                    Text(stringResource(R.string.btn_load))
                }
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
