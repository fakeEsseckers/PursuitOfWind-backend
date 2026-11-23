package io.github.esseckers.pursuitofwind.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerConfigTest {
    @Test
    fun `without a port the server listens on 8080`() {
        assertEquals(ServerConfig(port = 8080), ServerConfig.from(emptyMap()))
    }

    @Test
    fun `the port comes from the environment`() {
        assertEquals(ServerConfig(port = 9090), ServerConfig.from(mapOf("PORT" to "9090")))
    }

    @Test
    fun `a port that is no port stops the start and names the variable`() {
        listOf("http", "", "0", "70000", "-1").forEach { value ->
            val failure = assertFailsWith<IllegalArgumentException>(value) { ServerConfig.from(mapOf("PORT" to value)) }

            assertTrue("PORT" in failure.message.orEmpty(), failure.message)
        }
    }
}
