package io.github.esseckers.pursuitofwind.server.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Open-Meteo for tests, at the HTTP layer: the server's own client and adapter run against it, and it answers in the
 * shape the real service answers in (the two requests were recorded on 2026-11-23). What it forecasts is for a test
 * to say; unless told otherwise it has every hour from midnight before [clock]'s time for [days] days, three models
 * that agree, no rain, and the sun up from 04:00 to 20:00 UTC.
 */
internal class FakeOpenMeteo(
    private val clock: TestClock,
) {
    /** Requests received, the two of one forecast counted apart. */
    var requests = 0
        private set

    /** The places forecasts were asked for, as `latitude,longitude`. */
    val places = mutableListOf<String>()

    /** When set, every request gets this status in place of a forecast. */
    var failsWith: HttpStatusCode? = null

    /** When set, every request gets this text in place of a forecast. */
    var answersWith: String? = null

    /** When set, the two requests of a forecast get these answers, the wind's first: what the real service once said. */
    var recorded: Pair<String, String>? = null
    var days = 11

    /** One model's wind for the hour that starts [hour] hours after the first: mean, gusts, bearing; null for none. */
    var wind: (model: String, hour: Int) -> Triple<Double, Double, Double>? = { _, hour -> Triple(8.0 + hour % 3, 11.0 + hour % 3, 240.0) }
    var precipitationMm: (hour: Int) -> Double? = { 0.0 }
    var temperatureC: (hour: Int) -> Double? = { 18.5 }
    var weatherCode: (hour: Int) -> Int? = { 1 }
    var capeJPerKg: (hour: Int) -> Double? = { 120.0 }

    /** The first hour this source has: midnight UTC of the day before the clock's time. */
    val firstHour: Instant get() = Instant.fromEpochSeconds(clock.now.epochSeconds / DAY * DAY) - 1.days

    val client = HttpClient(MockEngine { request -> answer(request) })

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.answer(request: HttpRequestData) =
        run {
            requests++
            val parameters = request.url.parameters
            places += "${parameters["latitude"]},${parameters["longitude"]}"
            val failure = failsWith
            if (failure != null) {
                respondError(failure)
            } else {
                respond(answersWith ?: forecast("models" in parameters, parameters["models"].orEmpty()), headers = JSON)
            }
        }

    private fun forecast(
        wind: Boolean,
        models: String,
    ): String = recorded?.let { if (wind) it.first else it.second } ?: if (wind) windJson(models) else weatherJson()

    private fun hours(): List<Int> = (0 until days * 24).toList()

    private fun times(): String = hours().joinToString(",") { (firstHour + it.hours).epochSeconds.toString() }

    private fun windJson(models: String): String {
        val series =
            models.split(',').flatMap { theirs ->
                val ours = theirs.substringBefore('_')
                listOf(
                    "\"wind_speed_10m_$theirs\":[${hours().joinToString(",") { wind(ours, it)?.first.toString() }}]",
                    "\"wind_gusts_10m_$theirs\":[${hours().joinToString(",") { wind(ours, it)?.second.toString() }}]",
                    "\"wind_direction_10m_$theirs\":[${hours().joinToString(",") { wind(ours, it)?.third.toString() }}]",
                )
            }
        return """{"latitude":54.5,"longitude":9.0,"utc_offset_seconds":0,"timezone":"GMT","hourly":{"time":[${times()}],${series.joinToString(
            ",",
        )}}}"""
    }

    private fun weatherJson(): String {
        val dayStarts = (0 until days).map { firstHour + it.days }
        return """{"latitude":54.475243,"longitude":8.819662,"utc_offset_seconds":0,"timezone":"GMT","hourly":{"time":[${times()}],""" +
            """"precipitation":[${hours().joinToString(",") { precipitationMm(it).toString() }}],""" +
            """"temperature_2m":[${hours().joinToString(",") { temperatureC(it).toString() }}],""" +
            """"weather_code":[${hours().joinToString(",") { weatherCode(it).toString() }}],""" +
            """"cape":[${hours().joinToString(",") { capeJPerKg(it).toString() }}]},""" +
            """"daily":{"time":[${dayStarts.joinToString(",") { it.epochSeconds.toString() }}],""" +
            """"sunrise":[${dayStarts.joinToString(",") { (it + SUNRISE_HOUR.hours).epochSeconds.toString() }}],""" +
            """"sunset":[${dayStarts.joinToString(",") { (it + SUNSET_HOUR.hours).epochSeconds.toString() }}]}}"""
    }

    companion object {
        const val SUNRISE_HOUR = 4
        const val SUNSET_HOUR = 20
        private const val DAY = 86_400L
        private val JSON = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
