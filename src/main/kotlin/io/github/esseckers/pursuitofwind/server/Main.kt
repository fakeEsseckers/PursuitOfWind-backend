package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.server.db.Database
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import org.flywaydb.core.api.FlywayException

/** Starts the server with the configuration from the environment, and stays up until the process is stopped. */
fun main() {
    server(ServerConfig.from(System.getenv())).start(wait = true)
}

/**
 * The server, ready to start: connected to its database, with the schema at this build's version. A database that
 * is not there, or a migration that fails, fails here, before a request is taken. Stopping the server lets go of
 * the database.
 */
internal fun server(config: ServerConfig): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val database = Database(config.database)
    try {
        database.migrate()
    } catch (e: FlywayException) {
        database.close()
        throw e
    }
    return embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        monitor.subscribe(ApplicationStopped) { database.close() }
        module(database)
    }
}
