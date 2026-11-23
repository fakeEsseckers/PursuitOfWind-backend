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
| `api/` | the HTTP side of the contract: `open` / `signedIn` / `changing` register a handler for an `ApiRoute`, `Api` is what handlers share, `Idempotency` keeps answers under `Idempotency-Key`, `body` reads a request and `respondJson` answers with a DTO, `ProblemException` and `respondProblem` answer failures, one `*Routes.kt` per tag of the contract |
| `auth/` | `Sessions` (sign-in, token pairs, who an access token belongs to) and `Credentials` (tokens, ids, hashes) |
| `profile/`, `spot/` | one package per slice of the contract, shaped the same way: `ProfileRules` / `SpotRules` (the contract's limits) and `Profiles` / `Spots` (the tables) |
| `db/` | `Database` (connection pool, migration runner, `transaction { handle -> }` and `read { handle -> }` for queries) and `DatabaseConfig` (`DATABASE_URL`) |
| `src/main/resources/db/migration` | the schema: SQL migrations, applied in order at start |
| `src/test/kotlin` | tests through `testApplication` (`serverTest`), real HTTP handling in memory on a real PostgreSQL (`TestPostgres`) |

## Hard rules

1. Contract first, and the contract lives in the client repository. The server never answers with a route, field, status or problem code that `api/openapi.yaml` of the pinned client release does not have. A change to the API starts there: contract, DTOs, fake backend, a client release, then this server. A handler is registered for an `ApiRoute` (`open`, `signedIn`, `changing`), never for a path written by hand, and reads and writes the contract's DTOs. An operation that changes something is registered with `changing`: its handler runs in one transaction and returns an `Answer`, which is what makes `Idempotency-Key` work (ADR 0006). The limits of a request are the contract's `ApiLimits`, checked with `check422`. The ids in a path are checked against the contract's form by `signedIn` and `changing` before a handler runs, so nothing else from a path reaches SQL.
2. Verdicts, windows, kite sizes and the sectors of a spot come from the `windengine` artifact. No decision rule is written or adjusted here, and what the engine derives (a spot's sectors from the direction of its water) is not stored.
3. JSON is `ContractJson`. A handler reads the body with `body(type)` (or `Change.body`), which stops at 64 KB, and answers through `respondJson` or an `Answer`; never `call.receive` or `call.respond`, which depend on the call's headers. A line of text a person types is checked with `requireText`. Every failed call answers with a problem body (`respondProblem`) whose `code` is one of the contract's `ProblemCode`s, with the status and title the client's in-app fake backend uses for the same case; an unexpected failure tells the client nothing about the inside.
4. No secrets in the repository or the image. Tokens, passwords and addresses come from environment variables or CI secrets.
5. Configuration is read once at start through `ServerConfig`; a wrong value stops the start and names the variable. The time comes only from the `Clock` given to `module`, so a test can set it.
6. What the API does is tested through HTTP (`serverTest`), not around it, and on a real PostgreSQL (`TestPostgres`): no in-memory substitute, no mocked repository. Configuration and the database wrapper have tests of their own.
7. Queries are SQL as written, run through `Database.transaction` or `Database.read` (JDBI); rows are mapped by hand (ADR 0004). The schema changes only through SQL migrations in `src/main/resources/db/migration`, in the server's own schema `pursuitofwind`. A migration that has reached `main` is never edited; a new one is added. A credential (an installation id, a token) is stored as its hash, never as itself.
8. Verify library APIs and versions with official docs before using them. Versions live in `gradle/libs.versions.toml`; a library shared with the client (Ktor, kotlinx.serialization) keeps the client's version.
9. Kite sizing and verdicts are decision support, not safety advice.

## Workflow

Every change goes through a branch and a pull request; the owner reviews and merges. Run `./scripts/check.sh` before every PR. Use Conventional Commits. Write an ADR in `docs/adr` for a decision a reviewer would ask "why this way?" about.

## Environment notes

- Docker is not available in every development environment. What was not run locally is said so in the PR; CI builds and starts the image.
- Tests start their own PostgreSQL from bundled binaries. PostgreSQL refuses to run as root: in a container that runs as root, start a PostgreSQL server yourself and set `TEST_DATABASE_URL` (see `README.md`, Tests).
- Never claim something was verified when it was not.
