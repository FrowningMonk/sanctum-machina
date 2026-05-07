plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.sanctum.machina.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-Xcontext-receivers"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    api(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.gson)
    // Promoted to `api` (Phase 3.6 Task 11): `ModelRegistry.resetConversation`
    // and `LlmModelHelper.resetConversation` expose `litertlm.Message` as a
    // parameter type for KV-cache replay, so consumers of `:core-runtime`
    // (`:app`) need this artifact on their compile classpath.
    api(libs.litertlm)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
