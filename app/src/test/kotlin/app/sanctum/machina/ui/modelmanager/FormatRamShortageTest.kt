package app.sanctum.machina.ui.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [formatRamShortage] / [formatGbFloor] (Phase 3.5 Task 5, Slice 1).
 *
 * Locks the russian source-of-truth secondary text shown under the disabled "Скачать"
 * button: "Недостаточно RAM (X.X ГБ устройство, нужно Y ГБ)". `formatGbFloor` must
 * floor (NOT round) so a 3.99 GB device renders as "3.9 ГБ", which is exactly what
 * the user reads while diagnosing why the download is blocked.
 */
class FormatRamShortageTest {

    @Test
    fun format_5_3_GB_with_minGb_6() {
        // 5_694_498_816 bytes → floor(5.302... GB) → "5.3 ГБ".
        val actual = formatRamShortage(totalBytes = 5_694_498_816L, minGb = 6)
        assertEquals("Недостаточно RAM (5.3 ГБ устройство, нужно 6 ГБ)", actual)
    }

    @Test
    fun format_10_7_GB_with_minGb_16() {
        // 11_500_000_000 bytes → floor(10.71 GB) → "10.7 ГБ".
        val actual = formatRamShortage(totalBytes = 11_500_000_000L, minGb = 16)
        assertEquals("Недостаточно RAM (10.7 ГБ устройство, нужно 16 ГБ)", actual)
    }

    @Test
    fun format_exactly_5GB_yields_5_0() {
        // 5_368_709_120 = 5 * 1_073_741_824. Trailing ".0" must be present (locks "5 ГБ" regression).
        val actual = formatRamShortage(totalBytes = 5_368_709_120L, minGb = 6)
        assertTrue(
            "expected '5.0 ГБ' substring, got: $actual",
            actual.contains("5.0 ГБ"),
        )
    }

    @Test
    fun format_just_below_4GB_floors_to_3_9() {
        // 4_294_967_295 = (4 GB - 1 byte). Must floor to "3.9", never round up to "4.0".
        val actual = formatRamShortage(totalBytes = 4_294_967_295L, minGb = 4)
        assertTrue(
            "expected '3.9 ГБ' substring (floor, not round), got: $actual",
            actual.contains("3.9 ГБ"),
        )
    }
}
