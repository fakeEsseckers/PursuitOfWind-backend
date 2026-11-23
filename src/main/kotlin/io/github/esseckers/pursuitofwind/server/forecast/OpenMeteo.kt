package io.github.esseckers.pursuitofwind.server.forecast

import io.github.esseckers.pursuitofwind.contract.ForecastHourDto
import io.github.esseckers.pursuitofwind.contract.ModelWindDto
import io.github.esseckers.pursuitofwind.contract.ThunderRiskDto
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Where forecasts come from: every hour a source has for a place, as the contract's hours. */
internal fun interface ForecastSource {
    /** The hours the source has for [place] now, oldest first and at least one. Fails with [ForecastSourceException]. */
    suspend fun hours(place: Place): List<ForecastHourDto>
}

/** The source did not answer, or answered with something that is not a forecast. */
internal class ForecastSourceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Open-Meteo (open-meteo.com) as the source of forecasts: the wind of three weather models, and the weather and sun
 * times of its best match for the place. Its data is licensed CC BY 4.0, so whatever shows a forecast names it.
 *
 * One forecast is two requests, made together and both in UTC, so that their hours are the same hours:
 * - the wind per model. A model that has no value for an hour, as near the end of its range, is left out of that
 *   hour; an hour no model covers is left out altogether.
 * - precipitation, temperature, the weather code and the convective energy (CAPE) of each hour, and the sun times
 *   of each day.
 *
 * The source stamps what happened over an hour, its rain and its strongest gust, at the hour's end. The contract's
 * hour is named by its start, so those two are read from the stamp one hour later.
 *
 * What the contract asks for and the source does not give is worked out here:
 * - `thunderRisk` is HIGH when the source itself forecasts a thunderstorm for the hour (WMO weather codes 95 to 99),
 *   MEDIUM when the air is unstable enough for one ([UNSTABLE_CAPE_J_PER_KG]), LOW otherwise. HIGH stops a session in
 *   the decision engine, so it is kept for what the model says will happen, not for what could.
 * - `isDaylight` follows the contract: the hour starts at or after sunrise and ends no later than half an hour
 *   before sunset. The source gives a day without a night as sunrise at its start and sunset at its end, so days
 *   that touch are one stretch of light; a day without a sun as sunrise and sunset at the same moment.
 */
internal class OpenMeteo(
    private val client: HttpClient,
    private val baseUrl: String = "https://api.open-meteo.com",
) : ForecastSource {
    // Whatever goes wrong between here and a foreign service, a refused connection or an answer in another shape,
    // is the same thing to the caller: no forecast from the source this time.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun hours(place: Place): List<ForecastHourDto> =
        try {
            coroutineScope {
                val wind =
                    async {
                        fetch(
                            place,
                            "wind_speed_10m,wind_gusts_10m,wind_direction_10m",
                        ) { parameter("models", MODELS.values.joinToString(",")) }
                    }
                val weather =
                    async { fetch(place, "precipitation,temperature_2m,weather_code,cape") { parameter("daily", "sunrise,sunset") } }
                hours(
                    wind.await(),
                    weather.await(),
                ).ifEmpty { throw ForecastSourceException("Open-Meteo has no hour with wind and weather for $place") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForecastSourceException) {
            throw e
        } catch (e: Exception) {
            throw ForecastSourceException("Open-Meteo gave no forecast: ${e.message ?: e::class.simpleName}", e)
        }

    private suspend fun fetch(
        place: Place,
        hourly: String,
        more: HttpRequestBuilder.() -> Unit,
    ): JsonObject {
        val response =
            client.get("$baseUrl/v1/forecast") {
                parameter("latitude", place.lat)
                parameter("longitude", place.lon)
                parameter("hourly", hourly)
                parameter("wind_speed_unit", "ms")
                parameter("timeformat", "unixtime")
                parameter("past_days", PAST_DAYS)
                parameter("forecast_days", FORECAST_DAYS)
                more()
            }
        if (!response.status.isSuccess()) throw ForecastSourceException("Open-Meteo answered ${response.status.value}")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun hours(
        wind: JsonObject,
        weather: JsonObject,
    ): List<ForecastHourDto> {
        val windByHour = wind.series("hourly")
        val weatherByHour = weather.series("hourly")
        val light = light(weather.series("daily"))
        return windByHour.times.mapNotNull { start ->
            val models = MODELS.mapNotNull { (model, theirs) -> windByHour.wind(model, theirs, start) }
            val conditions = weatherByHour.conditions(start)
            if (models.isEmpty() || conditions == null) {
                null
            } else {
                ForecastHourDto(
                    start = Instant.fromEpochSeconds(start),
                    models = models,
                    precipitationMm = conditions.precipitationMm,
                    temperatureC = conditions.temperatureC,
                    thunderRisk = conditions.thunderRisk,
                    isDaylight = light.any { (rise, set) -> start >= rise && start + HOUR_SECONDS <= set - DUSK_SECONDS },
                )
            }
        }
    }

    /** The stretches of daylight in the answer, from a sunrise to a sunset; days whose light touches are one stretch. */
    private fun light(days: Series): List<Pair<Long, Long>> {
        val perDay =
            days.times.mapNotNull { day ->
                days.long("sunrise", day)?.let { rise -> days.long("sunset", day)?.let { rise to it } }
            }
        return perDay.sortedBy { it.first }.fold(emptyList()) { stretches, (rise, set) ->
            val last = stretches.lastOrNull()
            if (last != null &&
                rise <= last.second
            ) {
                stretches.dropLast(1) + (last.first to maxOf(last.second, set))
            } else {
                stretches + (rise to set)
            }
        }
    }

    /** What the hour that starts at [start] is like beside its wind, or null if the source lacks rain or temperature for it. */
    private fun Series.conditions(start: Long): Conditions? {
        val precipitation = double("precipitation", start + HOUR_SECONDS) ?: double("precipitation", start)
        val temperature = double("temperature_2m", start)
        return if (precipitation == null || temperature == null) {
            null
        } else {
            Conditions(precipitation.coerceAtLeast(0.0), temperature, thunderRisk(long("weather_code", start), double("cape", start)))
        }
    }

    /** One model's wind for the hour that starts at [start], or null if the model has no complete value for it. */
    private fun Series.wind(
        model: String,
        theirs: String,
        start: Long,
    ): ModelWindDto? {
        val mean = double("wind_speed_10m_$theirs", start)
        val gust = double("wind_gusts_10m_$theirs", start + HOUR_SECONDS) ?: double("wind_gusts_10m_$theirs", start)
        val from = double("wind_direction_10m_$theirs", start)
        return if (mean == null || gust == null || from == null) {
            null
        } else {
            // The source writes north as 360 as well as 0; the contract knows 0 only.
            ModelWindDto(model, mean.coerceAtLeast(0.0), gust.coerceAtLeast(0.0), ((from % FULL_TURN) + FULL_TURN) % FULL_TURN)
        }
    }

    private fun thunderRisk(
        weatherCode: Long?,
        capeJPerKg: Double?,
    ): ThunderRiskDto =
        when {
            weatherCode != null && weatherCode in THUNDERSTORM_CODES -> ThunderRiskDto.HIGH
            capeJPerKg != null && capeJPerKg >= UNSTABLE_CAPE_J_PER_KG -> ThunderRiskDto.MEDIUM
            else -> ThunderRiskDto.LOW
        }

    private class Conditions(
        val precipitationMm: Double,
        val temperatureC: Double,
        val thunderRisk: ThunderRiskDto,
    )

    /** A block of the answer (`hourly`, `daily`): its times and, under each name, one value per time. */
    private class Series(
        val times: List<Long>,
        private val values: JsonObject,
    ) {
        private val indexOf = times.indices.associateBy { times[it] }

        /** The number under [name] at the time [at], or null if there is none or it is not a finite number. */
        fun double(
            name: String,
            at: Long,
        ): Double? = primitive(name, at)?.doubleOrNull?.takeIf { it.isFinite() }

        fun long(
            name: String,
            at: Long,
        ): Long? = primitive(name, at)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

        private fun primitive(
            name: String,
            at: Long,
        ): JsonPrimitive? =
            indexOf[at]?.let { index ->
                ((values[name] as? JsonArray)?.getOrNull(index) as? JsonPrimitive)?.takeUnless { it is JsonNull }
            }
    }

    private fun JsonObject.series(name: String): Series {
        val block = requireNotNull(this[name] as? JsonObject) { "no $name in the answer" }
        val times = requireNotNull(block["time"] as? JsonArray) { "no time in $name" }
        return Series(times.map { requireNotNull((it as? JsonPrimitive)?.longOrNull) { "a time in $name is not a number" } }, block)
    }

    companion object {
        /** A day back, so that a call for hours that have just passed still finds them. */
        private const val PAST_DAYS = 1
        private const val FORECAST_DAYS = 10

        /** The contract's names of the models, and the source's. */
        private val MODELS = linkedMapOf("ecmwf" to "ecmwf_ifs025", "icon" to "icon_seamless", "gfs" to "gfs_seamless")

        /** WMO weather codes 95, 96 and 99: a thunderstorm, slight or with hail. */
        private val THUNDERSTORM_CODES = 95L..99L

        /** Convective available potential energy from which forecasters call the air moderately unstable. */
        const val UNSTABLE_CAPE_J_PER_KG = 1000.0
        private const val FULL_TURN = 360.0
        private val HOUR_SECONDS = 1.hours.inWholeSeconds
        private val DUSK_SECONDS = 30.minutes.inWholeSeconds
    }
}
