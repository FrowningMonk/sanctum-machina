package app.sanctum.machina.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sanctum.machina.R
import app.sanctum.machina.ui.components.SmSigil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// Mirrors AboutScreen's filename scheme for the diagnostic `.txt` export.
private const val CORRUPTION_LOG_TIMESTAMP_PATTERN = "yyyyMMdd-HHmm"

/**
 * Phase-3 start destination. Shows a centered product identity plus the primary "Новый быстрый
 * чат" action when at least one model is downloaded (AC-F2 normal path), or a placeholder
 * inviting the user to open Model Manager when the on-disk set is empty (AC-F2 no-models path,
 * US-8).
 *
 * AC-D5 / US-14 corruption banner: if [AppCorruptionState.corruptionOccurred] is `true` on the
 * first composition after a Room open that had to rename the db to `sanctum.db.corrupt_*`,
 * a one-shot banner is rendered with "Сохранить лог" (SAF export via [LogExportManager]) and
 * "Закрыть" actions. The visibility flag lives in `remember { mutableStateOf(...) }` — purely
 * in-memory, so dismissing it does not persist; the banner reappears only if the next process
 * launch again sees a corrupt db.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNewQuickChat: () -> Unit,
    onOpenModelManager: () -> Unit,
    onOpenDrawer: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val hasDownloadedModels by viewModel.hasDownloadedModels.collectAsStateWithLifecycle()
    val activeModelName by viewModel.activeModelName.collectAsStateWithLifecycle()

    // Seeded once from the process-scoped flag; dismissing flips the local state only, so the
    // banner returns on the next process launch if corruption strikes again.
    var showCorruptionBanner by remember {
        mutableStateOf(viewModel.corruptionOccurred)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val saveSuccess = stringResource(R.string.log_export_success_toast)
    val saveError = stringResource(R.string.log_export_error_toast)
    var launching by remember { mutableStateOf(false) }
    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        coroutineScope.launch {
            try {
                if (uri != null) {
                    viewModel.buildAndWrite(uri)
                        .onSuccess { snackbarHostState.showSnackbar(saveSuccess) }
                        .onFailure { snackbarHostState.showSnackbar(saveError) }
                }
            } finally {
                launching = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Outlined.Menu,
                            contentDescription = stringResource(R.string.home_menu_open),
                        )
                    }
                },
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    // TODO(Phase 5): wire to SettingsScreen; keep disabled until it exists so
                    // the icon is not a silent no-op tap target.
                    IconButton(onClick = {}, enabled = false) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.home_settings_open),
                        )
                    }
                },
            )
        },
        bottomBar = {
            HomeStatusBar(activeModelName = activeModelName)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            AnimatedVisibility(visible = showCorruptionBanner) {
                CorruptionBanner(
                    launching = launching,
                    onSaveLog = {
                        launching = true
                        val stamp = SimpleDateFormat(CORRUPTION_LOG_TIMESTAMP_PATTERN, Locale.ROOT)
                            .format(Date())
                        saveLogLauncher.launch("sanctum-corruption-$stamp.txt")
                    },
                    onDismiss = { showCorruptionBanner = false },
                )
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (hasDownloadedModels) {
                    HomeReadyBody(
                        onNewQuickChat = onNewQuickChat,
                        onOpenDrawer = onOpenDrawer,
                    )
                } else {
                    HomeNoModelsBody(onOpenModelManager = onOpenModelManager)
                }
            }
        }
    }
}

@Composable
private fun CorruptionBanner(
    launching: Boolean,
    onSaveLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.corruption_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.corruption_banner_dismiss))
                }
                // `Modifier.padding` on an empty Spacer collapses to zero size — use `width` so
                // the gap actually renders (code-reviewer-1 minor).
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onSaveLog, enabled = !launching) {
                    Text(stringResource(R.string.corruption_banner_save_log))
                }
            }
        }
    }
}

@Composable
private fun HomeIdentity() {
    SmSigil(size = 40.dp)
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.home_product_name),
        style = MaterialTheme.typography.headlineMedium,
    )
    Text(
        text = stringResource(R.string.home_kicker),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HomeReadyBody(
    onNewQuickChat: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeIdentity()
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onNewQuickChat) {
            Text(stringResource(R.string.home_action_new_quick_chat))
        }
        TextButton(onClick = onOpenDrawer) {
            Text(stringResource(R.string.home_action_open_history))
        }
    }
}

@Composable
private fun HomeNoModelsBody(onOpenModelManager: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeIdentity()
        Text(
            text = stringResource(R.string.home_no_models_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenModelManager) {
            Text(stringResource(R.string.home_action_open_model_manager))
        }
    }
}

@Composable
private fun HomeStatusBar(activeModelName: String?) {
    // TODO(Task 10): replace plain text with "warmup state dot + model chip" (design file
    // `sanctum-screen-home.jsx` bottom bar). Also resolve `activeModelName` (raw HF modelId)
    // through a display-name lookup so the chip doesn't show e.g. `litert-community/...`.
    val text = activeModelName ?: stringResource(R.string.home_status_model_cold)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
