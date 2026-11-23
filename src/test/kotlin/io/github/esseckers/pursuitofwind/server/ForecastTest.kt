package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.ForecastHourDto
import io.github.esseckers.pursuitofwind.contract.ModelWindDto
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.contract.SpotDto
import io.github.esseckers.pursuitofwind.contract.SpotForecastDto
import io.github.esseckers.pursuitofwind.contract.ThunderRiskDto
import io.github.esseckers.pursuitofwind.server.db.Database
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

    /** The hours of the UTC day [date] that the spot's forecast calls daylight, as hours of that day. */
    private suspend fun Rider.daylight(
        spotId: String,
        date: String,
    ): Set<Int> {
        val midnight = Instant.parse("${date}T00:00:00Z")
        return hours(spotId, "from=$midnight&hours=24").filter { it.isDaylight }.map { (it.start - midnight).inWholeHours.toInt() }.toSet()
    }

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
            // The mean and the bearing are the hour's own; the gust is the strongest of the hour, stamped at its end.
            assertEquals(
                listOf(
                    ModelWindDto("ecmwf", 7.0 + hour % 5, 10.5 + (hour + 1) % 5, 235.0),
                    ModelWindDto("icon", 7.0 + hour % 5, 10.5 + (hour + 1) % 5, 235.0),
                    ModelWindDto("gfs", 7.0 + hour % 5, 10.5 + (hour + 1) % 5, 0.0),
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
                        ModelWindDto("ecmwf", 2.94, 4.8, 342.0),
                        ModelWindDto("icon", 3.7, 5.7, 0.0),
                        ModelWindDto("gfs", 4.1, 5.2, 357.0),
                    ),
                    night(
                        "2026-11-23T22:00:00Z",
                        1.2,
                        ModelWindDto("ecmwf", 2.75, 4.6, 350.0),
                        ModelWindDto("icon", 3.0, 4.6, 2.0),
                        ModelWindDto("gfs", 3.9, 5.1, 359.0),
                    ),
                ),
                hours,
            )
            // The recording's last hour, 23:00, is not given: nothing is stamped at its end to say how it gusted.
        }

    @Test
    fun `a call says from which hour and how many, and gets fewer near the end of what the source has`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()

            val insideAnHour = rider.hours(spot.id, "from=2026-07-18T10:40:00Z&hours=3")
            val end = weather.firstHour + 11.days
            val lastHours = rider.hours(spot.id, "from=${end - 3.hours}&hours=168")
            val past = rider.hours(spot.id, "from=2026-07-15T22:00:00Z&hours=2")

            assertEquals(List(3) { Instant.parse("2026-07-18T10:00:00Z") + it.hours }, insideAnHour.map { it.start })
            // The source's last hour has no stamp at its end, so the one before it is the last there is to give.
            assertEquals(listOf(end - 3.hours, end - 2.hours), lastHours.map { it.start })
            assertEquals(Instant.parse("2026-07-15T22:00:00Z"), past.first().start)
            assertEquals(168, rider.hours(spot.id, "hours=168").size)
        }

    @Test
    fun `a model with nothing for an hour or for its end is left out of it, and an hour no model has is left out altogether`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            val gfsEnds = weather.indexOf(now + 2.hours)
            val allEnd = weather.indexOf(now + 4.hours)
            weather.wind = { model, hour -> if (hour >= allEnd || (model == "gfs" && hour >= gfsEnds)) null else Triple(9.0, 12.0, 240.0) }

            val hours = rider.hours(spot.id)

            // GFS has a mean for the second hour and no gust stamped at its end; nobody has one at the fourth's.
            assertEquals(listOf(now, now + 1.hours, now + 2.hours), hours.map { it.start })
            assertEquals(listOf(3, 2, 2), hours.map { it.models.size })
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
    fun `an hour is in daylight when it starts at or after sunrise and ends half an hour before sunset`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            weather.sun = { day ->
                when (day) {
                    2 -> (4.hours + 20.minutes) to (20.hours + 30.minutes)
                    3 -> 4.hours to (20.hours + 29.minutes)
                    else -> FakeOpenMeteo.SUNRISE to FakeOpenMeteo.SUNSET
                }
            }

            assertEquals((4..18).toSet(), rider.daylight(spot.id, "2026-07-16"))
            assertEquals((5..19).toSet(), rider.daylight(spot.id, "2026-07-17"))
            assertEquals((4..18).toSet(), rider.daylight(spot.id, "2026-07-18"))
        }

    @Test
    fun `far from Greenwich the light of a day runs into the next day of the source`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            // As the real service gives them for the Pacific: the sunrise of a UTC day, and the sunset that follows it.
            weather.sun = { 18.hours to 32.hours }

            assertEquals((0..6).toSet() + (18..23).toSet(), rider.daylight(spot.id, "2026-07-17"))
        }

    @Test
    fun `a day without a night is light all through, and a day without a sun is dark`() =
        serverTest {
            val rider = rider()
            val north = rider.spot(lat = 69.6)
            val south = rider.spot(lat = -69.6)
            // As the real service gives them: a polar day from midnight to midnight, a polar night as one moment.
            weather.sun = { 0.hours to 24.hours }
            val summer = rider.daylight(north.id, "2026-07-17")
            weather.sun = { 0.hours to 0.hours }
            val winter = rider.daylight(south.id, "2026-07-17")

            assertEquals((0..23).toSet(), summer)
            assertEquals(emptySet(), winter)
        }

    @Test
    fun `rain is what fell during the hour, and an hour the source lacks is a gap that later hours do not fill`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            val first = weather.indexOf(now)
            weather.precipitationMm = { hour -> 0.5 * (hour - first) }
            weather.temperatureC = { hour -> if (hour == first + 1) null else -3.5 + (hour - first) }

            val hours = rider.hours(spot.id, "hours=3")

            assertEquals(listOf(now, now + 2.hours), hours.map { it.start })
            assertEquals(listOf(0.5 to -3.5, 1.5 to -1.5), hours.map { it.precipitationMm to it.temperatureC })
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
            // Long enough for all eight to be there before the first of them has its answer.
            weather.takes = 300.milliseconds

            val forecasts = coroutineScope { riders.map { async { it.forecast(spot.id) } }.awaitAll() }

            assertEquals(List(8) { HttpStatusCode.OK }, forecasts.map { it.status })
            assertEquals(2, weather.requests)
        }

    @Test
    fun `spots on one beach share a forecast whoever added them, and a spot that moved gets its new place's`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val his = alex.spot(lat = 54.4936)
            val hers = dana.spot(lat = 54.4921)
            val first = alex.forecast(his.id).json(SpotForecastDto.serializer())
            clock.now += 10.minutes
            val second = dana.forecast(hers.id).json(SpotForecastDto.serializer())
            assertEquals(2, weather.requests)
            assertEquals(first.generatedAt to first.hours, second.generatedAt to second.hours)

            alex.call(ApiRoute.UPDATE_SPOT, """{"name":"Moved","location":{"lat":36.004,"lon":-5.6},"waterBearingDeg":200}""", null, his.id)
            alex.forecast(his.id)

            assertEquals(listOf("54.49,8.81", "36.0,-5.6"), weather.places.distinct())
            assertEquals(4, weather.requests)
        }

    @Test
    fun `however the source fails, the forecast that is there is given, and a spot without one says when to come again`() =
        serverTest {
            val morning = rider()
            val seen = morning.spot()
            val unseen = morning.spot(lat = 55.0)
            val earlier = morning.forecast(seen.id).json(SpotForecastDto.serializer())
            val failures: List<Pair<String, FakeOpenMeteo.() -> Unit>> =
                listOf(
                    "the connection breaks" to { unreachable = true },
                    "it is down" to { failsWith = HttpStatusCode.ServiceUnavailable },
                    "it has had enough of this server" to { failsWith = HttpStatusCode.TooManyRequests },
                    "it refuses the request" to { failsWith = HttpStatusCode.BadRequest },
                    "a page in place of JSON" to { answersWith = "<html>busy</html>" },
                    "JSON with nothing in it" to { answersWith = "{}" },
                    "its error with a good status" to { answersWith = """{"error":true,"reason":"Not today"}""" },
                    "times that are not times" to { answersWith = """{"hourly":{"time":["soon"]},"daily":{"time":[]}}""" },
                    "a forecast without an hour" to { answersWith = """{"hourly":{"time":[]},"daily":{"time":[]}}""" },
                )

            for ((failure, happens) in failures) {
                clock.now += 2.hours
                val rider = signedInAgain(morning)
                weather.unreachable = false
                weather.failsWith = null
                weather.answersWith = null
                weather.happens()

                val stale = rider.forecast(seen.id)
                val none = rider.forecast(unseen.id)

                assertEquals(HttpStatusCode.OK, stale.status, failure)
                assertEquals(earlier.generatedAt, stale.json(SpotForecastDto.serializer()).generatedAt, failure)
                assertEquals(HttpStatusCode.ServiceUnavailable, none.status, failure)
                assertEquals(ProblemCode.SERVICE_UNAVAILABLE to "300", none.problem().code to none.headers["Retry-After"], failure)
            }
        }

    @Test
    fun `a source that failed is left alone for five minutes, then asked again`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            weather.unreachable = true
            assertEquals(HttpStatusCode.ServiceUnavailable, rider.forecast(spot.id).status)
            val asked = weather.requests

            clock.now += 5.minutes - 1.seconds
            val again = List(3) { rider.forecast(spot.id) }

            assertEquals(List(3) { HttpStatusCode.ServiceUnavailable to "1" }, again.map { it.status to it.headers["Retry-After"] })
            assertEquals(asked, weather.requests)

            weather.unreachable = false
            clock.now += 1.seconds
            val forecast = rider.forecast(spot.id)

            assertEquals(HttpStatusCode.OK, forecast.status)
            assertEquals(clock.now, forecast.json(SpotForecastDto.serializer()).generatedAt)
            assertEquals(asked + 2, weather.requests)
        }

    @Test
    fun `riders waiting for a source that then fails get their answer together, and it is asked once`() =
        serverTest {
            val riders = List(8) { rider() }
            val spot = riders.first().spot()
            weather.takes = 300.milliseconds
            weather.failsWith = HttpStatusCode.BadGateway

            val forecasts = coroutineScope { riders.map { async { it.forecast(spot.id) } }.awaitAll() }

            assertEquals(List(8) { HttpStatusCode.ServiceUnavailable }, forecasts.map { it.status })
            assertEquals(2, weather.requests)
        }

    @Test
    fun `a kept forecast that can no longer be read is fetched anew`() =
        serverTest {
            val rider = rider()
            val spot = rider.spot()
            rider.forecast(spot.id)

            for ((round, unreadable) in listOf("""[{"start":"soon"}]""", """{"hours":[]}""", "[1]").withIndex()) {
                Database(database).use { database ->
                    database.transaction {
                        it
                            .createUpdate(
                                "update forecasts set hours = cast(:hours as jsonb)",
                            ).bind("hours", unreadable)
                            .execute()
                    }
                }

                assertEquals(72, rider.hours(spot.id).size, unreadable)
                assertEquals(2 * (round + 2), weather.requests, unreadable)
            }
        }

    @Test
    fun `a forecast nobody has asked about for a week is forgotten`() =
        serverTest {
            val first = rider()
            val old = first.spot()
            val other = first.spot(lat = 55.0)
            first.forecast(old.id)
            clock.now += 7.days
            val rider = signedInAgain(first)
            rider.forecast(other.id)
            weather.failsWith = HttpStatusCode.ServiceUnavailable

            assertEquals(HttpStatusCode.ServiceUnavailable, rider.forecast(old.id).status)
            assertEquals(HttpStatusCode.OK, rider.forecast(other.id).status)
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
