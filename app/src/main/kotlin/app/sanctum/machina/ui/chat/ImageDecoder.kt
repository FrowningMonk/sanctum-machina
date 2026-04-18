package app.sanctum.machina.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.sanctum.machina.core.common.decodeSampledBitmapFromUri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a content URI into a downscaled [Bitmap] (~1024×1024) suitable
 * for multimodal inference. Interface exists for testability — tests inject
 * a deterministic fake that never touches the filesystem.
 *
 * `null` return means the source is unreachable or unreadable; callers
 * drop the entry silently and log via `ErrorLog("attachment-decode", ...)`.
 */
interface ImageDecoder {
    suspend fun decode(uri: Uri): Bitmap?
}

/** Target max edge for Photo Picker attachments (tech-spec R5, AC-10). */
private const val TARGET_EDGE: Int = 1024

class DefaultImageDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImageDecoder {
    override suspend fun decode(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        decodeSampledBitmapFromUri(context, uri, TARGET_EDGE, TARGET_EDGE)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ImageDecoderModule {
    @Binds
    @Singleton
    abstract fun bindImageDecoder(impl: DefaultImageDecoder): ImageDecoder
}
