rootProject.name = "mdc-otel-kafka-reproducer"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

plugins {
    id("org.danilopianini.gradle-pre-commit-git-hooks").version("2.1.7")
}

val quarkusVersion = providers.gradleProperty("quarkusVersion").orElse("3.31.3").get()

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("quarkus-plugin", quarkusVersion)
            version("quarkus-platform", quarkusVersion)
            version("mockito", "5.21.0")

            plugin("quarkus", "io.quarkus").versionRef("quarkus-plugin")
            plugin("spotless", "com.diffplug.spotless").version("8.2.1")
            plugin("errorprone", "com.palantir.baseline-error-prone").version("6.79.0")
            plugin("test-logger", "com.adarshr.test-logger").version("4.0.0")

            library("quarkus-platform", "io.quarkus.platform", "quarkus-bom").versionRef("quarkus-platform")
            library("quarkus-logging", "io.quarkus", "quarkus-logging-json").versionRef("quarkus-platform")

            // testing
            library("assertj", "org.assertj", "assertj-core").version("3.27.7")
            library("mockito-core", "org.mockito", "mockito-core").versionRef("mockito")
            library("awaitility", "org.awaitility", "awaitility").version("4.3.0")
        }
    }
}
