package app.sanctum.machina.core.di

import android.content.Context
import app.sanctum.machina.core.data.DefaultDownloadRepository
import app.sanctum.machina.core.data.DownloadRepository
import app.sanctum.machina.core.inference.LlmChatModelHelper
import app.sanctum.machina.core.registry.DefaultModelRegistry
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.runtime.LlmModelHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreRuntimeModule {

  @Provides
  @Singleton
  fun provideDownloadRepository(@ApplicationContext context: Context): DownloadRepository =
    DefaultDownloadRepository(context)

  @Provides
  @Singleton
  fun provideLlmModelHelper(): LlmModelHelper = LlmChatModelHelper

  @Provides
  @Singleton
  fun provideModelRegistry(impl: DefaultModelRegistry): ModelRegistry = impl
}
