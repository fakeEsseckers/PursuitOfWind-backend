package io.github.esseckers.pursuitofwind.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.postgres.PostgresPlugin
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The server's PostgreSQL: a pool of connections, and the schema.
 *
 * Creating it connects, so a database that is not there stops the start instead of failing the first request. A
 * database that is only late (it restarts beside the server) is waited for, up to [startPatience].
 *
 * The server keeps its tables in a schema of its own ([SCHEMA]), so whatever a host puts into `public` is not in
 * the way. That schema changes only through the SQL migrations in `resources/db/migration`, which [migrate] applies
 * in order; a migration that has reached `main` is never edited, a new one is added.
 */
internal class Database(
    private val config: DatabaseConfig,
    startPatience: Duration = START_PATIENCE,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Database::class.java)

    private val pool =
        HikariDataSource(
            HikariConfig().apply {
                poolName = "database"
                jdbcUrl = config.jdbcUrl
                username = config.user
                password = config.password
                schema = SCHEMA
                // Small managed databases allow a few dozen connections in all, and a deploy runs two instances
                // side by side for a while. Connections beyond one are opened when needed and dropped when idle.
                maximumPoolSize = POOL_SIZE
                minimumIdle = 1
                // How long a request waits for a free connection before it fails.
                connectionTimeout = CONNECTION_TIMEOUT.inWholeMilliseconds
                initializationFailTimeout = startPatience.inWholeMilliseconds
            },
        )

    private val jdbi = Jdbi.create(pool).installPlugin(PostgresPlugin())

    /** How [isUp] connects: as the pool does, and giving up soon. */
    private val probe =
        Properties().apply {
            val seconds = PROBE_TIMEOUT.inWholeSeconds.toString()
            setProperty("user", config.user)
            setProperty("password", config.password)
            setProperty("connectTimeout", seconds)
            setProperty("loginTimeout", seconds)
            setProperty("socketTimeout", seconds)
        }

    /** Brings the schema to the version this build expects. Safe to call on a database that is already there. */
    fun migrate() {
        Flyway
            .configure()
            .dataSource(pool)
            .schemas(SCHEMA)
            .locations(MIGRATIONS)
            .load()
            .migrate()
    }

    /**
     * Runs [block] in one transaction, off the request threads. What it returns is the result; an exception rolls
     * everything back and goes on to the caller. Queries are SQL as written, through the [Handle] (ADR 0004).
     */
    suspend fun <T> transaction(block: (Handle) -> T): T = withContext(Dispatchers.IO) { jdbi.inTransaction<T, Exception> { block(it) } }

    /**
     * Whether the database answers right now. It asks over a connection of its own, not one from the pool: a pool
     * that is busy serving requests is not a database that is down.
     */
    suspend fun isUp(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                DriverManager.getConnection(config.jdbcUrl, probe).use { it.isValid(PROBE_TIMEOUT.inWholeSeconds.toInt()) }
            } catch (e: SQLException) {
                // One line per failed check: a host asks every few seconds for as long as the outage lasts.
                log.warn("The database does not answer: {}", e.message)
                false
            }
        }

    override fun close() = pool.close()

    companion object {
        /** The schema the server's tables live in. */
        const val SCHEMA = "pursuitofwind"
        private const val MIGRATIONS = "classpath:db/migration"
        private const val POOL_SIZE = 5
        private val CONNECTION_TIMEOUT = 5.seconds
        private val PROBE_TIMEOUT = 2.seconds
        private val START_PATIENCE = 30.seconds
    }
}
