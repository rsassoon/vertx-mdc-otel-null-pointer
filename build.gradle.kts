import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    alias(libs.plugins.quarkus)
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.test.logger)
}

group = "dev.mdcotel.reproducer"
version = System.getenv("CI_COMMIT_SHA") ?: "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")

    // https://github.com/palantir/gradle-baseline/tree/develop?tab=readme-ov-file#compalantirbaseline-error-prone
    options.errorprone.disable(
        "StrictUnusedVariable",
        "VarUsage",
        "PreferSafeLoggableExceptions",
    )
    options.errorprone.disableWarningsInGeneratedCode = true
//    options.compilerArgs.add("-Werror")
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.platform))

    // Rx + DI
    implementation("io.quarkus:quarkus-mutiny")
    implementation("io.quarkus:quarkus-arc")

    // Redis
    implementation("io.quarkus:quarkus-redis-client")

    // messaging
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-messaging")

    // Observability
    implementation("io.quarkus:quarkus-opentelemetry")
    implementation("io.quarkus:quarkus-smallrye-context-propagation")
    implementation(libs.quarkus.logging)

    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-jacoco")
    testImplementation("io.smallrye.reactive:smallrye-reactive-messaging-in-memory")
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
}

tasks.test {
    // quarkus changed the default behavior of dev services in tests in version 3.22.x
    // so we need to disable it explicitly
    systemProperty("quarkus.devservices.enabled", "false")
    // This will run all unit tests (i.e. all those without the tag 'integration')
    useJUnitPlatform {
        excludeTags("integration")
    }
}
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "Integration tests"

    val test by testing.suites.existing(JvmTestSuite::class)
    testClassesDirs = files(test.map { it.sources.output.classesDirs })
    classpath = files(test.map { it.sources.runtimeClasspath })
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter("test")
}

tasks.check {
    dependsOn("integrationTest")
}

val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    testImplementation(libs.mockito.core)
    mockitoAgent(libs.mockito.core) { isTransitive = false }
}

tasks.withType<Test> {
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED, TestLogEvent.FAILED, TestLogEvent.STANDARD_OUT, TestLogEvent.STANDARD_ERROR)
        showExceptions = true
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
        showStandardStreams = true
    }

    if (System.getenv("CI_COMMIT_SHA") != null) {
        // https://quarkus.io/guides/config-reference#multiple-profiles
        println("Running QUARKUS with with ci profile")
        systemProperty("quarkus.test.profile", "ci,test")
    }

    jvmArgs("-javaagent:${mockitoAgent.asPath}")

    java.testResultsDir = layout.buildDirectory.dir("test-results/test")
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

spotless {
    java {
        targetExclude("**/build/")
        removeUnusedImports()
        palantirJavaFormat()
        formatAnnotations()
    }

    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

tasks.withType<JavaCompile> {
    finalizedBy(tasks.spotlessApply)
}
