package io.github.esseckers.pursuitofwind.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Starts the server with the configuration from the environment, and stays up until the process is stopped. */
fun main() {
    val config = ServerConfig.from(System.getenv())
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module() }.start(wait = true)
}
