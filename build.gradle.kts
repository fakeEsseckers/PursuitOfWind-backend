// The Pursuit Of Wind server: a Ktor application that implements the client repository's api/openapi.yaml with the
// client's own decision engine and contract types.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    application
}

group = "io.github.esseckers.pursuitofwind"
version = providers.gradleProperty("pursuitofwind.version").getOrElse("0.0.0-SNAPSHOT")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

application {
    mainClass.set("io.github.esseckers.pursuitofwind.server.MainKt")
    // The start script the image runs: build/install/server/bin/server.
    applicationName = "server"
}

dependencies {
    implementation(libs.pursuitofwind.windengine)
    implementation(libs.pursuitofwind.contract)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.serialization.kotlinx.json)
    runtimeOnly(libs.logback.classic)

    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.embedded.postgres) {
        // Binaries for machines this project is not developed on; each is tens of megabytes.
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-windows-amd64")
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-linux-amd64-alpine")
    }
    // One PostgreSQL version on every machine; the library brings the Intel binaries for macOS and Linux, Apple
    // Silicon and ARM Linux need theirs named.
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testRuntimeOnly(libs.embedded.postgres.binaries.darwin.arm64)
    testRuntimeOnly(libs.embedded.postgres.binaries.linux.arm64)
}

tasks.test {
    useJUnitPlatform()
    // Tests run in UTC whatever the machine's zone, as on CI.
    systemProperty("user.timezone", "UTC")
    // Which PostgreSQL the tests ran on is part of what they proved: another one runs them again.
    inputs.property("testDatabaseUrl", providers.environmentVariable("TEST_DATABASE_URL").orElse(""))
}

// The server for a developer's machine: it brings its own PostgreSQL, started from the binaries the tests use, so
// nothing has to be installed. The database is new at every start.
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Runs the server on its own embedded PostgreSQL."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.esseckers.pursuitofwind.server.DevServerKt")
}

ktlint {
    version.set(libs.versions.ktlint.asProvider())
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("config/detekt/detekt.yml"))
}

// detekt 1.23.x embeds the Kotlin 2.0.21 compiler and refuses to run on another one. Keep its isolated classpath on
// that version; the project itself compiles with the catalog's Kotlin.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
    }
}
