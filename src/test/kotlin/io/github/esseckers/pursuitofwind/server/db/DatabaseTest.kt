package io.github.esseckers.pursuitofwind.server.db

import io.github.esseckers.pursuitofwind.server.testing.TestPostgres
import kotlinx.coroutines.test.runTest
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** The pool and the migrations against a real PostgreSQL. */
class DatabaseTest {
    private fun tables(config: DatabaseConfig): Set<String> =
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { statement ->
                val rows = statement.executeQuery("select table_name from information_schema.tables where table_schema = 'public'")
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }

    @Test
    fun `an empty database gets the schema, and migrating again changes nothing`() =
        runTest {
            TestPostgres.withDatabase { config ->
                assertEquals(emptySet(), tables(config))

                Database(config).use { database ->
                    database.migrate()
                    val migrated = tables(config)
                    database.migrate()

                    assertTrue("users" in migrated, migrated.toString())
                    assertEquals(migrated, tables(config))
                    assertTrue(database.isUp())
                }
            }
        }

    @Test
    fun `a database that is not there stops the start`() =
        runTest {
            TestPostgres.withDatabase { config ->
                assertFails { Database(config.copy(name = "nowhere")) }
            }
        }
}
