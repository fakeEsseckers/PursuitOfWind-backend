package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.server.db.Database
import io.github.esseckers.pursuitofwind.server.testing.TestPostgres
import kotlinx.coroutines.test.runTest
import org.flywaydb.core.api.FlywayException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The server as the process starts it: a real port, a real database. */
class ServerStartTest {
    @Test
    fun `the server brings a new database to its schema, answers, and lets go of the database when it stops`() =
        runTest {
            TestPostgres.withDatabase { database ->
                val server = server(ServerConfig(port = 0, database = database)).start(wait = false)
                try {
                    val port =
                        server.engine
                            .resolvedConnectors()
                            .first()
                            .port
                    val request = HttpRequest.newBuilder(URI("http://localhost:$port/health")).build()
                    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

                    assertEquals(200, response.statusCode())
                    assertTrue(TestPostgres.strings(database, "select count(*) from ${Database.SCHEMA}.users").single() == "0")
                } finally {
                    server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                }

                assertEquals(0, TestPostgres.connections(database))
            }
        }

    @Test
    fun `a schema the migrations cannot build on stops the start and leaves no connection behind`() =
        runTest {
            TestPostgres.withDatabase { database ->
                TestPostgres.execute(database, "create schema ${Database.SCHEMA}; create table ${Database.SCHEMA}.users (id int)")

                assertFailsWith<FlywayException> { server(ServerConfig(port = 0, database = database)) }

                assertEquals(0, TestPostgres.connections(database))
            }
        }
}
