package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.server.api.Api
import io.github.esseckers.pursuitofwind.server.db.Database
import io.github.esseckers.pursuitofwind.server.forecast.Forecasts
import io.github.esseckers.pursuitofwind.server.forecast.OpenMeteo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import org.flywaydb.core.api.FlywayException
import kotlin.time.Clock

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
        // The weather comes from Open-Meteo, over a client that gives up on a request that takes too long.
        val weather = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = FORECAST_TIMEOUT_MILLIS } }
        monitor.subscribe(ApplicationStopped) {
            weather.close()
            database.close()
        }
        module(Api(database, Clock.System, Forecasts(database, OpenMeteo(weather), server = this)))
    }
}

private const val FORECAST_TIMEOUT_MILLIS = 10_000L
