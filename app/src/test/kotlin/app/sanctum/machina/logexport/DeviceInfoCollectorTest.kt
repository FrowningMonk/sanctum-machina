package app.sanctum.machina.logexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeviceInfoCollector]. Hand-rolled [DeviceInfoProvider] stub —
 * tests never hit Build/PackageInfo/ActivityManager directly, so no Robolectric
 * needed (keeps the suite fast and isolates header formatting).
 */
class DeviceInfoCollectorTest {

    @Test
    fun headerFormatting_deterministicFromStub() {
        val provider = StubDeviceInfoProvider(
            applicationId = "app.sanctum.machina",
            versionName = "0.1.0",
            versionCode = 1,
            isDebug = true,
            manufacturer = "Honor",
            model = "HONOR 200",
            androidRelease = "14",
            apiLevel = 34,
            totalMemoryBytes = 12_564_408_729L, // ~11.7 GiB
            availableMemoryBytes = 6_657_199_718L, // ~6.2 GiB
            activeModelId = "litert-community/gemma-4-E2B-it-litert-lm",
            downloadedModels = listOf(
                "litert-community/gemma-4-E2B-it-litert-lm" to 3_328_599_818L // ~3.1 GiB
            ),
            nowIso = "2026-04-18T14:25:00+05:00",
        )

        val header = DeviceInfoCollector(provider).buildHeader()

        val expected = buildString {
            append("=== Sanctum Machina diagnostic log ===\n")
            append("exported: 2026-04-18T14:25:00+05:00\n")
            append("applicationId: app.sanctum.machina\n")
            append("version: 0.1.0 (1), debug=true\n")
            append("device: Honor / HONOR 200 / Android 14 (API 34)\n")
            append("memory: total=11.7 GB, available=6.2 GB\n")
            append("active model: litert-community/gemma-4-E2B-it-litert-lm\n")
            append("downloaded models:\n")
            append("  - litert-community/gemma-4-E2B-it-litert-lm (3.1 GB)\n")
        }
        assertEquals(expected, header)
    }

    @Test
    fun emptyDownloadedModels_rendersNonePlaceholder() {
        val provider = StubDeviceInfoProvider(
            activeModelId = null,
            downloadedModels = emptyList(),
        )
        val header = DeviceInfoCollector(provider).buildHeader()

        assertTrue(
            "header must end downloaded-models block with '  (none)', got:\n$header",
            header.contains("downloaded models:\n  (none)\n")
        )
        assertFalse(
            "header must NOT have a bare 'downloaded models:' with nothing following, got:\n$header",
            header.contains("downloaded models:\n\n")
        )
    }

    @Test
    fun nullActiveModelId_rendersNone() {
        val provider = StubDeviceInfoProvider(activeModelId = null)
        val header = DeviceInfoCollector(provider).buildHeader()

        assertTrue(
            "header must render 'active model: none' when activeModelId() is null, got:\n$header",
            header.contains("active model: none\n")
        )
    }

    private class StubDeviceInfoProvider(
        private val applicationId: String = "app.sanctum.machina",
        private val versionName: String = "0.1.0",
        private val versionCode: Long = 1,
        private val isDebug: Boolean = true,
        private val manufacturer: String = "Honor",
        private val model: String = "HONOR 200",
        private val androidRelease: String = "14",
        private val apiLevel: Int = 34,
        private val totalMemoryBytes: Long = 12_000_000_000L,
        private val availableMemoryBytes: Long = 6_000_000_000L,
        private val activeModelId: String? = null,
        private val downloadedModels: List<Pair<String, Long>> = emptyList(),
        private val nowIso: String = "2026-04-18T14:25:00+05:00",
    ) : DeviceInfoProvider {
        override fun applicationId() = applicationId
        override fun versionName() = versionName
        override fun versionCode() = versionCode
        override fun isDebug() = isDebug
        override fun manufacturer() = manufacturer
        override fun model() = model
        override fun androidRelease() = androidRelease
        override fun apiLevel() = apiLevel
        override fun totalMemoryBytes() = totalMemoryBytes
        override fun availableMemoryBytes() = availableMemoryBytes
        override fun activeModelId() = activeModelId
        override fun downloadedModels() = downloadedModels
        override fun nowIso() = nowIso
    }
}
