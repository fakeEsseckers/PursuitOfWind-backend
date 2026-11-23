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
 * refuses to start as root); it must be PostgreSQL 13 or newer, and its user must be allowed to create databases.
 */
internal object TestPostgres {
    private val server: DatabaseConfig by lazy {
        DatabaseConfig.parse(System.getenv("TEST_DATABASE_URL") ?: startEmbedded())
    }

    /** Runs [block] with a database nobody else uses, and drops it afterwards. */
    suspend fun <T> withDatabase(block: suspend (DatabaseConfig) -> T): T {
        val database = server.copy(name = "test_" + UUID.randomUUID().toString().replace("-", ""))
        execute(server, "create database ${database.name}")
        try {
            return block(database)
        } finally {
            drop(database)
        }
    }

    /** Takes [database] away with its connections cut, as an outage would. */
    fun drop(database: DatabaseConfig) = execute(server, "drop database if exists ${database.name} with (force)")

    /** How many connections are open to [database] right now. */
    fun connections(database: DatabaseConfig): Int =
        strings(server, "select count(*) from pg_stat_activity where datname = '${database.name}'").single().toInt()

    /** Runs [sql] in [database]. */
    fun execute(
        database: DatabaseConfig,
        sql: String,
    ) {
        DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    /** The first column of what [sql] finds in [database]. */
    fun strings(
        database: DatabaseConfig,
        sql: String,
    ): List<String> =
        DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
            connection.createStatement().use { statement ->
                val rows = statement.executeQuery(sql)
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }

    /** Starts a PostgreSQL from the bundled binaries, alive until this JVM exits, and gives its `DATABASE_URL`. */
    fun startEmbedded(): String = "postgresql://postgres@localhost:${EmbeddedPostgres.start().port}/postgres"
}
