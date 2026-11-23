pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// The decision engine and the API contract are built in the client repository and published from there, one version
// per release tag (docs/adr/0001). GitHub Packages asks for a token even to read a public package: in CI it is the
// workflow's own, on a developer's machine `gpr.user` and `gpr.key` in ~/.gradle/gradle.properties.
//
// -Ppursuitofwind.localArtifacts=true reads the two artifacts from the local Maven repository instead, after
// `./gradlew :shared:windengine:publishToMavenLocal :shared:contract:publishToMavenLocal` in a clone of the client.
val clientGroup = "io.github.esseckers.pursuitofwind"
val localArtifacts = providers.gradleProperty("pursuitofwind.localArtifacts").map(String::toBoolean).getOrElse(false)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (localArtifacts) {
            mavenLocal {
                content { includeGroup(clientGroup) }
            }
        }
        mavenCentral {
            content { excludeGroup(clientGroup) }
        }
        maven("https://maven.pkg.github.com/fakeEsseckers/PursuitOfWind-KMP-client") {
            name = "PursuitOfWindClient"
            content { includeGroup(clientGroup) }
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orElse(providers.gradleProperty("gpr.user")).orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orElse(providers.gradleProperty("gpr.key")).orNull
            }
        }
    }
}

rootProject.name = "pursuitofwind-backend"
