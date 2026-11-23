package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiLimits
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.SpotForecastDto
import io.github.esseckers.pursuitofwind.server.spot.Spots
import io.ktor.server.routing.Route
import kotlin.time.Instant

private const val DEFAULT_HOURS = 72
private const val HOUR_SECONDS = 3600L
private val HOURS = 1..ApiLimits.Forecast.HOURS_MAX

/** `GET /v1/spots/{spotId}/forecast`: the hours of a spot's forecast a call asks for, per weather model. */
internal fun Route.forecastRoutes(api: Api) {
    signedIn(api, ApiRoute.GET_SPOT_FORECAST) {
        val id = call.parameters["spotId"].orEmpty()
        val spot = api.database.read { Spots.find(it, id) } ?: throw notFound("Spot $id")
        val now = api.now()
        // An hour starts on the full hour; a `from` inside one starts with that hour.
        val from =
            instantQuery(
                call.parameters,
                "from",
                now,
            ).let { Instant.fromEpochSeconds(it.epochSeconds / HOUR_SECONDS * HOUR_SECONDS) }
        val hours = intQuery(call.parameters, "hours", HOURS, DEFAULT_HOURS)
        val forecast = api.forecasts.of(spot, now)
        call.respondJson(
            SpotForecastDto.serializer(),
            SpotForecastDto(spot.id, forecast.fetchedAt, forecast.hours.filter { it.start >= from }.take(hours)),
        )
    }
}
