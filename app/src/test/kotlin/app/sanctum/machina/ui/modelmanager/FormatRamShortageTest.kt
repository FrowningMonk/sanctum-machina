package app.sanctum.machina.ui.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [formatRamShortage] / [formatGbFloor] (Phase 3.5 Task 5, Slice 1).
 *
 * Locks the source-of-truth secondary text shown under the disabled "Download"
 * button: "Not enough RAM (X.X GB device, needs Y GB)". `formatGbFloor` must
 * floor (NOT round) so a 3.99 GB device renders as "3.9 GB", which is exactly what
 * the user reads while diagnosing why the download is blocked.
 */
class FormatRamShortageTest {

    @Test
    fun format_5_3_GB_with_minGb_6() {
        // 5_694_498_816 bytes → floor(5.302... GB) → "5.3 GB".
        val actual = formatRamShortage(totalBytes = 5_694_498_816L, minGb = 6)
        assertEquals("Not enough RAM (5.3 GB device, needs 6 GB)", actual)
    }

    @Test
    fun format_10_7_GB_with_minGb_16() {
        // 11_500_000_000 bytes → floor(10.71 GB) → "10.7 GB".
        val actual = formatRamShortage(totalBytes = 11_500_000_000L, minGb = 16)
        assertEquals("Not enough RAM (10.7 GB device, needs 16 GB)", actual)
    }

    @Test
    fun format_exactly_5GB_yields_5_0() {
        // 5_368_709_120 = 5 * 1_073_741_824. Trailing ".0" must be present (locks "5 GB" regression).
        val actual = formatRamShortage(totalBytes = 5_368_709_120L, minGb = 6)
        assertEquals("Not enough RAM (5.0 GB device, needs 6 GB)", actual)
    }

    @Test
    fun format_just_below_4GB_floors_to_3_9() {
        // 4_294_967_295 = (4 GB - 1 byte). Must floor to "3.9", never round up to "4.0".
        val actual = formatRamShortage(totalBytes = 4_294_967_295L, minGb = 4)
        assertEquals("Not enough RAM (3.9 GB device, needs 4 GB)", actual)
    }
}
