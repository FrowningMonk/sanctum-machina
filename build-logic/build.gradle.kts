plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        register("gitVersion") {
            id = "phonewrap.git-version"
            implementationClass = "app.sanctum.machina.build.GitVersionPlugin"
        }
    }
}
