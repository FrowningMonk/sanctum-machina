import app.sanctum.machina.build.GitVersionExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.application)
    alias(libs.plugins.ksp)
    id("phonewrap.git-version")
}

android {
    namespace = "app.sanctum.machina"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.sanctum.machina"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = the<GitVersionExtension>().versionName.orNull
            ?: "v0.3.5-diagnostics-fallback"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "MAIN_ACTIVITY_CLASS_NAME",
            "\"app.sanctum.machina.MainActivity\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "ru")
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

    // Make Room-generated schema JSON files visible to `MigrationTestHelper` on the
    // instrumentation runtime (it reads them from app assets under `schemas/<dbClass>/N.json`).
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-runtime"))
    implementation(project(":core-settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.google.android.material)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.compose.richtext.commonmark)
    implementation(libs.compose.richtext.ui.material3)
    implementation(libs.androidx.compose.ui.text.google.fonts)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Needed by SettingsMigrationHelper for atomic DataStore<AppSettings>
    // updateData — :core-settings exposes DataStore only as `implementation`,
    // so :app must depend on it directly.
    implementation(libs.androidx.datastore)

    // Phase 4 Decision 9: Tom Roush pdfbox-android fork (Apache 2.0).
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.litertlm)
    testImplementation(libs.androidx.datastore)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
}
