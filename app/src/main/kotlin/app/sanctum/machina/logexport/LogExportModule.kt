package app.sanctum.machina.logexport

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bindings that promote the logexport interfaces to their production
 * implementations. Introduced in Phase 2.5 Task 6 — the first task that
 * injected [LogExportManager] through Hilt; the SAF-export entry point now
 * lives in `DiagnosticsViewModel` (Phase 3.5 Task 8, Decision 11). Task 3
 * deliberately deferred this module; the non-Hilt `:crash` path (Decision 5)
 * builds the same graph by hand through [LogExportManager]'s secondary
 * constructor.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LogExportModule {

    @Binds
    abstract fun bindCommandRunner(impl: DefaultCommandRunner): CommandRunner

    @Binds
    abstract fun bindDeviceInfoProvider(impl: AndroidDeviceInfoProvider): DeviceInfoProvider

    @Binds
    abstract fun bindLogExporter(impl: LogExportManager): LogExporter
}
