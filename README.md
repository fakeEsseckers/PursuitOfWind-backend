# Pursuit Of Wind — backend

The server of [Pursuit Of Wind](https://github.com/fakeEsseckers/PursuitOfWind-KMP-client), a kitesurf session decision app: is it worth driving to the spot today, and who from the crew is driving with free seats.

It implements the API the app already speaks. The contract (`api/openapi.yaml`), its Kotlin types and the decision engine are written in the client repository and arrive here as versioned artifacts, so the server answers with the bytes the app expects and reaches the verdicts the app shows ([ADR 0001](docs/adr/0001-server-stack-and-shared-artifacts.md)).

> Kite sizing and verdicts are decision support, not safety advice. Check the conditions on the spot and decide for yourself.

## Status

A running skeleton on its database: the process connects to PostgreSQL, brings the schema to its version, answers `GET /health`, and answers every other path with the contract's problem body. The API arrives one slice of the contract at a time; see the [roadmap](#roadmap).

## Run it

You need JDK 21 and access to the two artifacts of the client repository. GitHub Packages asks for a token even to read a public package (CI uses the workflow's own), so on your machine pick one:

- **A token.** Create a personal access token (classic) with only the `read:packages` scope and put it in a file named `local.properties` in this folder. Git ignores that file, and no other project on the machine reads it:

  ```properties
  gpr.user=<your GitHub login>
  gpr.key=<the token>
  ```

  The build deliberately does not take the token from `~/.gradle/gradle.properties`: every Gradle build on the machine reads that file, and its values override a project's own.

- **A clone of the client.** Publish the artifacts to your local Maven repository from the client's release tag, then put `pursuitofwind.localArtifacts=true` into `local.properties` in this folder (or add `-Ppursuitofwind.localArtifacts=true` to a single command):

  ```bash
  ./gradlew :shared:windengine:publishToMavenLocal :shared:contract:publishToMavenLocal -Ppursuitofwind.version=0.1.0
  ```

Then start the server. `runDev` brings its own PostgreSQL, started from binaries that come with the build, so nothing has to be installed; its database is new at every start:

```bash
./gradlew runDev
```

```bash
curl http://localhost:8080/health
```

The Android emulator reaches a server on the same machine at `http://10.0.2.2:8080`; the client's README says how to point a debug build at it.

## Commands

```text
./scripts/check.sh        # what CI runs: ktlint, detekt, tests, the distribution
./gradlew test            # tests only
./gradlew ktlintFormat    # auto-fix formatting
./gradlew runDev          # start the server on its own embedded PostgreSQL, on PORT (8080)
./gradlew run             # start the server on the database in DATABASE_URL
./gradlew installDist     # the distribution the image packages: build/install/server
```

## Tests

Tests run on a real PostgreSQL ([ADR 0002](docs/adr/0002-postgresql-sql-migrations-and-tests-on-a-real-database.md)). They start one from binaries that come with the test dependencies, so a machine needs nothing installed, and give every test a new empty database. PostgreSQL refuses to run as root; on such a machine point the tests at a server of your own, whose user may create databases:

```bash
TEST_DATABASE_URL=postgresql://user:password@localhost:5432/postgres ./gradlew test
```

## Configuration

Everything a deployment decides comes from environment variables. A value that is present and wrong stops the start with a message that names the variable.

| Variable | Meaning | Default |
| --- | --- | --- |
| `PORT` | the port to listen on | `8080` |
| `DATABASE_URL` | the PostgreSQL to use, as hosts hand it out: `postgresql://user:password@host:5432/name` | required |

The server connects at start and applies the migrations in `src/main/resources/db/migration`; a database that is not there, or a migration that fails, ends the process before it takes a request. `GET /health` answers `503` while the database does not answer.

## Image

The image packages the distribution Gradle has built, so no token enters an image layer:

```bash
./gradlew installDist
```

```bash
docker build --tag pursuitofwind-backend .
```

```bash
docker run --rm --publish 8080:8080 --env DATABASE_URL=postgresql://user:password@host:5432/name pursuitofwind-backend
```

The image gives the JVM up to 75% of the container's memory for its heap (`JAVA_OPTS` in the `Dockerfile`); a host can set its own `JAVA_OPTS`.

## Layout

| Path | What is there |
| --- | --- |
| `src/main/kotlin/.../server` | the application: start, configuration, what every route shares |
| `src/main/kotlin/.../server/api` | the HTTP side of the contract: problem bodies now, routes as they arrive |
| `src/main/kotlin/.../server/db` | the connection pool and the migration runner |
| `src/main/resources/db/migration` | the schema, as SQL migrations applied in order |
| `src/test/kotlin` | tests through real HTTP handling in memory, on a real PostgreSQL |
| `gradle/libs.versions.toml` | versions, including the client release this server is built against |
| `docs/adr` | architecture decisions |

## Roadmap

- [x] Skeleton: Ktor, the client's artifacts, configuration, health, image, CI
- [x] PostgreSQL: connection pool, SQL migrations applied at start, tests on a real database
- [ ] Contract tests against the `openapi.yaml` of the pinned client release
- [ ] Sign-in and tokens, validation problems, `Idempotency-Key`
- [ ] Profile and spots
- [ ] Real forecasts: source adapter, cache, scheduler, endpoint
- [ ] Crews, rides, seats, the event feed
- [ ] Push token intake
- [ ] Staging deployment, contract tests against it, debug builds of the app on it
- [ ] Production environment; server-side window evaluation and push notifications
