package io.github.esseckers.pursuitofwind.server.testing

import io.github.esseckers.pursuitofwind.server.db.DatabaseConfig
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.DriverManager
import java.util.UUID

/**
 * A real PostgreSQL for tests: one server for the whole run, and a new empty database for every test.
 *
 * The server is started from binaries that come with the test dependencies, so a machine needs nothing installed.
 * `TEST_DATABASE_URL` names a server to use instead, for a machine where those binaries cannot run (PostgreSQL
 * refuses to start as root); its user must be allowed to create databases.
 */
internal object TestPostgres {
    private val server: DatabaseConfig by lazy {
        DatabaseConfig.parse(System.getenv("TEST_DATABASE_URL") ?: startEmbedded())
    }

    /** Runs [block] with a database nobody else uses, and drops it afterwards. */
    suspend fun <T> withDatabase(block: suspend (DatabaseConfig) -> T): T {
        val name = "test_" + UUID.randomUUID().toString().replace("-", "")
        execute("create database $name")
        try {
            return block(server.copy(name = name))
        } finally {
            execute("drop database $name with (force)")
        }
    }

    /** Starts a PostgreSQL from the bundled binaries, alive until this JVM exits, and gives its `DATABASE_URL`. */
    fun startEmbedded(): String = "postgresql://postgres@localhost:${EmbeddedPostgres.start().port}/postgres"

    private fun execute(sql: String) {
        DriverManager.getConnection(server.jdbcUrl, server.user, server.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }
}
