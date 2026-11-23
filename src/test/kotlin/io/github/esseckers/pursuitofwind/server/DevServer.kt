package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.server.db.DatabaseConfig
import io.github.esseckers.pursuitofwind.server.testing.TestPostgres

/**
 * The server for a developer's machine (`./gradlew runDev`). It brings its own PostgreSQL, started from the binaries
 * the tests use, so nothing has to be installed; the database is new at every start and gone at the end. Everything
 * else is the real server, configured from the environment like it (`PORT`).
 */
fun main() {
    serve(ServerConfig.from(System.getenv() + (DatabaseConfig.VARIABLE to TestPostgres.startEmbedded())))
}
