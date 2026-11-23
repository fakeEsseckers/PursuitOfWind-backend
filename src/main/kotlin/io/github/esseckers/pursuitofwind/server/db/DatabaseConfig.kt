package io.github.esseckers.pursuitofwind.server.db

import java.net.URI
import java.net.URISyntaxException

/**
 * Where the database is and who the server is to it. A host hands this out as one URL in `DATABASE_URL`:
 * `postgresql://user:password@host:5432/name`, with optional connection parameters after `?`.
 *
 * The password stays out of messages and logs: [toString] leaves it out, and a URL that cannot be read is reported
 * without its text.
 *
 * @property parameters what followed `?` in the URL, passed to the driver as it came (`sslmode=require`).
 */
internal data class DatabaseConfig(
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String,
    val parameters: String = "",
) {
    /** The same place as the driver wants it; the user and the password travel beside it. */
    val jdbcUrl: String
        get() = "jdbc:postgresql://$host:$port/$name" + if (parameters.isEmpty()) "" else "?$parameters"

    override fun toString(): String = "DatabaseConfig(host=$host, port=$port, name=$name, user=$user)"

    companion object {
        const val VARIABLE = "DATABASE_URL"
        private const val FORM = "postgresql://user:password@host:5432/name"
        private const val DEFAULT_PORT = 5432
        private val SCHEMES = setOf("postgresql", "postgres")

        /** Reads [url] in the form above. The port (5432) and the password may be left out. */
        fun parse(url: String): DatabaseConfig {
            val uri = uriOrNull(url)
            val name = uri?.path?.removePrefix("/").orEmpty()
            val credentials = uri?.userInfo?.split(':', limit = 2).orEmpty()
            val user = credentials.firstOrNull().orEmpty()
            val readable = uri?.scheme in SCHEMES && !uri?.host.isNullOrEmpty() && name.isNotEmpty() && '/' !in name
            require(uri != null && readable && user.isNotEmpty()) { "The environment variable $VARIABLE must look like $FORM" }
            return DatabaseConfig(
                host = uri.host,
                port = if (uri.port == -1) DEFAULT_PORT else uri.port,
                name = name,
                user = user,
                password = credentials.getOrNull(1).orEmpty(),
                parameters = uri.rawQuery.orEmpty(),
            )
        }

        // The exception quotes the whole URL, password included, so it goes no further than this.
        @Suppress("SwallowedException")
        private fun uriOrNull(url: String): URI? =
            try {
                URI(url.trim())
            } catch (e: URISyntaxException) {
                null
            }
    }
}
