package app.sanctum.machina.logexport

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import app.sanctum.machina.BuildConfig
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Renders the fixed-shape header block that sits at the top of the exported
 * `.txt`. Values are pulled from [provider] so tests can inject a
 * deterministic stub without Robolectric — the class itself has no Android
 * imports and no static `Build`/`PackageInfo` lookups.
 *
 * Output format is stable and string-matched by [DeviceInfoCollectorTest]:
 * ```
 * === Sanctum Machina diagnostic log ===
 * exported: <ISO-8601 with offset>
 * applicationId: <fqn>
 * version: <versionName> (<versionCode>), debug=<true|false>
 * device: <manufacturer> / <model> / Android <release> (API <level>)
 * memory: total=<G.G> GB, available=<G.G> GB
 * active model: <id or "none">
 * downloaded models:
 *   - <id> (<size>)         # or "  (none)" when the list is empty
 * ```
 */
class DeviceInfoCollector @Inject constructor(
    private val provider: DeviceInfoProvider,
) {
    fun buildHeader(): String = buildString {
        append("=== Sanctum Machina diagnostic log ===\n")
        append("exported: ").append(provider.nowIso()).append('\n')
        append("applicationId: ").append(provider.applicationId()).append('\n')
        append("version: ").append(provider.versionName())
            .append(" (").append(provider.versionCode()).append(')')
            .append(", debug=").append(provider.isDebug()).append('\n')
        append("device: ").append(provider.manufacturer())
            .append(" / ").append(provider.model())
            .append(" / Android ").append(provider.androidRelease())
            .append(" (API ").append(provider.apiLevel()).append(")\n")
        append("memory: total=").append(formatGb(provider.totalMemoryBytes()))
            .append(", available=").append(formatGb(provider.availableMemoryBytes()))
            .append('\n')
        append("active model: ").append(provider.activeModelId() ?: "none").append('\n')
        append("downloaded models:\n")
        val downloaded = provider.downloadedModels()
        if (downloaded.isEmpty()) {
            append("  (none)\n")
        } else {
            for ((id, bytes) in downloaded) {
                append("  - ").append(id).append(" (").append(formatGb(bytes)).append(")\n")
            }
        }
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.ROOT, "%.1f GB", gb)
    }
}

/**
 * Header values, all read via this interface so tests stub deterministically
 * without Robolectric. Production binding is [AndroidDeviceInfoProvider].
 */
interface DeviceInfoProvider {
    fun applicationId(): String
    fun versionName(): String
    fun versionCode(): Long
    fun isDebug(): Boolean
    fun manufacturer(): String
    fun model(): String
    fun androidRelease(): String
    fun apiLevel(): Int
    fun totalMemoryBytes(): Long
    fun availableMemoryBytes(): Long
    fun activeModelId(): String?
    fun downloadedModels(): List<Pair<String, Long>>
    fun nowIso(): String
}

/**
 * Production [DeviceInfoProvider]. Reads from `PackageManager`, `Build`, and
 * `ActivityManager.MemoryInfo` — all through the injected [context].
 *
 * Two construction paths:
 *
 *  * **Hilt primary** — `@Inject constructor(Context, ModelRegistry)`. In the
 *    main process, [ModelRegistry] drives [activeModelId] and
 *    [downloadedModels] from `registry.models.value`.
 *  * **Non-Hilt secondary** — `AndroidDeviceInfoProvider(Context)` passes
 *    `registry = null`. Used by the `:crash` process (Decision 10): the crash
 *    process has no running engine, so model data is genuinely unavailable.
 *    The export falls back to `active model: none` and
 *    `downloaded models:\n  (none)` in the header.
 */
class AndroidDeviceInfoProvider private constructor(
    @ApplicationContext private val context: Context,
    // Registry is captured as a thunk so the :crash-process ctor can supply an
    // empty-list fallback without a separate `NullModelRegistry` implementation
    // and without leaking a Hilt dependency into that path (Decision 10).
    private val entriesProvider: () -> List<ModelEntry>,
) : DeviceInfoProvider {

    // Hilt-injected path (main process) — ModelRegistry provides real state.
    @Inject constructor(
        @ApplicationContext context: Context,
        registry: ModelRegistry,
    ) : this(context, { registry.models.value })

    // Non-Hilt path (:crash process, per Decision 10). Registry is genuinely
    // unavailable — the crashed app has no engine; stubs fall back to null /
    // empty so the header reads `active model: none`.
    constructor(@ApplicationContext context: Context) : this(context, { emptyList() })

    override fun applicationId(): String = context.packageName

    override fun versionName(): String = packageInfo().versionName ?: "unknown"

    @Suppress("DEPRECATION")
    override fun versionCode(): Long {
        val info = packageInfo()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    override fun isDebug(): Boolean = BuildConfig.DEBUG

    override fun manufacturer(): String = Build.MANUFACTURER ?: "unknown"
    override fun model(): String = Build.MODEL ?: "unknown"
    override fun androidRelease(): String = Build.VERSION.RELEASE ?: "unknown"
    override fun apiLevel(): Int = Build.VERSION.SDK_INT

    override fun totalMemoryBytes(): Long = memoryInfo().totalMem
    override fun availableMemoryBytes(): Long = memoryInfo().availMem

    // Stable HF repo id of the single model whose engine is Ready (single-active-engine
    // invariant). `null` when no engine is loaded OR when the registry is unavailable
    // (:crash path).
    override fun activeModelId(): String? =
        entriesProvider()
            .firstOrNull { it.initStatus === ModelInitStatus.Ready }
            ?.model
            ?.modelId
            ?.takeIf { it.isNotEmpty() }

    // List of (modelId, sizeInBytes) for every registry entry marked SUCCEEDED.
    // `modelId` is the stable HF repo id; size comes from the allowlist entry.
    override fun downloadedModels(): List<Pair<String, Long>> =
        entriesProvider()
            .filter { it.downloadStatus.status == ModelDownloadStatusType.SUCCEEDED }
            .map { it.model.modelId to it.model.sizeInBytes }

    override fun nowIso(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun packageInfo(): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, 0)

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info
    }
}
