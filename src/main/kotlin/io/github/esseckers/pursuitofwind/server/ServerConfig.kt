package io.github.esseckers.pursuitofwind.server

/**
 * What the server is told from outside, all of it through environment variables: nothing about a deployment is in the
 * repository or the image. A value that is present and wrong stops the start with a message that names the variable,
 * so a mistake shows at deploy time and not on the first request.
 *
 * @property port the port to listen on (`PORT`, 8080 when not set).
 */
internal data class ServerConfig(
    val port: Int,
) {
    companion object {
        const val DEFAULT_PORT = 8080
        private val PORTS = 1..65_535

        /** The configuration in [environment], as `System.getenv()` gives it. */
        fun from(environment: Map<String, String>): ServerConfig {
            val port = environment["PORT"]?.let { it.toIntOrNull() ?: invalid("PORT", it, "a number") } ?: DEFAULT_PORT
            if (port !in PORTS) invalid("PORT", port.toString(), "between ${PORTS.first} and ${PORTS.last}")
            return ServerConfig(port)
        }

        private fun invalid(
            name: String,
            value: String,
            expected: String,
        ): Nothing = throw IllegalArgumentException("The environment variable $name must be $expected, was '$value'")
    }
}
