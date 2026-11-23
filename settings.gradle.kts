pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// The decision engine and the API contract are built in the client repository and published from there, one version
// per release tag (docs/adr/0001). GitHub Packages asks for a token even to read a public package: on a developer's
// machine `gpr.user` and `gpr.key` in ~/.gradle/gradle.properties, in CI GITHUB_ACTOR and GITHUB_TOKEN. The
// developer's own properties come first: a GITHUB_TOKEN exported for another tool rarely has read:packages.
//
// -Ppursuitofwind.localArtifacts=true reads the two artifacts from the local Maven repository instead, after
// `./gradlew :shared:windengine:publishToMavenLocal :shared:contract:publishToMavenLocal` in a clone of the client.
val clientGroup = "io.github.esseckers.pursuitofwind"
val localArtifacts = providers.gradleProperty("pursuitofwind.localArtifacts").map(String::toBoolean).getOrElse(false)
val packagesUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
val packagesToken = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull

if (!localArtifacts && (packagesUser.isNullOrBlank() || packagesToken.isNullOrBlank())) {
    throw GradleException(
        "The engine and the contract come from GitHub Packages, which needs a token with read:packages. " +
            "Set gpr.user and gpr.key in ~/.gradle/gradle.properties, or publish the artifacts to the local Maven " +
            "repository and pass -Ppursuitofwind.localArtifacts=true. See \"Run it\" in README.md.",
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
