package io.github.esseckers.pursuitofwind.server.testing

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ProblemDto
import io.github.esseckers.pursuitofwind.server.api.Api
import io.github.esseckers.pursuitofwind.server.db.Database
import io.github.esseckers.pursuitofwind.server.db.DatabaseConfig
import io.github.esseckers.pursuitofwind.server.forecast.Forecasts
import io.github.esseckers.pursuitofwind.server.forecast.OpenMeteo
import io.github.esseckers.pursuitofwind.server.module
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.serialization.DeserializationStrategy
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The whole server under test: real HTTP handling in memory, on a real database of its own with this build's schema.
 *
 * @property client calls the server as the app would.
 * @property database where the server's database is, for the tests that are about the database itself.
 * @property clock the server's time; it stands still until a test moves it.
 * @property weather the source of forecasts the server asks, which forecasts what a test tells it to.
 */
internal class TestServer(
    val client: HttpClient,
    val database: DatabaseConfig,
    val clock: TestClock,
    val weather: FakeOpenMeteo,
)

/** A clock that shows what a test sets it to. It starts on a Thursday morning in July 2026. */
internal class TestClock(
    var now: Instant = Instant.parse("2026-07-16T06:00:00Z"),
) : Clock {
    override fun now(): Instant = now
}

/**
 * Runs [test] against the whole server. [extra] adds what a test needs beside the server's own routes, and is given
 * what the server's handlers share.
 */
internal fun serverTest(
    extra: Application.(Api) -> Unit = {},
    test: suspend TestServer.() -> Unit,
) = testApplication {
    val clock = TestClock()
    TestPostgres.withDatabase { config ->
        Database(config).use { database ->
            database.migrate()
            val weather = FakeOpenMeteo(clock)
            application {
                val api = Api(database, clock, Forecasts(database, OpenMeteo(weather.client), server = this))
                module(api)
                extra(api)
            }
            TestServer(client, config, clock, weather).test()
        }
    }
}

/** The problem a failed call answered with; fails unless it came as `application/problem+json`. */
internal suspend fun HttpResponse.problem(): ProblemDto {
    assertEquals(ContentType.parse(ProblemDto.MEDIA_TYPE), contentType()?.withoutParameters())
    return ContractJson.decodeFromString(ProblemDto.serializer(), bodyAsText())
}

/** The body of a successful call as the contract's type; fails unless it came as JSON. */
internal suspend fun <T> HttpResponse.json(type: DeserializationStrategy<T>): T {
    assertEquals(ContentType.Application.Json, contentType()?.withoutParameters())
    return ContractJson.decodeFromString(type, bodyAsText())
}
