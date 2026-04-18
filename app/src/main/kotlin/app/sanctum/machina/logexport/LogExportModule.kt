package app.sanctum.machina.logexport

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bindings that promote the logexport interfaces to their production
 * implementations. Introduced in Task 6 — the first task that injects
 * [LogExportManager] through Hilt (via `AboutViewModel`). Task 3 deliberately
 * deferred this module; the non-Hilt `:crash` path (Decision 5) builds the
 * same graph by hand through [LogExportManager]'s secondary constructor.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LogExportModule {

    @Binds
    abstract fun bindCommandRunner(impl: DefaultCommandRunner): CommandRunner

    @Binds
    abstract fun bindDeviceInfoProvider(impl: AndroidDeviceInfoProvider): DeviceInfoProvider
}
