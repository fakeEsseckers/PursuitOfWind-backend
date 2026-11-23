# Pursuit Of Wind — backend

The server of [Pursuit Of Wind](https://github.com/fakeEsseckers/PursuitOfWind-KMP-client), a kitesurf session decision app: is it worth driving to the spot today, and who from the crew is driving with free seats.

It implements the API the app already speaks. The contract (`api/openapi.yaml`), its Kotlin types and the decision engine are written in the client repository and arrive here as versioned artifacts, so the server answers with the bytes the app expects and reaches the verdicts the app shows ([ADR 0001](docs/adr/0001-server-stack-and-shared-artifacts.md)).

> Kite sizing and verdicts are decision support, not safety advice. Check the conditions on the spot and decide for yourself.

## Status

A running skeleton: the process starts, answers `GET /health`, and answers every other path with the contract's problem body. The API arrives one slice of the contract at a time; see the [roadmap](#roadmap).

## Run it

You need JDK 21 and access to the two artifacts of the client repository. GitHub Packages asks for a token even to read a public package (CI uses the workflow's own), so on your machine pick one:

- **A token.** Create a personal access token (classic) with the `read:packages` scope and put it in `~/.gradle/gradle.properties`, never in this repository:

  ```properties
  gpr.user=<your GitHub login>
  gpr.key=<the token>
  ```

- **A clone of the client.** Publish the artifacts to your local Maven repository from the client's release tag, then add `-Ppursuitofwind.localArtifacts=true` to every Gradle command here:

  ```bash
  ./gradlew :shared:windengine:publishToMavenLocal :shared:contract:publishToMavenLocal -Ppursuitofwind.version=0.1.0
  ```

Then start the server:

```bash
./gradlew run
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
./gradlew run             # start the server on PORT (8080)
./gradlew installDist     # the distribution the image packages: build/install/server
```

## Configuration

Everything a deployment decides comes from environment variables. A value that is present and wrong stops the start with a message that names the variable.

| Variable | Meaning | Default |
| --- | --- | --- |
| `PORT` | the port to listen on | `8080` |

## Image

The image packages the distribution Gradle has built, so no token enters an image layer:

```bash
./gradlew installDist
```

```bash
docker build --tag pursuitofwind-backend .
```

```bash
docker run --rm --publish 8080:8080 pursuitofwind-backend
```

The image gives the JVM up to 75% of the container's memory for its heap (`JAVA_OPTS` in the `Dockerfile`); a host can set its own `JAVA_OPTS`.

## Layout

| Path | What is there |
| --- | --- |
| `src/main/kotlin/.../server` | the application: start, configuration, what every route shares |
| `src/main/kotlin/.../server/api` | the HTTP side of the contract: problem bodies now, routes as they arrive |
| `src/test/kotlin` | tests through real HTTP handling in memory |
| `gradle/libs.versions.toml` | versions, including the client release this server is built against |
| `docs/adr` | architecture decisions |

## Roadmap

- [x] Skeleton: Ktor, the client's artifacts, configuration, health, image, CI
- [ ] PostgreSQL, schema and migrations
- [ ] Contract tests against the `openapi.yaml` of the pinned client release
- [ ] Sign-in and tokens, validation problems, `Idempotency-Key`
- [ ] Profile and spots
- [ ] Real forecasts: source adapter, cache, scheduler, endpoint
- [ ] Crews, rides, seats, the event feed
- [ ] Push token intake
- [ ] Staging deployment, contract tests against it, debug builds of the app on it
- [ ] Production environment; server-side window evaluation and push notifications
