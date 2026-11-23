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
    fun `characters a url has to escape come back as themselves, and go to the driver escaped`() {
        val config = DatabaseConfig.parse("postgresql://ri%3Ader:p%40ss%3Aw%2Frd+1@db.internal/wind%2Bone%3F")

        assertEquals("ri:der", config.user)
        assertEquals("p@ss:w/rd+1", config.password)
        assertEquals("wind+one?", config.name)
        assertEquals("jdbc:postgresql://db.internal:5432/wind%2Bone%3F", config.jdbcUrl)
    }

    @Test
    fun `the host is taken as written`() {
        assertEquals("pow_db", DatabaseConfig.parse("postgresql://rider:secret@pow_db:5432/wind").host)
        assertEquals("jdbc:postgresql://[::1]:6543/wind", DatabaseConfig.parse("postgresql://rider:secret@[::1]:6543/wind").jdbcUrl)
        assertEquals("jdbc:postgresql://[::1]:5432/wind", DatabaseConfig.parse("postgresql://rider@[::1]/wind").jdbcUrl)
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
                "postgresql://rider:secret@db.internal:0/wind",
                "postgresql://rider:secret@db.internal:99999/wind",
                "postgresql://rider:secret@db.internal:port/wind",
                "postgresql://rider:secret@one.internal:5432,two.internal:5432/wind",
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
