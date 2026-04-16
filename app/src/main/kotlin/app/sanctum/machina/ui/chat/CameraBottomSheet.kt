package app.sanctum.machina.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sanctum.machina.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Camera capture bottom sheet (AC-11, AC-15, D6, D11, R8).
 *
 * Flow:
 *  1. On entry, check `CAMERA` permission. Missing → request via launcher.
 *     Denial → `onPermissionDenied(permanent)` + dismiss. Permanent-denial
 *     heuristic: after the launcher delivers `granted=false`,
 *     `shouldShowRequestPermissionRationale` is also false (OS has stopped
 *     prompting). Before the first prompt that signal looks identical, but by
 *     the time this callback fires the launcher already showed a dialog, so
 *     treating it as permanent is the pragmatic call. See
 *     [isCameraDenialPermanent] for the extracted pure helper.
 *  2. Granted → bind `Preview` + `ImageCapture` (resolution capped at
 *     [CAPTURE_TARGET_PX] so a 50-MP sensor doesn't allocate a ~200 MB
 *     intermediate bitmap before `ChatViewModel.addImageBitmap` downscales)
 *     to the lifecycle owner via `ProcessCameraProvider.awaitInstance`.
 *     Any throwable during bind → `onCameraError` + dismiss.
 *  3. Capture button → `ImageCapture.takePicture(executor, callback)`.
 *     The callback runs on a single-thread executor (off-Main decode);
 *     bitmap + rotation are computed there, then handed back to the
 *     Compose scope via `scope.launch { }` so all UI state transitions
 *     happen on Main. If the sheet's scope is cancelled (user swiped away
 *     mid-capture), the launched dispatch no-ops — no phantom attachment.
 *  4. `DisposableEffect.onDispose` unbinds the provider and shuts down the
 *     executor. Once capture fires, `capturing` stays true until the sheet
 *     is torn down — avoids re-arming the shutter on a dismissing sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraBottomSheet(
    onDismiss: () -> Unit,
    onImageCaptured: (Bitmap) -> Unit,
    onCameraError: (description: String, cause: Throwable?) -> Unit,
    onPermissionDenied: (permanent: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val onDismissLatest = rememberUpdatedState(onDismiss)
    val onPermissionDeniedLatest = rememberUpdatedState(onPermissionDenied)
    val onImageCapturedLatest = rememberUpdatedState(onImageCaptured)
    val onCameraErrorLatest = rememberUpdatedState(onCameraError)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
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
                .shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            onPermissionDeniedLatest.value(isCameraDenialPermanent(activity, rationaleVisible))
            animateAndDismiss()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { onDismissLatest.value() },
    ) {
        if (hasPermission) {
            CameraPreviewContent(
                scope = scope,
                onCapture = { bitmap ->
                    onImageCapturedLatest.value(bitmap)
                    animateAndDismiss()
                },
                onError = { description, cause ->
                    onCameraErrorLatest.value(description, cause)
                    animateAndDismiss()
                },
                onClose = { animateAndDismiss() },
            )
        }
    }
}

@Composable
private fun CameraPreviewContent(
    scope: CoroutineScope,
    onCapture: (Bitmap) -> Unit,
    onError: (String, Throwable?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewUseCase = remember { Preview.Builder().build() }
    val imageCaptureUseCase = remember {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(CAPTURE_TARGET_PX, CAPTURE_TARGET_PX),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
    }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    // Sticky: flips true on capture dispatch, never flips back — the sheet
    // tears down after success/error, so the shutter shouldn't re-arm.
    var capturing by remember { mutableStateOf(false) }

    // Keyed on `lifecycleOwner`: NavHost may swap owners on configuration
    // change, which would orphan the bound use cases if we keyed on Unit.
    LaunchedEffect(lifecycleOwner) {
        try {
            val provider = ProcessCameraProvider.awaitInstance(context)
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                previewUseCase,
                imageCaptureUseCase,
            )
            cameraProvider = provider
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            onError("camera bind failed", t)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            if (!executor.isShutdown) executor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CameraSheetHeight),
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    previewUseCase.surfaceProvider = view.surfaceProvider
                }
            },
            update = { /* PreviewView self-updates from surfaceProvider. */ },
            modifier = Modifier.fillMaxWidth().height(CameraSheetHeight),
        )

        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.btn_close),
            )
        }

        IconButton(
            onClick = {
                if (capturing) return@IconButton
                capturing = true
                imageCaptureUseCase.takePicture(
                    executor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bitmap = try {
                                val raw = image.toBitmap()
                                rotateBitmapByDegrees(raw, image.imageInfo.rotationDegrees)
                            } catch (t: Throwable) {
                                image.close()
                                // Main-dispatch: the sheet's `scope` cancels on
                                // composition drop, so a swipe-dismiss mid-capture
                                // silences this path (no phantom VM error).
                                scope.launch { onError("capture decode failed", t) }
                                return
                            }
                            image.close()
                            scope.launch { onCapture(bitmap) }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            scope.launch { onError("capture failed", exception) }
                        }
                    },
                )
            },
            enabled = !capturing && cameraProvider != null,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .size(72.dp)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                ),
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoCamera,
                contentDescription = stringResource(R.string.camera_capture),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * Fixed height for the camera preview area — wide enough for a recognisable
 * preview, short enough to leave the shutter button at thumb reach on a 6"
 * display. Not tied to screen aspect ratio; the ModalBottomSheet host caps
 * height at the top-system-bar inset.
 */
private val CameraSheetHeight = 480.dp

/**
 * Target edge for CameraX `ImageCapture`. Matches `ChatViewModel`'s
 * downscale invariant so the raw capture bitmap is already near-1024 and
 * the VM's defensive downscale is effectively a no-op. Keeps peak memory
 * predictable on high-resolution sensors (50 MP+ devices).
 */
private const val CAPTURE_TARGET_PX = 1024

/**
 * Rotates [bitmap] clockwise by [degrees]. CameraX exposes rotation as
 * degrees (0/90/180/270) via `ImageProxy.imageInfo.rotationDegrees`, not as
 * `ExifInterface` constants — `MediaUtils.rotateBitmap` takes the latter,
 * so it doesn't fit this call site. Degrees outside 0..359 are normalized;
 * a normalized 0 returns the input bitmap without allocating.
 */
internal fun rotateBitmapByDegrees(bitmap: Bitmap, degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return bitmap
    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Pure boolean heuristic extracted from the permission-denied path so it
 * can be unit-tested without a `compose-ui-test` harness. [activity] being
 * null (no hosting Activity resolvable from the Compose context) is always
 * permanent — we can't re-prompt without one. When the OS will still show
 * a rationale ([rationaleVisible] = true), the denial is soft.
 */
internal fun isCameraDenialPermanent(activity: Activity?, rationaleVisible: Boolean): Boolean =
    activity == null || !rationaleVisible

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
