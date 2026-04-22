package app.sanctum.machina.engine

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory Phase-3 flag raised by `AppModule.provideSanctumDatabase` when the Room DB
 * file on disk could not be opened and had to be renamed to `sanctum.db.corrupt_*`
 * (Decision 6, AC-R6). `HomeScreen` (Task 10) reads this once in a `LaunchedEffect` to
 * decide whether to show the one-time "история повреждена" banner.
 *
 * The flag lives only for the duration of the current process — a reopen after process
 * restart must land in the "no corruption" branch unless the filesystem is genuinely
 * broken again. No DataStore / no SharedPreferences on purpose.
 */
@Singleton
class AppCorruptionState @Inject constructor() {
    // `@Volatile` because the write happens on the Hilt injection thread during
    // `provideSanctumDatabase` (typically main) while `HomeScreen`'s `LaunchedEffect` (Task 10)
    // reads on the Compose main thread — guarantees happens-before visibility so the one-time
    // "история повреждена" banner actually fires on ARM without a missed memory barrier.
    @Volatile var corruptionOccurred: Boolean = false
}
