package io.github.esseckers.pursuitofwind.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.SQLException
import kotlin.time.Duration.Companion.seconds

/**
 * The server's PostgreSQL: a pool of connections, and the schema.
 *
 * Creating it connects at once, so a database that is not there stops the start instead of failing the first
 * request. The schema changes only through the SQL migrations in `resources/db/migration`, which [migrate] applies
 * in order; a migration that has reached a deployed database is never edited, a new one is added.
 */
internal class Database(
    config: DatabaseConfig,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Database::class.java)

    private val pool =
        HikariDataSource(
            HikariConfig().apply {
                poolName = "database"
                jdbcUrl = config.jdbcUrl
                username = config.user
                password = config.password
                // How long a request waits for a free connection before it fails, and so how long /health waits
                // on a database that is gone.
                connectionTimeout = CONNECTION_TIMEOUT.inWholeMilliseconds
            },
        )

    /** Brings the schema to the version this build expects. Safe to call on a database that is already there. */
    fun migrate() {
        Flyway
            .configure()
            .dataSource(pool)
            .locations(MIGRATIONS)
            .load()
            .migrate()
    }

    /** Whether the database answers right now. */
    suspend fun isUp(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                pool.connection.use { it.isValid(PING_TIMEOUT.inWholeSeconds.toInt()) }
            } catch (e: SQLException) {
                log.warn("The database does not answer", e)
                false
            }
        }

    override fun close() = pool.close()

    private companion object {
        const val MIGRATIONS = "classpath:db/migration"
        val CONNECTION_TIMEOUT = 5.seconds
        val PING_TIMEOUT = 2.seconds
    }
}
