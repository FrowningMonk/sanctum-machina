package app.sanctum.machina.logexport

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import app.sanctum.machina.BuildConfig
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
 * `ActivityManager.MemoryInfo` — all through the injected [context] so the
 * class stays directly constructable without Hilt (the non-Hilt path used by
 * `:crash`'s [LogExportManager] secondary constructor).
 *
 * `activeModelId` and `downloadedModels` are deliberately stubbed to `null` /
 * empty list for Phase 2.5 — the real model registry integration lands in a
 * later phase and is tracked in `NOTES.md`. The export falls back to
 * `active model: none` and `downloaded models:\n  (none)` in the header.
 */
class AndroidDeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceInfoProvider {

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

    // TODO(Phase 3+): wire to ModelRegistry — tracked in NOTES.md backlog
    //  ("Phase 2.5 follow-up: wire DeviceInfoCollector.activeModelId/downloadedModels").
    override fun activeModelId(): String? = null

    // TODO(Phase 3+): wire to ModelRegistry — see activeModelId().
    override fun downloadedModels(): List<Pair<String, Long>> = emptyList()

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
