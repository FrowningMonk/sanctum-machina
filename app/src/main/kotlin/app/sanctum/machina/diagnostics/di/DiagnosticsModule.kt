package app.sanctum.machina.diagnostics.di

import app.sanctum.machina.core.registry.InitDiagnostics
import app.sanctum.machina.diagnostics.DiagnosticsState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Promotes the `:core-runtime` [InitDiagnostics] interface to its `:app`-side
 * implementation [DiagnosticsState]. Same shape as `LogExportModule` for
 * `CommandRunner` / `DeviceInfoProvider` — `@Binds` carries no scope; the
 * `@Singleton` on the impl class plus `@InstallIn(SingletonComponent::class)`
 * here is the scoping pair.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    abstract fun bindInitDiagnostics(impl: DiagnosticsState): InitDiagnostics
}
