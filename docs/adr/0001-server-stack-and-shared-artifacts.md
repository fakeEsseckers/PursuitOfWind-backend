# 0001. Server stack and the artifacts shared with the app

- Status: Accepted
- Date: 2026-11-23

## Context

The app exists and runs on an in-app fake backend that implements `api/openapi.yaml` of the client repository. This server replaces the fake. It must speak the API exactly as the app does, and it must reach the verdicts the app shows, because it will later tell riders about session windows the app then displays.

The client repository already publishes what both sides need, one version per release tag (its ADR 0012): `io.github.esseckers.pursuitofwind:contract` (the DTOs, routes, limits and the JSON configuration of the API) and `…:windengine` (the decision engine), with the specification attached to the same release.

The project has one developer. The server should cost little to understand next to the app, and little to run.

## Decision

- **Kotlin on JVM 21 with Ktor, on Netty.** The same language as the app, and the same Ktor and kotlinx.serialization versions as the client's catalog, so one set of types and one JSON configuration serve both sides.
- **The contract and the engine are dependencies, never copies.** `gradle/libs.versions.toml` pins one client release (`pursuitofwind`). The server serializes with the artifact's `ContractJson` and decides with its `DecisionEngine`. Moving to a new client release is a deliberate one-line change.
- **GitHub Packages of the client repository, with credentials from outside the build.** `GITHUB_ACTOR` and `GITHUB_TOKEN` in CI; on a developer's machine `gpr.user` and `gpr.key` in the project's git-ignored `local.properties`, and not in `~/.gradle/gradle.properties`, which every Gradle build on the machine reads and which overrides a project's own values. `pursuitofwind.localArtifacts=true` reads the two artifacts from the local Maven repository instead, for a machine without a token.
- **Every failure is the contract's problem body.** One function builds it. A request the server cannot read is `VALIDATION_FAILED`, as the app's in-app backend answers it; a request no route took is `NOT_FOUND`; a route's own problem passes through untouched; an unexpected exception is logged with its stack trace and answered with `INTERNAL` and nothing else.
- **Configuration from environment variables, read once at start.** A wrong value stops the start and names the variable. Nothing about a deployment is in the repository or the image.
- **One Gradle project.** Packages separate the HTTP side from the rest; modules are added when something needs a boundary a package cannot give.
- **The image packages a distribution built outside it.** `./gradlew installDist`, then a `Dockerfile` that copies `build/install/server` onto a JRE image and runs as a non-root user. CI builds the image and starts it on every pull request.
- **The client's quality rules.** ktlint and detekt with the client's versions and configuration, warnings as errors.

## Alternatives considered

- **Spring Boot** — more out of the box, but a second way of doing HTTP, serialization and tests next to the app's Ktor, and a heavier start for a small API.
- **One repository for client and server, or a composite build** — rejected in the client's ADR 0012: the client is a showcase of its own, and a composite build has no versions. A composite build stays available for a day when both sides change together; a release never uses it.
- **Generating server types from `openapi.yaml`** — a second set of types that can drift from the ones the app sends and receives. The specification is used to check the server (contract tests), not to generate it.
- **A multi-stage Docker build** — the build needs the GitHub Packages token, which would have to be passed into the image build as a secret. Building outside keeps the `Dockerfile` trivial and the token out of every layer.
- **A fat jar** — one file to copy, at the price of another plugin and merged service files. The distribution of the `application` plugin needs neither.

## Consequences

- Positive: the bytes on the wire and the verdicts are the app's own; a mismatch is a compile error or a failed contract test, not a bug report.
- Positive: nothing secret is needed to build the image, and nothing secret is in it.
- Negative: reading even a public package from GitHub Packages needs a token on every developer machine. CI reads with the workflow's own token, which works only while the packages are public.
- Negative: a change to the API or to a decision rule takes two releases, the client's and then the server's.
- Negative: versions of the shared libraries follow the client; the server cannot move ahead of it on its own.
- Revisit when: the artifacts move to Maven Central (the token goes away), or the server grows a part that deserves its own module.
