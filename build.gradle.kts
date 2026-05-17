plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt.application) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.protobuf) apply false
}

// Per-test progress visibility for every module's `:testDebugUnitTest` / `:test*` task — without
// this Gradle prints only the final tally, which makes a Robolectric hang indistinguishable
// from a slow test. STANDARD_OUT/STANDARD_ERROR stay off to avoid drowning the console in
// println() noise.
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            events(
                org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
            )
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }
}
