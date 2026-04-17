package app.sanctum.machina.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sanctum.machina.R
import app.sanctum.machina.core.data.Accelerator
import app.sanctum.machina.core.data.ConfigKeys
import app.sanctum.machina.core.settings.proto.PerModelSettings
import androidx.compose.foundation.text.KeyboardOptions

/** Defensive cap on user-entered system prompts (security-auditor minor-2). */
private const val SYSTEM_PROMPT_MAX_LENGTH: Int = 4096

/**
 * Bottom sheet for per-model inference settings (D15, D16, AC-4, AC-21).
 *
 * Reads persisted overrides via [ChatViewModel.observePerModelSettings] and
 * shows the *effective* (defaults ∪ overrides) value for each field. Hides
 * `enable_thinking` when `supportThinking == false` (D9). Apply / Default
 * buttons route through [ChatViewModel.saveAndApplySettings] /
 * [ChatViewModel.resetSettingsToDefaults] respectively; both surface
 * `HeavyChangeDialog` first when accelerator or enable_thinking would
 * change so the user can back out before the engine teardown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceSettingsBottomSheet(
    viewModel: ChatViewModel,
    supportThinking: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val overrides by viewModel.observePerModelSettings().collectAsStateWithLifecycle(initialValue = null)

    val defaults = remember { viewModel.allowlistDefaults() }
    val effective = remember(overrides, defaults) {
        EffectiveConfig.merge(defaults, overrides)
    }

    val systemPrompt = remember(effective) {
        mutableStateOf(effective[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label] as? String ?: "")
    }
    val temperature = remember(effective) {
        mutableFloatStateOf((effective[ConfigKeys.TEMPERATURE.label] as? Float) ?: 0.7f)
    }
    val topK = remember(effective) {
        mutableIntStateOf((effective[ConfigKeys.TOPK.label] as? Int) ?: 40)
    }
    val topP = remember(effective) {
        mutableFloatStateOf((effective[ConfigKeys.TOPP.label] as? Float) ?: 0.95f)
    }
    val maxTokens = remember(effective) {
        mutableIntStateOf((effective[ConfigKeys.MAX_TOKENS.label] as? Int) ?: 1024)
    }
    val enableThinking = remember(effective) {
        mutableStateOf((effective[ConfigKeys.ENABLE_THINKING.label] as? Boolean) ?: false)
    }
    val accelerator = remember(effective) {
        mutableStateOf(
            (effective[ConfigKeys.ACCELERATOR.label] as? String) ?: Accelerator.GPU.label
        )
    }

    var pendingHeavyApply by remember { mutableStateOf<HeavyAction?>(null) }

    fun buildOverrides(): PerModelSettings = PerModelSettings.newBuilder().apply {
        setSystemPromptDefault(systemPrompt.value)
        setTemperature(temperature.floatValue)
        setTopK(topK.intValue)
        setTopP(topP.floatValue)
        setMaxTokens(maxTokens.intValue)
        if (supportThinking) setEnableThinking(enableThinking.value)
        setAccelerator(accelerator.value)
    }.build()

    fun isHeavyApplyNeeded(target: PerModelSettings): Boolean {
        val current = viewModel.currentEffectiveConfig()
        val merged = EffectiveConfig.merge(viewModel.allowlistDefaults(), target)
        val acceleratorChanged = current[ConfigKeys.ACCELERATOR.label] !=
            merged[ConfigKeys.ACCELERATOR.label]
        val thinkingChanged = current[ConfigKeys.ENABLE_THINKING.label] !=
            merged[ConfigKeys.ENABLE_THINKING.label]
        return acceleratorChanged || thinkingChanged
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
            )

            SystemPromptField(state = systemPrompt)
            FloatSliderField(
                label = stringResource(R.string.settings_temperature_label),
                state = temperature,
                range = 0f..2f,
            )
            IntSliderField(
                label = stringResource(R.string.settings_top_k_label),
                state = topK,
                range = 1..100,
            )
            FloatSliderField(
                label = stringResource(R.string.settings_top_p_label),
                state = topP,
                range = 0f..1f,
            )
            IntSliderField(
                label = stringResource(R.string.settings_max_tokens_label),
                state = maxTokens,
                range = 256..8192,
                step = 256,
            )
            if (supportThinking) {
                BooleanSwitchField(
                    label = stringResource(R.string.settings_enable_thinking_label),
                    state = enableThinking,
                )
            }
            AcceleratorField(state = accelerator)

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        if (viewModel.needsHeavyResetToDefaults()) {
                            pendingHeavyApply = HeavyAction.ResetToDefaults
                        } else {
                            viewModel.resetSettingsToDefaults()
                            onDismiss()
                        }
                    },
                ) {
                    Text(stringResource(R.string.btn_default))
                }
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        val target = buildOverrides()
                        if (isHeavyApplyNeeded(target)) {
                            pendingHeavyApply = HeavyAction.Save(target)
                        } else {
                            viewModel.saveAndApplySettings(target)
                            onDismiss()
                        }
                    },
                ) {
                    Text(stringResource(R.string.btn_apply))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // `pendingHeavyApply` uses plain `remember` — a configuration change
    // while the HeavyChangeDialog is visible silently discards the
    // pending action. Acceptable trade-off: the proto carries binary
    // bytes and cannot be saved by the default state-saver, the sheet
    // is a fast-path flow where rotation mid-dialog is rare, and the
    // user's edits remain in the sheet fields so re-clicking Apply
    // reconstructs the same action.
    pendingHeavyApply?.let { action ->
        HeavyChangeDialog(
            onConfirm = {
                pendingHeavyApply = null
                when (action) {
                    is HeavyAction.Save -> viewModel.saveAndApplySettings(action.target)
                    HeavyAction.ResetToDefaults -> viewModel.resetSettingsToDefaults()
                }
                onDismiss()
            },
            onDismiss = { pendingHeavyApply = null },
        )
    }
}

private sealed interface HeavyAction {
    data class Save(val target: PerModelSettings) : HeavyAction
    data object ResetToDefaults : HeavyAction
}

@Composable
private fun SystemPromptField(state: MutableState<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.settings_system_prompt_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.value,
            onValueChange = { new ->
                // Clamp length at input time — prevents a pathological paste
                // from sitting in DataStore and being re-deserialised on every
                // cold start (security-auditor minor-2).
                state.value = if (new.length <= SYSTEM_PROMPT_MAX_LENGTH) new
                else new.take(SYSTEM_PROMPT_MAX_LENGTH)
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.settings_system_prompt_placeholder)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = KeyboardType.Text,
            ),
            maxLines = 4,
        )
    }
}

@Composable
private fun FloatSliderField(
    label: String,
    state: MutableState<Float>,
    range: ClosedFloatingPointRange<Float>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.settings_value_float_format, state.value),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = state.value,
            onValueChange = { state.value = it },
            valueRange = range,
        )
    }
}

@Composable
private fun IntSliderField(
    label: String,
    state: MutableState<Int>,
    range: IntRange,
    step: Int = 1,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.settings_value_int_format, state.value),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val steps = ((range.last - range.first) / step) - 1
        Slider(
            value = state.value.toFloat(),
            onValueChange = { v ->
                val snapped = (kotlin.math.round(v / step) * step).toInt()
                    .coerceIn(range.first, range.last)
                state.value = snapped
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps.coerceAtLeast(0),
        )
    }
}

@Composable
private fun BooleanSwitchField(
    label: String,
    state: MutableState<Boolean>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = state.value, onCheckedChange = { state.value = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcceleratorField(state: MutableState<String>) {
    val options = listOf(Accelerator.GPU.label, Accelerator.CPU.label)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.settings_accelerator_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { idx, label ->
                SegmentedButton(
                    selected = state.value == label,
                    onClick = { state.value = label },
                    shape = SegmentedButtonDefaults.itemShape(idx, options.size),
                ) {
                    Text(label)
                }
            }
        }
    }
}
