package app.sanctum.machina.ui.modelmanager

import app.sanctum.machina.core.registry.ModelEntry

private const val BYTES_PER_GB: Long = 1_073_741_824L

/**
 * Per-row pre-flight gate verdict (Phase 3.5 Slice 1, tech-spec § Data Models).
 *
 * Captured once when [ModelManagerViewModel.rows] is materialized — `totalBytes` is
 * a runtime-stable quantity (the kernel-reported `MemoryInfo.totalMem` cannot change
 * between cold-starts) and `minGb` is read from the allowlist Manifest, so a single
 * snapshot is enough for the lifetime of the row.
 *
 * `minGb` is the source value (not nullable here): the parser already rejects null /
 * out-of-range entries before they reach the registry (Task 1), so by the time a
 * `GateDecision` is built the field is concrete. When the upstream value is
 * defensively-null (e.g. parser drift), [gateAllowsDownload] still fails closed —
 * `allowed = false` with `minGb = 0` for the secondary text fallback.
 */
data class GateDecision(
    val allowed: Boolean,
    val totalBytes: Long,
    val minGb: Int,
)

/** Pairing of [ModelEntry] (drives the existing UI) with its [GateDecision] (drives Slice 1). */
data class ModelRowState(
    val entry: ModelEntry,
    val gate: GateDecision,
)

/**
 * Pure threshold check (Decision 4): a model may be downloaded iff the device's total
 * RAM equals or exceeds `minGb` gigabytes (1 GB = 1_073_741_824 bytes, matches
 * `MemoryInfo.totalMem` units).
 *
 * `minGb = null` returns `false` — defence-in-depth against parser drift, even though
 * Task 1 fail-fasts on a null field at load time.
 */
fun gateAllowsDownload(totalBytes: Long, minGb: Int?): Boolean =
    minGb != null && totalBytes >= minGb.toLong() * BYTES_PER_GB

/**
 * Russian secondary text shown under the disabled "Скачать" button. Hand-rolled
 * (no `String.format` / `Locale.getDefault()`) so the digits stay ASCII regardless
 * of the device locale and the floor-to-0.1-GB precision is locked end-to-end.
 *
 * Source-of-truth for the visible text in Phase 3.5: production callers and unit
 * tests both go through this function. The mirror string resource
 * `R.string.model_gate_secondary` exists as a localization-reserve and is not read
 * by the UI in Task 5.
 */
fun formatRamShortage(totalBytes: Long, minGb: Int): String =
    "Недостаточно RAM (${formatGbFloor(totalBytes)} ГБ устройство, нужно $minGb ГБ)"

/**
 * Floor-precision GB renderer (e.g. `4_294_967_295L` → `"3.9"`, never `"4.0"`).
 *
 * Visibility is `internal` (not `private`) because Task 8's diagnostics screen
 * reuses it cross-file for the InProgress state's "X.X GB / Y GB" summary —
 * `:app` is the only consumer module, so `internal` keeps the helper module-scoped
 * without forcing an artificial public API.
 *
 * Implementation note: the `* 10L / BYTES_PER_GB` integer arithmetic floors by
 * default (Long quotient truncates toward zero) and is overflow-safe up to
 * ~9223 PB, well past any realistic device.
 */
internal fun formatGbFloor(bytes: Long): String {
    val tenths = (bytes * 10L) / BYTES_PER_GB
    return "${tenths / 10}.${tenths % 10}"
}
