package io.github.esseckers.pursuitofwind.server.forecast

import io.github.esseckers.pursuitofwind.contract.ForecastHourDto
import io.github.esseckers.pursuitofwind.contract.ModelWindDto
import io.github.esseckers.pursuitofwind.contract.SpotDto
import io.github.esseckers.pursuitofwind.contract.ThunderRiskDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Where forecasts come from: every hour a source has for a spot, as the contract's hours. */
internal fun interface ForecastSource {
    /** The hours the source has for [spot]'s place now, oldest first. Fails with [ForecastSourceException]. */
    suspend fun hours(spot: SpotDto): List<ForecastHourDto>
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
 * One forecast is two requests, made together:
 * - the wind per model. A model that has no value for an hour, as near the end of its range, is left out of that
 *   hour; an hour no model covers is left out altogether.
 * - precipitation, temperature, the weather code and the convective energy (CAPE) of the hour, and the sun times of
 *   each day in the spot's own time zone.
 *
 * What the contract asks for and the source does not give is worked out here:
 * - `thunderRisk` is HIGH when the source itself forecasts a thunderstorm for the hour (WMO weather codes 95 to 99),
 *   MEDIUM when the air is unstable enough for one ([UNSTABLE_CAPE_J_PER_KG]), LOW otherwise. HIGH stops a session in
 *   the decision engine, so it is kept for what the model says will happen, not for what could.
 * - `isDaylight` follows the contract: the hour starts at or after sunrise and ends no later than half an hour
 *   before sunset.
 */
internal class OpenMeteo(
    private val client: HttpClient,
    private val baseUrl: String = "https://api.open-meteo.com",
) : ForecastSource {
    override suspend fun hours(spot: SpotDto): List<ForecastHourDto> =
        try {
            coroutineScope {
                val wind = async { fetch(spot) { parameter("models", MODELS.values.joinToString(",")) } }
                val weather =
                    async {
                        fetch(spot, hourly = "precipitation,temperature_2m,weather_code,cape") {
                            parameter("daily", "sunrise,sunset")
                            parameter("timezone", spot.timeZone)
                        }
                    }
                hours(wind.await(), weather.await())
            }
        } catch (e: IOException) {
            throw ForecastSourceException("Open-Meteo could not be reached", e)
        } catch (e: HttpRequestTimeoutException) {
            throw ForecastSourceException("Open-Meteo did not answer in time", e)
        } catch (e: SerializationException) {
            throw ForecastSourceException("Open-Meteo answered with something that is not JSON", e)
        } catch (e: IllegalArgumentException) {
            throw ForecastSourceException("Open-Meteo answered with something that is not a forecast", e)
        }

    private suspend fun fetch(
        spot: SpotDto,
        hourly: String = "wind_speed_10m,wind_gusts_10m,wind_direction_10m",
        more: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): JsonObject {
        val response =
            client.get("$baseUrl/v1/forecast") {
                parameter("latitude", spot.location.lat)
                parameter("longitude", spot.location.lon)
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
        val weatherIndex = weatherByHour.times.indices.associateBy { weatherByHour.times[it] }
        val days = weather.series("daily")
        val sunUp =
            days.times.indices.mapNotNull { day ->
                days.long("sunrise", day)?.let { rise ->
                    days.long("sunset", day)?.let {
                        rise to
                            it
                    }
                }
            }
        return windByHour.times.indices.mapNotNull { hour ->
            val start = windByHour.times[hour]
            val models = MODELS.mapNotNull { (model, theirs) -> windByHour.wind(model, theirs, hour) }
            val conditions = weatherIndex[start]?.let { weatherByHour.conditions(it) }
            if (models.isEmpty() || conditions == null) {
                null
            } else {
                ForecastHourDto(
                    start = Instant.fromEpochSeconds(start),
                    models = models,
                    precipitationMm = conditions.precipitationMm,
                    temperatureC = conditions.temperatureC,
                    thunderRisk = conditions.thunderRisk,
                    isDaylight = sunUp.any { (rise, set) -> start >= rise && start + HOUR_SECONDS <= set - DUSK_SECONDS },
                )
            }
        }
    }

    /** What the hour at [index] is like beside its wind, or null if the source has no rain or temperature for it. */
    private fun Series.conditions(index: Int): Conditions? {
        val precipitation = double("precipitation", index)
        val temperature = double("temperature_2m", index)
        return if (precipitation == null || temperature == null) {
            null
        } else {
            Conditions(precipitation.coerceAtLeast(0.0), temperature, thunderRisk(long("weather_code", index), double("cape", index)))
        }
    }

    private class Conditions(
        val precipitationMm: Double,
        val temperatureC: Double,
        val thunderRisk: ThunderRiskDto,
    )

    private fun thunderRisk(
        weatherCode: Long?,
        capeJPerKg: Double?,
    ): ThunderRiskDto =
        when {
            weatherCode != null && weatherCode in THUNDERSTORM_CODES -> ThunderRiskDto.HIGH
            capeJPerKg != null && capeJPerKg >= UNSTABLE_CAPE_J_PER_KG -> ThunderRiskDto.MEDIUM
            else -> ThunderRiskDto.LOW
        }

    /** A block of the answer (`hourly`, `daily`): its times and, under each name, one value per time. */
    private class Series(
        val times: List<Long>,
        private val values: JsonObject,
    ) {
        fun double(
            name: String,
            index: Int,
        ): Double? = primitive(name, index)?.doubleOrNull

        fun long(
            name: String,
            index: Int,
        ): Long? = primitive(name, index)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

        /** One model's wind for the hour at [index], or null if the model has no complete value for it. */
        fun wind(
            model: String,
            theirs: String,
            index: Int,
        ): ModelWindDto? {
            val mean = double("wind_speed_10m_$theirs", index)
            val gust = double("wind_gusts_10m_$theirs", index)
            val from = double("wind_direction_10m_$theirs", index)
            return if (mean == null || gust == null || from == null) {
                null
            } else {
                // The source writes north as 360 as well as 0; the contract knows 0 only.
                ModelWindDto(model, mean.coerceAtLeast(0.0), gust.coerceAtLeast(0.0), ((from % FULL_TURN) + FULL_TURN) % FULL_TURN)
            }
        }

        private fun primitive(
            name: String,
            index: Int,
        ): JsonPrimitive? = ((values[name] as? JsonArray)?.getOrNull(index) as? JsonPrimitive)?.takeUnless { it is JsonNull }
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
