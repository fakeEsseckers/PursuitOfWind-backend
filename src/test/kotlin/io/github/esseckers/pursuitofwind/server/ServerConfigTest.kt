package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.server.db.DatabaseConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerConfigTest {
    private val database = "DATABASE_URL" to "postgresql://rider:secret@db.internal:5432/wind"

    @Test
    fun `without a port the server listens on 8080`() {
        assertEquals(8080, ServerConfig.from(mapOf(database)).port)
    }

    @Test
    fun `the port and the database come from the environment`() {
        assertEquals(
            ServerConfig(port = 9090, database = DatabaseConfig("db.internal", 5432, "wind", "rider", "secret")),
            ServerConfig.from(mapOf("PORT" to "9090", database)),
        )
    }

    @Test
    fun `a port that is no port stops the start and names the variable`() {
        listOf("http", "", "0", "70000", "-1").forEach { value ->
            val failure = assertFailsWith<IllegalArgumentException>(value) { ServerConfig.from(mapOf("PORT" to value, database)) }

            assertTrue("PORT" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `without a database the start stops and names the variable`() {
        val failure = assertFailsWith<IllegalArgumentException> { ServerConfig.from(emptyMap()) }

        assertTrue("DATABASE_URL" in failure.message.orEmpty(), failure.message)
    }
}
