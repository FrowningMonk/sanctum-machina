package app.sanctum.machina.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.sanctum.machina.R
import app.sanctum.machina.core.data.MAX_AUDIO_CLIP_DURATION_SEC
import app.sanctum.machina.core.data.SAMPLE_RATE
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val MAX_DURATION_MS: Long = MAX_AUDIO_CLIP_DURATION_SEC * 1000L

/**
 * Audio capture bottom sheet (AC-12, AC-15, AC-19, AC-20, D5, D13, D14, D27).
 *
 * Flow:
 *  1. On entry, check `RECORD_AUDIO`. Missing → request via launcher.
 *     Denial → `onPermissionDenied(permanent)` + dismiss. Permanent-denial
 *     heuristic mirrors [isAudioDenialPermanent] (same shape as
 *     `CameraBottomSheet.isCameraDenialPermanent`).
 *  2. Granted → construct `AudioRecord(CHANNEL_IN_MONO, PCM_16BIT, 16 kHz)`
 *     and verify `state == STATE_INITIALIZED`. Failure (OEM-specific
 *     MIUI/HarmonyOS rejection, R9; `getMinBufferSize` returning `ERROR` /
 *     `ERROR_BAD_VALUE`; mic held by another app) → `onAudioError` +
 *     dismiss.
 *  3. The recording loop runs in `Dispatchers.IO`: `recorder.read(buffer)`
 *     → `ByteArrayOutputStream`, elapsed ms drives the timer state. The
 *     loop is the sole writer to the stream and the sole caller of
 *     `onSave`. Auto-stops at [MAX_DURATION_MS].
 *  4. "Остановить" button → `recorder.stop()`. `recordingState` flips to
 *     `STOPPED`; the read loop sees it on the next poll, exits the while,
 *     and calls the shared finish path.
 *  5. `DisposableEffect.onDispose` releases `AudioRecord` and cancels the
 *     recording job — guarantees native resource release on any
 *     composition exit (D14).
 *  6. `LifecycleEventEffect(ON_PAUSE)` dismisses the sheet without saving
 *     — covers incoming calls, background, and screen lock without
 *     `READ_PHONE_STATE` / `TelephonyCallback` (AC-19, D14).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRecorderBottomSheet(
    onDismiss: () -> Unit,
    onSaveAudio: (pcm: ByteArray, durationMs: Long) -> Unit,
    onAudioError: (description: String, cause: Throwable?) -> Unit,
    onPermissionDenied: (permanent: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val onDismissLatest = rememberUpdatedState(onDismiss)
    val onSaveAudioLatest = rememberUpdatedState(onSaveAudio)
    val onAudioErrorLatest = rememberUpdatedState(onAudioError)
    val onPermissionDeniedLatest = rememberUpdatedState(onPermissionDenied)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    fun animateAndDismiss() {
        scope.launch {
            try {
                sheetState.hide()
            } finally {
                onDismissLatest.value()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            hasPermission = true
        } else {
            val activity = context.findActivity()
            val rationaleVisible = activity != null && ActivityCompat
                .shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            onPermissionDeniedLatest.value(isAudioDenialPermanent(activity, rationaleVisible))
            animateAndDismiss()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { onDismissLatest.value() },
    ) {
        if (hasPermission) {
            AudioRecorderContent(
                scope = scope,
                onSave = { pcm, durationMs ->
                    onSaveAudioLatest.value(pcm, durationMs)
                    animateAndDismiss()
                },
                onError = { description, cause ->
                    onAudioErrorLatest.value(description, cause)
                    animateAndDismiss()
                },
                onPauseDismiss = { animateAndDismiss() },
            )
        }
    }
}

@Composable
private fun AudioRecorderContent(
    scope: CoroutineScope,
    onSave: (pcm: ByteArray, durationMs: Long) -> Unit,
    onError: (description: String, cause: Throwable?) -> Unit,
    onPauseDismiss: () -> Unit,
) {
    // Held outside Compose state — native resource, not UI state.
    // Timer-tick recomposition should not churn these references.
    val recorderRef = remember { RecorderHandle() }
    // Idempotency guard for the terminal path — Stop button can race with
    // the auto-stop branch (both call `finish`).
    val completed = remember { AtomicBoolean(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onError("audio record buffer size error", null)
            return@LaunchedEffect
        }
        val recorder = try {
            buildRecorder(minBufferSize)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            onError("audio record init failed", t)
            return@LaunchedEffect
        }
        if (recorder == null) {
            onError("audio record not initialized", null)
            return@LaunchedEffect
        }
        recorderRef.recorder = recorder
        val stream = ByteArrayOutputStream()
        recorderRef.stream = stream
        recorderRef.job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(minBufferSize)
            try {
                recorder.startRecording()
                val startMs = System.currentTimeMillis()
                while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) stream.write(buffer, 0, bytesRead)
                    val now = System.currentTimeMillis() - startMs
                    elapsedMs = now
                    if (now >= MAX_DURATION_MS) break
                }
                val finalMs = (System.currentTimeMillis() - startMs).coerceAtMost(MAX_DURATION_MS)
                finish(recorderRef, stream, finalMs, completed, onSave)
            } catch (ce: CancellationException) {
                // DisposableEffect/ON_PAUSE teardown — drop buffer, don't save.
                throw ce
            } catch (t: Throwable) {
                onError("audio record read failed", t)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorderRef.job?.cancel()
            recorderRef.recorder?.let { r ->
                runCatching {
                    if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
                }
                r.release()
            }
            recorderRef.recorder = null
            recorderRef.stream = null
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        // AC-19: drop the half-finished buffer on call/background/lock.
        // DisposableEffect.onDispose handles the actual release on sheet
        // teardown triggered here via `onPauseDismiss`.
        onPauseDismiss()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = formatTimer(elapsedMs),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                // AudioRecord.stop() is thread-safe; the read loop sees the
                // state flip and exits the while, then calls finish() on
                // its own (IO) thread — single writer to `stream` invariant
                // preserved.
                runCatching {
                    val r = recorderRef.recorder ?: return@runCatching
                    if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
                }
            },
        ) {
            Icon(imageVector = Icons.Default.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.audio_record_stop))
        }
    }
}

/**
 * Recorder state held outside Compose state so recomposition (timer
 * ticks) does not churn the native reference. Populated by the
 * `LaunchedEffect` that starts the read loop; cleared by
 * `DisposableEffect.onDispose`.
 */
private class RecorderHandle {
    var recorder: AudioRecord? = null
    var job: Job? = null
    var stream: ByteArrayOutputStream? = null
}

/**
 * Creates and verifies `AudioRecord`. Returns null if the device/OEM
 * refuses initialization (R9 — MIUI/HarmonyOS) or the min-buffer query
 * returns `ERROR` / `ERROR_BAD_VALUE`. `@SuppressLint` is safe because
 * the caller gates this behind `ContextCompat.checkSelfPermission` +
 * `RequestPermission()` launcher.
 */
@SuppressLint("MissingPermission")
private fun buildRecorder(minBufferSize: Int): AudioRecord? {
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT,
        minBufferSize,
    )
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        return null
    }
    return recorder
}

/**
 * Idempotent terminal path for both Stop-button and auto-stop. `stop()`
 * is safe to call even after the loop's own exit-state check fired; the
 * `completed` CAS guards against double-invocation of `onSave` if the
 * user taps Stop moments before the 30-sec boundary.
 */
private fun finish(
    recorderHandle: RecorderHandle,
    stream: ByteArrayOutputStream,
    durationMs: Long,
    completed: AtomicBoolean,
    onSave: (ByteArray, Long) -> Unit,
) {
    if (!completed.compareAndSet(false, true)) return
    runCatching {
        val r = recorderHandle.recorder ?: return@runCatching
        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
    }
    val bytes = stream.toByteArray()
    stream.reset()
    onSave(bytes, durationMs)
}

/**
 * Formats elapsed millis as `MM:SS`. Stays stable if
 * `MAX_AUDIO_CLIP_DURATION_SEC` grows past 60 in a future phase.
 * Negative millis (clock skew) clamp to zero. Pure — see
 * `AudioRecorderBottomSheetTest`.
 */
internal fun formatTimer(elapsedMs: Long): String {
    val totalSec = (elapsedMs.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * Mirror of `CameraBottomSheet.isCameraDenialPermanent`. A null Activity
 * means we cannot re-prompt anyway (permanent by definition); otherwise
 * the OS's `shouldShowRequestPermissionRationale` tells us whether
 * "don't ask again" was selected.
 */
internal fun isAudioDenialPermanent(activity: Activity?, rationaleVisible: Boolean): Boolean =
    activity == null || !rationaleVisible

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
