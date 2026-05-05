plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("gitVersion") {
            id = "phonewrap.git-version"
            implementationClass = "app.sanctum.machina.build.GitVersionPlugin"
        }
    }
}
