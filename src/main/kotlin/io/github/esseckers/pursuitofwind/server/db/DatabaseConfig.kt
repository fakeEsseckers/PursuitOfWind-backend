package io.github.esseckers.pursuitofwind.server.db

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where the database is and who the server is to it. A host hands this out as one URL in `DATABASE_URL`:
 * `postgresql://user:password@host:5432/name`, with optional connection parameters after `?`.
 *
 * The password stays out of messages and logs: [toString] leaves it out, and a URL that cannot be read is reported
 * by what is wrong with it, never by its text.
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
        get() =
            "jdbc:postgresql://$host:$port/${URLEncoder.encode(name, Charsets.UTF_8)}" +
                if (parameters.isEmpty()) "" else "?$parameters"

    override fun toString(): String = "DatabaseConfig(host=$host, port=$port, name=$name, user=$user)"

    companion object {
        const val VARIABLE = "DATABASE_URL"
        private const val FORM = "postgresql://user:password@host:5432/name"
        private const val DEFAULT_PORT = 5432
        private val PORTS = 1..65_535
        private val SCHEMES = setOf("postgresql", "postgres")

        /**
         * Reads [url] in the form above. The port (5432) and the password may be left out; characters a URL has to
         * escape come back as themselves. The host is taken as written, so names a strict URL parser refuses
         * (`pow_db`, as container networks name services) are fine.
         */
        fun parse(url: String): DatabaseConfig {
            val uri = uriOrNull(url) ?: unreadable("it is not a URL")
            if (uri.scheme !in SCHEMES) unreadable("the scheme must be postgresql")
            val authority = uri.rawAuthority.orEmpty()
            val credentials = authority.substringBeforeLast('@', missingDelimiterValue = "").split(':', limit = 2)
            val address = authority.substringAfterLast('@')
            val host = if (address.startsWith("[")) address.substringBefore(']') + "]" else address.substringBefore(':')
            val name = uri.rawPath.orEmpty().removePrefix("/")
            return DatabaseConfig(
                host = host.takeUnless { it.isEmpty() || it == "[]" } ?: unreadable("the host is missing"),
                port = port(address.removePrefix(host).removePrefix(":")),
                name = decoded(name.takeUnless { it.isEmpty() || '/' in it } ?: unreadable("the database name is missing")),
                user = decoded(credentials[0].ifEmpty { unreadable("the user is missing") }),
                password = decoded(credentials.getOrNull(1).orEmpty()),
                parameters = uri.rawQuery.orEmpty(),
            )
        }

        private fun port(text: String): Int =
            if (text.isEmpty()) {
                DEFAULT_PORT
            } else {
                text.toIntOrNull()?.takeIf { it in PORTS } ?: unreadable("the port must be one number from ${PORTS.first} to ${PORTS.last}")
            }

        /** A part of the URL with its percent-escapes resolved; a plus sign is a plus sign there. */
        private fun decoded(part: String): String = URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8)

        private fun unreadable(what: String): Nothing =
            throw IllegalArgumentException("The environment variable $VARIABLE must look like $FORM: $what")

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
