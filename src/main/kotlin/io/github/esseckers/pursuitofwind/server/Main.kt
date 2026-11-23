package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.server.db.Database
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Starts the server with the configuration from the environment, and stays up until the process is stopped. */
fun main() {
    serve(ServerConfig.from(System.getenv()))
}

/**
 * Connects to the database, brings its schema to this build's version, then serves until the process is stopped.
 * A database that is not there, or a migration that fails, ends the process before it takes a request.
 */
internal fun serve(config: ServerConfig) {
    val database = Database(config.database)
    database.migrate()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        monitor.subscribe(ApplicationStopped) { database.close() }
        module(database)
    }.start(wait = true)
}
