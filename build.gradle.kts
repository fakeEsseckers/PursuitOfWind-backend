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
    implementation(libs.ktor.serialization.kotlinx.json)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
    // Tests run in UTC whatever the machine's zone, as on CI.
    systemProperty("user.timezone", "UTC")
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
