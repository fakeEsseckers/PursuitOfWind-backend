package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.ForecastHourDto
import io.github.esseckers.pursuitofwind.contract.ModelWindDto
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.contract.SpotDto
import io.github.esseckers.pursuitofwind.contract.SpotForecastDto
import io.github.esseckers.pursuitofwind.contract.ThunderRiskDto
import io.github.esseckers.pursuitofwind.server.testing.FakeOpenMeteo
import io.github.esseckers.pursuitofwind.server.testing.Rider
import io.github.esseckers.pursuitofwind.server.testing.json
import io.github.esseckers.pursuitofwind.server.testing.problem
import io.github.esseckers.pursuitofwind.server.testing.rider
import io.github.esseckers.pursuitofwind.server.testing.serverTest
import io.github.esseckers.pursuitofwind.server.testing.signedInAgain
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A spot's forecast from the source to the app, through HTTP, the server's own client and a real database. */
class ForecastTest {
    /** The clock of a test starts here: Thursday 2026-07-16 06:00 UTC. The source's hours start a day and six hours before. */
    private val now = Instant.parse("2026-07-16T06:00:00Z")

    private suspend fun Rider.spot(
        lat: Double = 54.4936,
        waterBearingDeg: Double = 240.0,
    ): SpotDto =
        call(
            ApiRoute.CREATE_SPOT,
            """{"name":"Nordstrand Bay","location":{"lat":$lat,"lon":8.8131},"waterBearingDeg":$waterBearingDeg,"timeZone":"UTC"}""",
        ).json(SpotDto.serializer())

    private suspend fun Rider.forecast(
        spotId: String,
        query: String? = null,
    ): HttpResponse = call(ApiRoute.GET_SPOT_FORECAST, null, null, spotId, query = query)

    private suspend fun Rider.hours(
        spotId: String,
        query: String? = null,
    ): List<ForecastHourDto> = forecast(spotId, query).json(SpotForecastDto.serializer()).hours

    /** An answer of the real service, kept in `src/test/resources/open-meteo` as it came. */
    private fun recorded(name: String): String =
        checkNotNull(javaClass.getResource("/open-meteo/$name")) { "no recording $name" }.readText()

    /** The index the source gives an hour: hours since its first one. */
    private fun FakeOpenMeteo.indexOf(start: Instant): Int = ((start - firstHour).inWholeHours).toInt()

    @Test
    fun `a forecast is the source's hours from the current one, three days of them, every model in each`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            weather.wind = { model, hour -> Triple(7.0 + hour % 5, 10.5 + hour % 5, if (model == "gfs") 360.0 else 235.0) }
            clock.now += 25.minutes

            val forecast = rider.forecast(spot.id).json(SpotForecastDto.serializer())

            assertEquals(spot.id to clock.now, forecast.spotId to forecast.generatedAt)
            assertEquals(List(72) { now + it.hours }, forecast.hours.map { it.start })
            val first = forecast.hours.first()
            val hour = weather.indexOf(now)
            assertEquals(
                listOf(
                    ModelWindDto("ecmwf", 7.0 + hour % 5, 10.5 + hour % 5, 235.0),
                    ModelWindDto("icon", 7.0 + hour % 5, 10.5 + hour % 5, 235.0),
                    ModelWindDto("gfs", 7.0 + hour % 5, 10.5 + hour % 5, 0.0),
                ),
                first.models,
            )
            assertEquals(Triple(0.0, 18.5, ThunderRiskDto.LOW), Triple(first.precipitationMm, first.temperatureC, first.thunderRisk))
        }

    @Test
    fun `what the real service answered on 23 November 2026 is read as it is`() =
        serverTest {
            clock.now = Instant.parse("2026-11-23T21:10:00Z")
            val rider = rider()
            val spot = rider.spot()
            weather.recorded = recorded("wind-2026-11-23.json") to recorded("weather-2026-11-23.json")

            val hours = rider.hours(spot.id)

            fun night(
                start: String,
                temperatureC: Double,
                vararg models: ModelWindDto,
            ) = ForecastHourDto(Instant.parse(start), models.toList(), 0.0, temperatureC, ThunderRiskDto.LOW, isDaylight = false)
            assertEquals(
                listOf(
                    night(
                        "2026-11-23T21:00:00Z",
                        1.6,
                        ModelWindDto("ecmwf", 2.94, 5.0, 342.0),
                        ModelWindDto("icon", 3.7, 6.3, 0.0),
                        ModelWindDto("gfs", 4.1, 6.1, 357.0),
                    ),
                    night(
                        "2026-11-23T22:00:00Z",
                        1.2,
                        ModelWindDto("ecmwf", 2.75, 4.8, 350.0),
                        ModelWindDto("icon", 3.0, 5.7, 2.0),
                        ModelWindDto("gfs", 3.9, 5.2, 359.0),
                    ),
                    night(
                        "2026-11-23T23:00:00Z",
                        1.2,
                        ModelWindDto("ecmwf", 2.6, 4.6, 0.0),
                        ModelWindDto("icon", 2.8, 4.6, 0.0),
                        ModelWindDto("gfs", 3.8, 5.1, 358.0),
                    ),
                ),
                hours,
            )
        }

    @Test
    fun `a call says from which hour and how many, and gets fewer near the end of what the source has`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()

            val insideAnHour = rider.hours(spot.id, "from=2026-07-18T10:40:00Z&hours=3")
            val lastHours = rider.hours(spot.id, "from=${weather.firstHour + (11 * 24 - 2).hours}&hours=168")
            val past = rider.hours(spot.id, "from=2026-07-15T22:00:00Z&hours=2")

            assertEquals(List(3) { Instant.parse("2026-07-18T10:00:00Z") + it.hours }, insideAnHour.map { it.start })
            assertEquals(2, lastHours.size)
            assertEquals(Instant.parse("2026-07-15T22:00:00Z"), past.first().start)
            assertEquals(168, rider.hours(spot.id, "hours=168").size)
        }

    @Test
    fun `a model with nothing for an hour is left out of it, and an hour no model has is left out altogether`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            val gfsEnds = weather.indexOf(now + 2.hours)
            val allEnd = weather.indexOf(now + 4.hours)
            weather.wind = { model, hour -> if (hour >= allEnd || (model == "gfs" && hour >= gfsEnds)) null else Triple(9.0, 12.0, 240.0) }

            val hours = rider.hours(spot.id)

            assertEquals(listOf(3, 3, 2, 2), hours.map { it.models.size })
            assertEquals(listOf("ecmwf", "icon"), hours.last().models.map { it.model })
        }

    @Test
    fun `thunder is high when the source forecasts a thunderstorm, medium in unstable air, low otherwise`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            val first = weather.indexOf(now)
            weather.weatherCode = { hour -> mapOf(first to 95, first + 1 to 99, first + 2 to 82, first + 3 to 3)[hour] ?: 1 }
            weather.capeJPerKg = { hour -> mapOf(first + 2 to 1000.0, first + 3 to 999.0, first + 4 to 2600.0)[hour] ?: 0.0 }

            val risks = rider.hours(spot.id, "hours=6").map { it.thunderRisk }

            assertEquals(
                listOf(
                    ThunderRiskDto.HIGH,
                    ThunderRiskDto.HIGH,
                    ThunderRiskDto.MEDIUM,
                    ThunderRiskDto.LOW,
                    ThunderRiskDto.MEDIUM,
                    ThunderRiskDto.LOW,
                ),
                risks,
            )
        }

    @Test
    fun `an hour is in daylight from sunrise until half an hour before sunset`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()

            val day =
                rider.hours(spot.id, "from=2026-07-17T00:00:00Z&hours=24").associate {
                    it.start
                        .toString()
                        .substring(11, 13)
                        .toInt() to
                        it.isDaylight
                }

            assertEquals((FakeOpenMeteo.SUNRISE_HOUR until FakeOpenMeteo.SUNSET_HOUR - 1).toSet(), day.filterValues { it }.keys)
        }

    @Test
    fun `rain and temperature are the source's, and an hour without them is left out`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            val first = weather.indexOf(now)
            weather.precipitationMm = { hour -> if (hour == first + 1) null else 0.4 * (hour - first) }
            weather.temperatureC = { hour -> -3.5 + (hour - first) }

            val hours = rider.hours(spot.id, "hours=3")

            assertEquals(listOf(now, now + 2.hours, now + 3.hours), hours.map { it.start })
            assertEquals(listOf(0.0 to -3.5, 0.8 to -1.5, 1.2000000000000002 to -0.5), hours.map { it.precipitationMm to it.temperatureC })
        }

    @Test
    fun `riders looking at one spot within the hour share one forecast, and the next hour brings a new one`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val spot = alex.spot()

            val first = alex.forecast(spot.id).json(SpotForecastDto.serializer())
            clock.now += 59.minutes
            val shared = dana.forecast(spot.id).json(SpotForecastDto.serializer())
            assertEquals(2, weather.requests)
            assertEquals(first.generatedAt, shared.generatedAt)

            clock.now += 1.minutes
            val fresh = signedInAgain(dana).forecast(spot.id).json(SpotForecastDto.serializer())
            assertEquals(4, weather.requests)
            assertEquals(clock.now, fresh.generatedAt)
        }

    @Test
    fun `eight riders asking for one spot at the same moment cost one forecast`() =
        serverTest {
            val riders = List(8) { rider() }
            val spot = riders.first().spot()

            val forecasts = coroutineScope { riders.map { async { it.forecast(spot.id) } }.awaitAll() }

            assertEquals(List(8) { HttpStatusCode.OK }, forecasts.map { it.status })
            assertEquals(2, weather.requests)
        }

    @Test
    fun `a spot that moved gets the forecast of its new place`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            rider.forecast(spot.id)

            rider.call(ApiRoute.UPDATE_SPOT, """{"name":"Moved","location":{"lat":36.0,"lon":-5.6},"waterBearingDeg":200}""", null, spot.id)
            rider.forecast(spot.id)

            assertEquals(listOf("54.4936,8.8131", "36.0,-5.6"), weather.places.distinct())
            assertEquals(4, weather.requests)
        }

    @Test
    fun `when the source does not answer, the forecast that is there is given, and a spot without one says come again`() =
        serverTest {
            val morning = rider()
            val seen = morning.spot()
            val unseen = morning.spot(lat = 55.0)
            val earlier = morning.forecast(seen.id).json(SpotForecastDto.serializer())
            clock.now += 3.hours
            val rider = signedInAgain(morning)

            for (failure in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests)) {
                weather.failsWith = failure

                val stale = rider.forecast(seen.id)
                val none = rider.forecast(unseen.id)

                assertEquals(HttpStatusCode.OK, stale.status, failure.toString())
                assertEquals(earlier.generatedAt, stale.json(SpotForecastDto.serializer()).generatedAt)
                assertEquals(HttpStatusCode.ServiceUnavailable, none.status)
                assertEquals(ProblemCode.SERVICE_UNAVAILABLE to "1", none.problem().code to none.headers["Retry-After"])
            }

            weather.failsWith = null
            assertEquals(clock.now, rider.forecast(seen.id).json(SpotForecastDto.serializer()).generatedAt)
        }

    @Test
    fun `an answer of the source that is not a forecast is no forecast`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()

            for (nonsense in listOf(
                "<html>busy</html>",
                "{}",
                """{"hourly":{"time":["soon"]}}""",
                """{"error":true,"reason":"too far north"}""",
            )) {
                weather.answersWith = nonsense

                assertEquals(HttpStatusCode.ServiceUnavailable, rider.forecast(spot.id).status, nonsense)
            }
        }

    @Test
    fun `a forecast of a spot that is not there, or for hours the contract does not allow, is refused`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()

            assertEquals(ProblemCode.NOT_FOUND, rider.forecast("spt_nowhere", "hours=0").problem().code)
            for (query in listOf("hours=0", "hours=169", "hours=many", "from=tomorrow", "from=2026-07-18")) {
                val response = rider.forecast(spot.id, query)

                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, query)
                assertEquals(ProblemCode.VALIDATION_FAILED, response.problem().code, query)
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoute.GET_SPOT_FORECAST.pathWith(spot.id)).status)
            assertEquals(0, weather.requests)
        }

    @Test
    fun `every hour of a forecast is one the contract allows`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            weather.wind = { _, hour -> Triple(hour % 30 * 1.0, hour % 30 * 1.4, (hour * 37 % 361).toDouble()) }

            val hours = rider.hours(spot.id, "hours=168")

            assertTrue(
                hours.all { hour ->
                    hour.models.isNotEmpty() && hour.models
                        .map { it.model }
                        .toSet()
                        .size == hour.models.size
                },
            )
            assertTrue(hours.flatMap { it.models }.all { it.meanMps >= 0 && it.gustMps >= 0 && it.bearingDeg >= 0 && it.bearingDeg < 360 })
            assertTrue(hours.all { it.precipitationMm >= 0 })
            assertEquals(hours.map { it.start }, hours.map { it.start }.sorted())
        }
}
