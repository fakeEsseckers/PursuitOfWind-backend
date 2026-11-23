# Pursuit Of Wind — backend

The server of the Pursuit Of Wind kitesurf decision app: a Ktor application (Kotlin, JVM 21) that implements the API contract of the client repository, `fakeEsseckers/PursuitOfWind-KMP-client`. The contract's Kotlin types and the decision engine come from that repository as Maven artifacts pinned to one of its releases (`pursuitofwind` in `gradle/libs.versions.toml`, [ADR 0001](docs/adr/0001-server-stack-and-shared-artifacts.md)).

This file is the short operating manual. What is built and what is next: the roadmap in `README.md`.

## Language

- Talk to the owner in **Russian**.
- Everything committed (code, comments, KDoc, docs, ADRs, commit messages, PR and issue text) is **English**.
- After each task, give the owner a 3–5 line Russian summary: what was done, how to check, what is left.

## Commands

```text
./scripts/check.sh        # what CI runs: ktlint, detekt, tests, the distribution
./gradlew test            # tests only
./gradlew ktlintFormat    # auto-fix formatting
./gradlew runDev          # start the server on its own embedded PostgreSQL, on PORT (8080)
./gradlew run             # start the server on the database in DATABASE_URL
./gradlew installDist     # the distribution the image packages: build/install/server
```

The client's artifacts are read from its GitHub Packages with a token: `gpr.user` and `gpr.key` in the git-ignored `local.properties` of this folder, `GITHUB_ACTOR` and `GITHUB_TOKEN` in CI. The token is never taken from `~/.gradle/gradle.properties`, which every Gradle build on the machine reads. Without a token, publish the artifacts to the local Maven repository from a clone of the client and set `pursuitofwind.localArtifacts=true` in `local.properties` (or pass it as `-P` to a command); `README.md` has the exact steps.

Expect one Gradle deprecation warning from the detekt 1.23.8 plugin, as in the client.

## Layout

One Gradle project, package `io.github.esseckers.pursuitofwind.server`:

| Path | What is there |
| --- | --- |
| `Main.kt`, `ServerConfig.kt` | the start, and the configuration read from environment variables |
| `Application.kt` | `Application.module()`: what every route shares, and the routes |
| `api/` | the HTTP side of the contract: problem bodies now, routes as they arrive |
| `db/` | `Database` (connection pool, migration runner) and `DatabaseConfig` (`DATABASE_URL`) |
| `src/main/resources/db/migration` | the schema: SQL migrations, applied in order at start |
| `src/test/kotlin` | tests through `testApplication` (`serverTest`), real HTTP handling in memory on a real PostgreSQL (`TestPostgres`) |

## Hard rules

1. Contract first, and the contract lives in the client repository. The server never answers with a route, field, status or problem code that `api/openapi.yaml` of the pinned client release does not have. A change to the API starts there: contract, DTOs, fake backend, a client release, then this server.
2. Verdicts, windows and kite sizes come from the `windengine` artifact. No decision rule is written or adjusted here.
3. JSON is `ContractJson`. Every failed call answers with a problem body (`respondProblem`) whose `code` is one of the contract's `ProblemCode`s, with the status and title the client's in-app fake backend uses for the same case; an unexpected failure tells the client nothing about the inside.
4. No secrets in the repository or the image. Tokens, passwords and addresses come from environment variables or CI secrets.
5. Configuration is read once at start through `ServerConfig`; a wrong value stops the start and names the variable.
6. What the API does is tested through HTTP (`serverTest`), not around it, and on a real PostgreSQL (`TestPostgres`): no in-memory substitute, no mocked repository. Configuration and the database wrapper have tests of their own.
7. The schema changes only through SQL migrations in `src/main/resources/db/migration`, in the server's own schema `pursuitofwind`. A migration that has reached `main` is never edited; a new one is added. A credential (an installation id, a token) is stored as its hash, never as itself.
8. Verify library APIs and versions with official docs before using them. Versions live in `gradle/libs.versions.toml`; a library shared with the client (Ktor, kotlinx.serialization) keeps the client's version.
9. Kite sizing and verdicts are decision support, not safety advice.

## Workflow

Every change goes through a branch and a pull request; the owner reviews and merges. Run `./scripts/check.sh` before every PR. Use Conventional Commits. Write an ADR in `docs/adr` for a decision a reviewer would ask "why this way?" about.

## Environment notes

- Docker is not available in every development environment. What was not run locally is said so in the PR; CI builds and starts the image.
- Tests start their own PostgreSQL from bundled binaries. PostgreSQL refuses to run as root: in a container that runs as root, start a PostgreSQL server yourself and set `TEST_DATABASE_URL` (see `README.md`, Tests).
- Never claim something was verified when it was not.
