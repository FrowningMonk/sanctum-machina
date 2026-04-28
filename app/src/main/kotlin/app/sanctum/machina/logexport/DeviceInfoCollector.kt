package app.sanctum.machina.logexport

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Debug
import app.sanctum.machina.BuildConfig
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.diagnostics.DiagnosticsState
import app.sanctum.machina.diagnostics.InitSnapshot
import app.sanctum.machina.diagnostics.Outcome
import app.sanctum.machina.ui.modelmanager.formatGbFloor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
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
 * memory: total=<G.G> GB, available=<G.G> GB, threshold=<G.G> GB, lowMemory=<true|false>
 * process: java=<G.G> GB, native=<G.G> GB, totalPss=<G.G> GB    # `n/a` per-field on source error
 * last init: <Ok|Failed|InProgress|пока не было>                # see [formatLastInit]
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
            .append(", threshold=").append(formatGb(provider.thresholdMemoryBytes()))
            .append(", lowMemory=").append(provider.isLowMemory())
            .append('\n')
        append("process: java=").append(formatGbOrNa(provider.processJavaHeapBytes()))
            .append(", native=").append(formatGbOrNa(provider.processNativeHeapBytes()))
            .append(", totalPss=").append(formatGbOrNa(provider.processTotalPssBytes()))
            .append('\n')
        append("last init: ").append(formatLastInit(provider.lastInitSnapshot())).append('\n')
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

    private fun formatGbOrNa(bytes: Long): String =
        if (bytes == NA_SENTINEL) "n/a" else formatGb(bytes)
}

/**
 * Sentinel returned by [DeviceInfoProvider]'s process-memory getters when the
 * underlying source threw — `Debug.MemoryInfo` reads can fail on some OEMs.
 * `buildHeader` renders the offending field as `n/a` while sibling fields keep
 * their normal `X.X GB` rendering.
 */
internal const val NA_SENTINEL: Long = Long.MIN_VALUE

/**
 * Pure renderer for the `last init:` line. Four branches per AC-D6 + Decision 12:
 *  * `null` → `пока не было`
 *  * `Ok` → `<X.X> ГБ RAM · HH:mm · <modelName> · ok`
 *  * `Failed` → `<X.X> ГБ RAM · HH:mm · <modelName> · ошибка`
 *  * `InProgress` → `Идёт инициализация: <X.X> ГБ RAM · HH:mm · <modelName>`
 *
 * Russian `ГБ` (with floor-precision via [formatGbFloor]) is intentional and
 * matches the same units used on the diagnostics screen (Decision 12). The
 * `memory:` and `process:` rows use English `GB` via `formatGb` instead — those
 * are two different formatters and must not be confused.
 *
 * `zone` defaults to system default — production wants whatever the device user
 * sees when they read the export. Tests pin an explicit zone for determinism.
 *
 * `modelName` is flattened via [flattenForLogLine] before interpolation. Today
 * the value is allowlist-controlled (`DefaultModelRegistry.initialize` matches
 * against `ModelEntry.model.name`), but a stray `\n` would forge a header line
 * — defense-in-depth keeps that closed.
 */
internal fun formatLastInit(
    snapshot: InitSnapshot?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (snapshot == null) return "пока не было"
    val ramGb = formatGbFloor(snapshot.freeRamBytes)
    val hhmm = OffsetDateTime
        .ofInstant(Instant.ofEpochMilli(snapshot.atEpochMs), zone)
        .format(HH_MM)
    val name = flattenForLogLine(snapshot.modelName)
    return when (snapshot.outcome) {
        Outcome.Ok -> "$ramGb ГБ RAM · $hhmm · $name · ok"
        Outcome.Failed -> "$ramGb ГБ RAM · $hhmm · $name · ошибка"
        Outcome.InProgress -> "Идёт инициализация: $ramGb ГБ RAM · $hhmm · $name"
    }
}

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private val LINE_BREAK_OR_TAB = Regex("[\\r\\n\\t]+")

/** Collapse newlines/tabs to single spaces so an untrusted token can't split a log line. */
internal fun flattenForLogLine(raw: String): String =
    raw.replace(LINE_BREAK_OR_TAB, " ").trim()

/**
 * Header values, all read via this interface so tests stub deterministically
 * without Robolectric. Production binding is [AndroidDeviceInfoProvider].
 *
 * Process-memory getters ([processJavaHeapBytes], [processNativeHeapBytes],
 * [processTotalPssBytes]) may return [NA_SENTINEL] when the underlying
 * `Debug.MemoryInfo` / `Runtime` source throws — this is a per-field signal,
 * not a global error: the surviving fields still render normally.
 *
 * [lastInitSnapshot] returns `null` in the `:crash` process where
 * [app.sanctum.machina.diagnostics.DiagnosticsState] is unavailable; the
 * `last init:` row degrades to `пока не было` (AC-H4) while the five other
 * memory/process getters keep working through system APIs.
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
    fun thresholdMemoryBytes(): Long
    fun isLowMemory(): Boolean
    fun processJavaHeapBytes(): Long
    fun processNativeHeapBytes(): Long
    fun processTotalPssBytes(): Long
    fun lastInitSnapshot(): InitSnapshot?
    fun activeModelId(): String?
    fun downloadedModels(): List<Pair<String, Long>>
    fun nowIso(): String
}

/**
 * Production [DeviceInfoProvider]. Reads from `PackageManager`, `Build`,
 * `ActivityManager.MemoryInfo`, `Debug.MemoryInfo`, and `Runtime` — all through
 * the injected [context].
 *
 * Three construction paths:
 *
 *  * **Private primary** — `private constructor(Context, () -> List<ModelEntry>,
 *    () -> InitSnapshot?)`. Both registry and diagnostics are captured as thunks
 *    so the secondary ctors can supply per-process fallbacks without separate
 *    `Null*` implementations and without leaking Hilt deps into the `:crash`
 *    path (Decision 10).
 *  * **`@Inject` secondary (Hilt-entry-point, main process)** — receives both
 *    [ModelRegistry] and [DiagnosticsState] from the Hilt graph and forwards
 *    `{ registry.models.value }` + `{ state.lastInitSnapshot() }` thunks to the
 *    primary.
 *  * **Non-Hilt secondary `(Context)`** — `:crash` process (see
 *    `architecture.md` § «Non-Hilt construction in :crash»). `DiagnosticsState`
 *    is genuinely unavailable (singleton lives only in main process), so the
 *    snapshot thunk forwards `{ null }` — `last init:` row reads `пока не было`
 *    (AC-H4). Registry thunk likewise forwards `{ emptyList() }` — the crashed
 *    app has no engine, so `active model:` reads `none` and `downloaded models:`
 *    reads `(none)`. The five memory/process getters still work because they
 *    read live system APIs that don't depend on app singletons.
 */
class AndroidDeviceInfoProvider private constructor(
    @ApplicationContext private val context: Context,
    private val entriesProvider: () -> List<ModelEntry>,
    private val lastInitSnapshotProvider: () -> InitSnapshot?,
) : DeviceInfoProvider {

    @Inject constructor(
        @ApplicationContext context: Context,
        registry: ModelRegistry,
        state: DiagnosticsState,
    ) : this(context, { registry.models.value }, { state.lastInitSnapshot() })

    constructor(@ApplicationContext context: Context) : this(
        context,
        { emptyList() },
        { null },
    )

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
    override fun thresholdMemoryBytes(): Long = memoryInfo().threshold
    override fun isLowMemory(): Boolean = memoryInfo().lowMemory

    override fun processJavaHeapBytes(): Long = runCatching {
        val rt = Runtime.getRuntime()
        rt.totalMemory() - rt.freeMemory()
    }.getOrDefault(NA_SENTINEL)

    override fun processNativeHeapBytes(): Long = runCatching {
        Debug.getNativeHeapAllocatedSize()
    }.getOrDefault(NA_SENTINEL)

    override fun processTotalPssBytes(): Long = runCatching {
        // Debug.MemoryInfo.totalPss is in KB — multiply to bytes for the shared GB formatter.
        debugMemoryInfo().totalPss.toLong() * 1024L
    }.getOrDefault(NA_SENTINEL)

    override fun lastInitSnapshot(): InitSnapshot? = lastInitSnapshotProvider()

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

    private fun debugMemoryInfo(): Debug.MemoryInfo {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info
    }
}
