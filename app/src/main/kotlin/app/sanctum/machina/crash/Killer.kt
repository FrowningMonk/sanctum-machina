package app.sanctum.machina.crash

import android.os.Process

/**
 * Abstraction over [Process.killProcess] so [CrashHandler] can be unit-tested
 * without actually killing the JVM. Production code uses [Killer.Default];
 * tests substitute a recording fake.
 *
 * Not a Hilt binding — [CrashHandler] is constructed manually in
 * `SanctumApplication.onCreate` (Task 5) and receives [Default] as an argument.
 */
interface Killer {
    fun kill(pid: Int)

    companion object Default : Killer {
        override fun kill(pid: Int) {
            Process.killProcess(pid)
        }
    }
}
