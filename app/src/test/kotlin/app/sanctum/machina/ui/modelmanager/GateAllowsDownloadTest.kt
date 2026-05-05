package app.sanctum.machina.ui.modelmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [gateAllowsDownload] (Phase 3.5 Task 5, Slice 1).
 *
 * Boundary fixtures cover Decision 4 thresholds (E2B = 4 GB, E4B = 6 GB after the
 * Task-1 recalibration) plus AC-T4 device-matrix anchors (3.0 GB / 11.5 GB vs minGb=4)
 * and the Long arithmetic edge cases (`(minGb * 1_073_741_824L)` overflow, exact
 * equality, just-below). No Robolectric, no Android imports — keep this harness
 * pure JVM so future drift toward Android-only types in `GateDecision.kt` breaks the
 * build instead of silently degrading to a Robolectric harness.
 */
class GateAllowsDownloadTest {

    @Test
    fun gateAllowsDownload_above_threshold_returns_true() {
        // 11.5 GB — Honor 200 totalMem against E4B's minGb=6 should pass comfortably.
        assertTrue(gateAllowsDownload(totalBytes = 11_500_000_000L, minGb = 6))
    }

    @Test
    fun gateAllowsDownload_below_threshold_returns_false() {
        // 5.3 GB — S20 FE-class device totalMem against E4B's minGb=6 must be blocked.
        assertFalse(gateAllowsDownload(totalBytes = 5_300_000_000L, minGb = 6))
    }

    @Test
    fun gateAllowsDownload_lower_minGb_passes() {
        // Same 5.3 GB device against E2B's minGb=4 — should pass after Task-1 recalibration.
        assertTrue(gateAllowsDownload(totalBytes = 5_300_000_000L, minGb = 4))
    }

    @Test
    fun gateAllowsDownload_exact_equality_passes() {
        // Exactly 4 GB in bytes (4 * 1_073_741_824) — boundary is inclusive (>=).
        assertTrue(gateAllowsDownload(totalBytes = 4_294_967_296L, minGb = 4))
    }

    @Test
    fun gateAllowsDownload_just_below_fails() {
        // One byte below 4 GB — boundary must be strict (>= not >).
        assertFalse(gateAllowsDownload(totalBytes = 4_294_967_295L, minGb = 4))
    }

    @Test
    fun gateAllowsDownload_null_minGb_returns_false() {
        // Defence-in-depth: parser rejects null after Task 1, but the gate must still fail closed.
        assertFalse(gateAllowsDownload(totalBytes = 5_000_000_000L, minGb = null))
    }

    @Test
    fun gateAllowsDownload_3GB_below_minGb_4_returns_false() {
        // AC-T4 boundary: a 3.0 GB device (3 * 1_073_741_824 bytes) must fail E2B's minGb=4 gate.
        assertFalse(gateAllowsDownload(totalBytes = 3_221_225_472L, minGb = 4))
    }

    @Test
    fun gateAllowsDownload_11_5GB_above_minGb_4_returns_true() {
        // AC-T4 boundary: a Honor-200-class 11.5 GB device must pass E2B's minGb=4 gate.
        assertTrue(gateAllowsDownload(totalBytes = 12_348_030_976L, minGb = 4))
    }
}
