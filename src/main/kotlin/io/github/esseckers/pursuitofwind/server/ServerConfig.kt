package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.server.db.DatabaseConfig

/**
 * What the server is told from outside, all of it through environment variables: nothing about a deployment is in the
 * repository or the image. A value that is missing or wrong stops the start with a message that names the variable,
 * so a mistake shows at deploy time and not on the first request.
 *
 * @property port the port to listen on (`PORT`, 8080 when not set).
 * @property database where the database is (`DATABASE_URL`, required).
 */
internal data class ServerConfig(
    val port: Int,
    val database: DatabaseConfig,
) {
    companion object {
        const val DEFAULT_PORT = 8080
        private val PORTS = 1..65_535

        /** The configuration in [environment], as `System.getenv()` gives it. */
        fun from(environment: Map<String, String>): ServerConfig {
            val port = environment["PORT"]?.let { it.toIntOrNull() ?: invalid("PORT", it, "a number") } ?: DEFAULT_PORT
            if (port !in PORTS) invalid("PORT", port.toString(), "between ${PORTS.first} and ${PORTS.last}")
            val database =
                requireNotNull(environment[DatabaseConfig.VARIABLE]) { "The environment variable ${DatabaseConfig.VARIABLE} is not set" }
            return ServerConfig(port, DatabaseConfig.parse(database))
        }

        private fun invalid(
            name: String,
            value: String,
            expected: String,
        ): Nothing = throw IllegalArgumentException("The environment variable $name must be $expected, was '$value'")
    }
}
