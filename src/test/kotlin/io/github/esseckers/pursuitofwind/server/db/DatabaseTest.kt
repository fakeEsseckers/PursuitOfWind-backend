package io.github.esseckers.pursuitofwind.server.db

import com.zaxxer.hikari.pool.HikariPool
import io.github.esseckers.pursuitofwind.server.testing.TestPostgres
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The pool and the migrations against a real PostgreSQL. */
class DatabaseTest {
    private fun tables(
        database: DatabaseConfig,
        schema: String,
    ): Set<String> =
        TestPostgres.strings(database, "select table_name from information_schema.tables where table_schema = '$schema'").toSet()

    @Test
    fun `an empty database gets the schema, and migrating again changes nothing`() =
        runTest {
            TestPostgres.withDatabase { config ->
                Database(config).use { database ->
                    database.migrate()
                    val migrated = tables(config, Database.SCHEMA)
                    database.migrate()

                    assertTrue("users" in migrated, migrated.toString())
                    assertEquals(migrated, tables(config, Database.SCHEMA))
                }
            }
        }

    @Test
    fun `the server's tables keep out of the way of what a host put into the database`() =
        runTest {
            TestPostgres.withDatabase { config ->
                TestPostgres.execute(config, "create table public.spatial_ref_sys (srid int primary key)")

                Database(config).use { it.migrate() }

                assertEquals(setOf("spatial_ref_sys"), tables(config, "public"))
                assertTrue("users" in tables(config, Database.SCHEMA))
            }
        }

    @Test
    fun `the database is up while it is there, and down once it is gone`() =
        runTest {
            TestPostgres.withDatabase { config ->
                Database(config).use { database ->
                    assertTrue(database.isUp())

                    TestPostgres.drop(config)

                    assertFalse(database.isUp())
                }
            }
        }

    @Test
    fun `a database that is not there fails when the pool is made`() =
        runTest {
            TestPostgres.withDatabase { config ->
                assertFailsWith<HikariPool.PoolInitializationException> {
                    Database(config.copy(name = "nowhere"), startPatience = 1.milliseconds)
                }
            }
        }
}
