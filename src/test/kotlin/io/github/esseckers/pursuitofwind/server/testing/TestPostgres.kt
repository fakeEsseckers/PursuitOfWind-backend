package io.github.esseckers.pursuitofwind.server.testing

import io.github.esseckers.pursuitofwind.server.db.DatabaseConfig
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * A real PostgreSQL for tests: one server for the whole run, and a new empty database for every test.
 *
 * The server is started from binaries that come with the test dependencies, so a machine needs nothing installed.
 * `TEST_DATABASE_URL` names a server to use instead, for a machine where those binaries cannot run (PostgreSQL
 * refuses to start as root); it must be PostgreSQL 13 or newer, and its user must be allowed to create databases.
 */
internal object TestPostgres {
    private const val DROP_ATTEMPTS = 20
    private const val DROP_PAUSE_MILLIS = 250L
    private const val INSUFFICIENT_PRIVILEGE = "42501"

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

    /**
     * Takes [database] away with its connections cut, as an outage would. A role that is not a superuser cannot cut
     * the server's own autovacuum off a database, so the drop is tried again while one is passing through.
     */
    fun drop(database: DatabaseConfig) {
        repeat(DROP_ATTEMPTS - 1) {
            try {
                return execute(server, "drop database if exists ${database.name} with (force)")
            } catch (e: SQLException) {
                if (e.sqlState != INSUFFICIENT_PRIVILEGE) throw e
                Thread.sleep(DROP_PAUSE_MILLIS)
            }
        }
        execute(server, "drop database if exists ${database.name} with (force)")
    }

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

    /**
     * Runs [sql] in [database] in a transaction that stays open, with whatever it locked, until the connection
     * that comes back is closed.
     */
    fun holding(
        database: DatabaseConfig,
        sql: String,
    ): Connection {
        val connection = DriverManager.getConnection(database.jdbcUrl, database.user, database.password)
        connection.autoCommit = false
        connection.createStatement().use { it.execute(sql) }
        return connection
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
