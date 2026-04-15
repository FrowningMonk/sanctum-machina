package app.sanctum.machina.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sanctum.machina.R

@Composable
fun ChatScreen(
    modelName: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    when (val state = uiState) {
        ChatUiState.Loading -> LoadingContent()
        is ChatUiState.Failed -> FailedContent(rawCause = state.rawCause, onBack = onBack)
        is ChatUiState.Ready ->
            ReadyContent(
                modelName = modelName,
                messages = messages,
                isGenerating = state.isGenerating,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onReset = viewModel::reset,
            )
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.chat_loading_model),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FailedContent(rawCause: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.chat_load_failed_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = rawCause,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.btn_back))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(
    modelName: String,
    messages: List<Message>,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(modelName) },
                actions = {
                    IconButton(onClick = onReset, enabled = !isGenerating) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.btn_reset),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            MessageList(
                messages = messages,
                modifier = Modifier.weight(1f),
            )
            ChatInputRow(
                isGenerating = isGenerating,
                onSend = onSend,
                onStop = onStop,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MessageList(messages: List<Message>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages) { message -> MessageBubble(message) }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor =
        if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Column(
            modifier =
                Modifier.widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val suffix =
                if (message.interrupted) stringResource(R.string.chat_message_interrupted_suffix)
                else ""
            Text(
                text = message.text + suffix,
                style = MaterialTheme.typography.bodyMedium,
            )
            message.footer?.let { footer ->
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatInputRow(
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
            modifier = Modifier.weight(1f),
            enabled = !isGenerating,
        )
        IconButton(
            onClick = {
                if (isGenerating) {
                    onStop()
                } else {
                    val toSend = text.trim()
                    if (toSend.isNotEmpty()) {
                        onSend(toSend)
                        text = ""
                    }
                }
            },
        ) {
            Icon(
                imageVector = if (isGenerating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription =
                    stringResource(
                        if (isGenerating) R.string.btn_stop else R.string.btn_send
                    ),
            )
        }
    }
}
