package io.github.esseckers.pursuitofwind.server.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseConfigTest {
    @Test
    fun `the url a host hands out is read part by part`() {
        val config = DatabaseConfig.parse("postgresql://rider:secret@db.internal:6543/wind")

        assertEquals(DatabaseConfig(host = "db.internal", port = 6543, name = "wind", user = "rider", password = "secret"), config)
        assertEquals("jdbc:postgresql://db.internal:6543/wind", config.jdbcUrl)
    }

    @Test
    fun `the short scheme, a missing port and a missing password are fine`() {
        assertEquals(
            DatabaseConfig(host = "localhost", port = 5432, name = "wind", user = "rider", password = ""),
            DatabaseConfig.parse("postgres://rider@localhost/wind"),
        )
    }

    @Test
    fun `connection parameters reach the driver as they came`() {
        val config = DatabaseConfig.parse("postgresql://rider:secret@db.internal/wind?sslmode=require&connectTimeout=5")

        assertEquals("jdbc:postgresql://db.internal:5432/wind?sslmode=require&connectTimeout=5", config.jdbcUrl)
    }

    @Test
    fun `a password keeps the characters a url has to escape`() {
        assertEquals("p@ss:w/rd", DatabaseConfig.parse("postgresql://rider:p%40ss%3Aw%2Frd@db.internal/wind").password)
    }

    @Test
    fun `a url that cannot be read names the variable and keeps the password to itself`() {
        val unreadable =
            listOf(
                "",
                "db.internal",
                "mysql://rider:secret@db.internal/wind",
                "postgresql://rider:secret@/wind",
                "postgresql://rider:secret@db.internal",
                "postgresql://rider:secret@db.internal/",
                "postgresql://:secret@db.internal/wind",
                "postgresql://rider:secret@db.internal/wind/extra",
                "postgresql://rider:sec ret@db.internal/wind",
            )

        for (url in unreadable) {
            val failure = assertFailsWith<IllegalArgumentException>(url) { DatabaseConfig.parse(url) }

            assertTrue("DATABASE_URL" in failure.message.orEmpty(), failure.message)
            assertFalse("secret" in failure.toString() || "sec ret" in failure.toString(), failure.toString())
        }
    }

    @Test
    fun `printing the configuration leaves the password out`() {
        assertFalse("secret" in DatabaseConfig.parse("postgresql://rider:secret@db.internal/wind").toString())
    }
}
