pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Downloads the JDK the build asks for (21) when the machine has none, as the client's build does.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The decision engine and the API contract are built in the client repository and published from there, one version
// per release tag (docs/adr/0001). GitHub Packages asks for a token even to read a public package.
//
// A developer keeps it in local.properties in this folder, which git ignores and no other build on the machine reads:
//     gpr.user=<GitHub login>
//     gpr.key=<a token with read:packages>
// It is deliberately not taken from ~/.gradle/gradle.properties: every Gradle build on the machine reads that file,
// and its values override a project's own. CI has GITHUB_ACTOR and GITHUB_TOKEN in its environment. The file comes
// first: a GITHUB_TOKEN exported for another tool rarely has read:packages.
//
// pursuitofwind.localArtifacts=true, in the same file or as -P, reads the two artifacts from the local Maven
// repository instead, after `./gradlew :shared:windengine:publishToMavenLocal :shared:contract:publishToMavenLocal`
// in a clone of the client.
val localProperties =
    java.util.Properties().apply {
        providers.fileContents(layout.rootDirectory.file("local.properties")).asText.orNull?.let { load(it.reader()) }
    }

fun local(name: String): String? = localProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

val clientGroup = "io.github.esseckers.pursuitofwind"
val localArtifacts =
    (providers.gradleProperty("pursuitofwind.localArtifacts").orNull ?: local("pursuitofwind.localArtifacts")).toBoolean()
val packagesUser = local("gpr.user") ?: providers.environmentVariable("GITHUB_ACTOR").orNull
val packagesToken = local("gpr.key") ?: providers.environmentVariable("GITHUB_TOKEN").orNull

if (!localArtifacts && (packagesUser.isNullOrBlank() || packagesToken.isNullOrBlank())) {
    throw GradleException(
        "The engine and the contract come from GitHub Packages, which needs a token with read:packages. " +
            "Put gpr.user and gpr.key into local.properties in this folder (git ignores it), or publish the artifacts " +
            "to the local Maven repository and set pursuitofwind.localArtifacts=true there. See \"Run it\" in README.md.",
    )
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral {
            content { excludeGroup(clientGroup) }
        }
        if (localArtifacts) {
            mavenLocal {
                content { includeGroup(clientGroup) }
            }
        } else {
            maven("https://maven.pkg.github.com/fakeEsseckers/PursuitOfWind-KMP-client") {
                name = "PursuitOfWindClient"
                content { includeGroup(clientGroup) }
                credentials {
                    username = packagesUser
                    password = packagesToken
                }
            }
        }
    }
}

rootProject.name = "pursuitofwind-backend"
