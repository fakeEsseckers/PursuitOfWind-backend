# 0002. PostgreSQL, SQL migrations at start, tests on a real database

- Status: Accepted
- Date: 2026-11-23

## Context

The app's in-app fake backend keeps everything in memory and forgets it when the process ends. The server has to keep users, profiles, spots, crews, rides and cached forecasts, and several of its rules are about concurrent writers: the last free seat of a ride, an `Idempotency-Key` seen twice, a refresh token used after it rotated.

The first staging runs on Railway, which hands a service its database as one `DATABASE_URL`. One developer maintains the server, and development also happens in containers without Docker.

## Decision

- **PostgreSQL.** Transactions, row locks and unique constraints carry the rules above, so they hold with more than one server instance. Every host under consideration offers it managed.
- **The schema is SQL, in versioned migrations.** Files in `src/main/resources/db/migration`, applied in order by Flyway. A migration that has reached `main` is never edited; a new one is added. Each slice of the API brings the tables it uses.
- **A schema of the server's own**, `pursuitofwind`, not `public`. Whatever a host puts into the database (extension tables, for one) is not in the way of the migrations, and the server touches nothing it did not create.
- **Migrations run at start, before the first request.** The server connects, migrates, then listens. A database that is not there, or a migration that fails, ends the process, so a broken deploy never takes traffic. A database that is only late, because it restarts beside the server, is waited for up to 30 seconds, so the two coming back together do not end in a crash loop. Flyway takes a lock in the database, so two instances starting together do not migrate twice.
- **One small connection pool (HikariCP), the PostgreSQL JDBC driver.** At most five connections per instance and one kept while idle: small managed databases allow a few dozen in all, and a deploy runs two instances side by side. Blocking calls run off the request threads. Nothing reactive: at this size it buys nothing and costs clarity.
- **`DATABASE_URL` in the form hosts hand out**, `postgresql://user:password@host:5432/name`. The password never reaches a message or a log line.
- **Credentials at rest are hashes.** The first table shows the rule: a user is found by the SHA-256 of the installation id, which the contract calls a long-lived credential; a copy of the table signs nobody in.
- **`GET /health` includes the database, as a readiness check.** It answers `503` while the database does not answer, so a host does not route to an instance that cannot serve. It asks over a connection of its own with a two-second limit, so a pool busy with requests does not read as an outage. It is not a liveness check: restarting the server does not bring a database back.
- **Tests run on a real PostgreSQL.** One server per test run, started from binaries that come with the test dependencies (macOS and Linux, Intel and ARM), and a new empty database per test; nothing to install, no Docker. `TEST_DATABASE_URL` names a server to use instead where those binaries cannot run. The same binaries back `./gradlew runDev`, the server on a database of its own for a developer's machine.
- **How queries are written is decided with the first real query**, in the pull request that adds sign-in.

## Alternatives considered

- **An ORM that owns the schema (generated or updated from entity classes)** — the schema of a deployed database would change as a side effect of code; a migration says exactly what changes and when.
- **Migrations as a separate deploy step** — one more thing to run and to forget; with one small service, start is the simplest moment that cannot be skipped.
- **H2 or another in-memory database for tests** — a different database: locks, constraints, types and `on conflict` behave differently, and those are exactly what the server's rules rest on.
- **Testcontainers** — the same real PostgreSQL, but it needs a Docker daemon on every machine that runs tests, which the development containers do not have.
- **R2DBC** — non-blocking down to the socket, at the price of a less mature driver and tooling; a bounded pool of blocking connections is enough for this API.
- **SQLite or a document store** — no row locks across instances in the first, no relational constraints in the second.

## Consequences

- Positive: a deploy migrates itself, and cannot serve on a schema it does not know.
- Positive: every test that touches data runs against the database production runs, with the migrations production applies.
- Positive: tests and a local server need nothing installed.
- Negative: start takes as long as the pending migrations; a long migration will need care (it holds up the deploy).
- Negative: the test binaries add about 150 megabytes of downloads to the first build, and cannot run as root.
- Negative: rolling back a release does not roll back its migrations; a migration has to keep the previous release working.
- Negative: the URL must be a direct connection. A transaction-mode pooler in front of PostgreSQL (PgBouncer) breaks the migration lock and the driver's prepared statements.
- Revisit when: a migration becomes too long to run at start, or more than one service shares the database.
