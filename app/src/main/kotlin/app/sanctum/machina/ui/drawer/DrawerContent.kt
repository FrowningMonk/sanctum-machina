package app.sanctum.machina.ui.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sanctum.machina.R
import app.sanctum.machina.ui.theme.sanctumColors

private const val MAX_MANUAL_TITLE_LEN = 60

/**
 * Navigation drawer content for Phase-3 history (Task 9).
 *
 * Stateless relative to the VM — `drawerUiState`, the current open chat id and
 * all actions are pushed in from `SanctumApp`. Local composable state holds the
 * three transient dialogs (rename / delete-confirm / model-unavailable) so they
 * survive swipe state transitions without involving the VM in UI plumbing.
 *
 * Swipe-left triggers the delete confirmation dialog directly (Material 3's
 * `SwipeToDismissBox` pattern) rather than revealing a button first. The user
 * still gets the explicit confirmation before any CASCADE DELETE runs; the red
 * background with the trash icon is visible during the swipe itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawerContent(
    currentChatId: Long?,
    onChatClick: (chatId: Long) -> Unit,
    onNewChat: () -> Unit,
    onNavigateToModelManager: (modelId: String) -> Unit,
    onOpenModelManager: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onPopCurrentChat: () -> Unit,
    viewModel: DrawerViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.drawerUiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DrawerEvent.PopBack -> onPopCurrentChat()
            }
        }
    }

    var pendingRename by remember { mutableStateOf<ChatRowUiModel?>(null) }
    var pendingDelete by remember { mutableStateOf<ChatRowUiModel?>(null) }
    var pendingUnavailable by remember { mutableStateOf<ChatRowUiModel?>(null) }

    ModalDrawerSheet(modifier = modifier.width(300.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DrawerHeader(onNewChat = onNewChat)
            HorizontalDivider()
            // Task 18 B5 / user-spec §37: chat list occupies the middle; footer
            // pins «Модели» and «О приложении» to the bottom. Wrapping the list
            // in `Box(weight=1f)` makes the footer hug the bottom edge
            // regardless of list length (empty, overflowing, or somewhere in
            // between) — a plain Column would push the footer down when the
            // list is short and scroll it off-screen when long.
            Box(modifier = Modifier.weight(1f)) {
                if (state.sections.isEmpty() && !state.isLoading) {
                    DrawerEmptyState(onNewChat = onNewChat)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        state.sections.forEach { section ->
                            item(key = "header-${section.kind.name}") {
                                SectionHeader(kind = section.kind)
                            }
                            items(section.chats, key = { it.id }) { row ->
                                SwipeableChatRow(
                                    row = row,
                                    isActive = row.id == currentChatId,
                                    onTap = {
                                        if (row.isModelAvailable) {
                                            onChatClick(row.id)
                                        } else {
                                            pendingUnavailable = row
                                        }
                                    },
                                    onLongPress = { pendingRename = row },
                                    onSwipeDelete = { pendingDelete = row },
                                )
                            }
                        }
                    }
                }
            }
            DrawerFooter(
                onOpenModelManager = onOpenModelManager,
                onNavigateToDiagnostics = onNavigateToDiagnostics,
                onNavigateToAbout = onNavigateToAbout,
            )
        }
    }

    pendingRename?.let { row ->
        RenameChatDialog(
            initialTitle = row.title,
            onConfirm = { newTitle ->
                viewModel.renameChat(row.id, newTitle)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
        )
    }

    pendingDelete?.let { row ->
        DeleteChatDialog(
            chatId = row.id,
            chatTitle = row.title,
            messageCountProvider = { viewModel.getMessageCount(row.id) },
            onConfirm = {
                viewModel.deleteChat(row.id, currentChatId)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingUnavailable?.let { row ->
        ModelUnavailableDialog(
            modelDisplayName = row.modelDisplayName,
            onDownload = {
                onNavigateToModelManager(row.modelId)
                pendingUnavailable = null
            },
            onDismiss = { pendingUnavailable = null },
        )
    }

}

@Composable
private fun DrawerFooter(
    onOpenModelManager: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    HorizontalDivider()
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.drawer_nav_models)) },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Storage,
                    contentDescription = null,
                )
            },
            selected = false,
            onClick = onOpenModelManager,
            modifier = Modifier.padding(horizontal = 12.dp),
            colors = NavigationDrawerItemDefaults.colors(),
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.drawer_nav_diagnostics)) },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.MonitorHeart,
                    contentDescription = null,
                )
            },
            selected = false,
            onClick = onNavigateToDiagnostics,
            modifier = Modifier.padding(horizontal = 12.dp),
            colors = NavigationDrawerItemDefaults.colors(),
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.drawer_nav_about)) },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                )
            },
            selected = false,
            onClick = onNavigateToAbout,
            modifier = Modifier.padding(horizontal = 12.dp),
            colors = NavigationDrawerItemDefaults.colors(),
        )
    }
}

@Composable
private fun DrawerHeader(onNewChat: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.drawer_title),
            style = MaterialTheme.typography.titleMedium,
        )
        FilledTonalButton(onClick = onNewChat) {
            Text(stringResource(R.string.drawer_new_chat))
        }
    }
}

@Composable
private fun DrawerEmptyState(onNewChat: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.drawer_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onNewChat) {
                Text(stringResource(R.string.drawer_new_chat))
            }
        }
    }
}

@Composable
private fun SectionHeader(kind: DateSectionKind) {
    val label = when (kind) {
        DateSectionKind.TODAY -> R.string.drawer_section_today
        DateSectionKind.YESTERDAY -> R.string.drawer_section_yesterday
        DateSectionKind.THIS_WEEK -> R.string.drawer_section_this_week
        DateSectionKind.EARLIER -> R.string.drawer_section_earlier
    }
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableChatRow(
    row: ChatRowUiModel,
    isActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeDelete: () -> Unit,
) {
    val dangerColor = MaterialTheme.sanctumColors.danger
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onSwipeDelete()
            }
            // Always return false: we drive the actual delete through the
            // confirmation dialog the caller shows, and reset the swipe state
            // once the dialog is dismissed. The box snapping back here is what
            // keeps the row visible behind the confirmation dialog.
            false
        },
        positionalThreshold = { it * 0.35f },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(dangerColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.drawer_delete_action),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
    ) {
        ChatRow(
            row = row,
            isActive = isActive,
            onTap = onTap,
            onLongPress = onLongPress,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    row: ChatRowUiModel,
    isActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val accentColor = MaterialTheme.sanctumColors.accent
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isActive) {
                        Modifier.drawBehind {
                            drawRect(
                                color = accentColor,
                                topLeft = Offset.Zero,
                                size = Size(width = 2.dp.toPx(), height = size.height),
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title.ifBlank { stringResource(R.string.drawer_untitled_chat) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModelChip(displayName = row.modelDisplayName)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = row.relativeDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelChip(displayName: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RenameChatDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.take(MAX_MANUAL_TITLE_LEN) },
                singleLine = true,
                textStyle = TextStyle.Default,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                placeholder = { Text(stringResource(R.string.drawer_rename_placeholder)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.drawer_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

@Composable
private fun DeleteChatDialog(
    chatId: Long,
    chatTitle: String,
    messageCountProvider: suspend () -> Int?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // `null` covers two cases we want to render identically: (1) the count is
    // still loading, and (2) the count query failed. In both cases we show
    // the no-count body so the user never sees a misleading "0 сообщ." right
    // before an irreversible CASCADE DELETE.
    var messageCount by remember(chatId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(chatId) {
        messageCount = messageCountProvider()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_title)) },
        text = {
            val body = messageCount?.let { count ->
                stringResource(R.string.drawer_delete_body_with_count, chatTitle, count)
            } ?: stringResource(R.string.drawer_delete_body_without_count, chatTitle)
            Text(body)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.drawer_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

@Composable
private fun ModelUnavailableDialog(
    modelDisplayName: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_model_unavailable_title)) },
        text = {
            Text(stringResource(R.string.drawer_model_unavailable_body, modelDisplayName))
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.drawer_model_unavailable_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}
