package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiHeaders
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.contract.RiderProfileDto
import io.github.esseckers.pursuitofwind.contract.SkillLevelDto
import io.github.esseckers.pursuitofwind.contract.WindUnitDto
import io.github.esseckers.pursuitofwind.server.api.Answer
import io.github.esseckers.pursuitofwind.server.api.Api
import io.github.esseckers.pursuitofwind.server.api.changing
import io.github.esseckers.pursuitofwind.server.db.Database
import io.github.esseckers.pursuitofwind.server.testing.Rider
import io.github.esseckers.pursuitofwind.server.testing.TestPostgres
import io.github.esseckers.pursuitofwind.server.testing.json
import io.github.esseckers.pursuitofwind.server.testing.problem
import io.github.esseckers.pursuitofwind.server.testing.rider
import io.github.esseckers.pursuitofwind.server.testing.serverTest
import io.github.esseckers.pursuitofwind.server.testing.signedInAgain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** The rider's profile and `Idempotency-Key`, through HTTP on a real database. */
class ProfileTest {
    private val alex =
        RiderProfileDto(
            displayName = "Alex",
            weightKg = 78.5,
            level = SkillLevelDto.INTERMEDIATE,
            quiverM2 = listOf(7.0, 9.0, 12.0),
            minWindMps = 6.0,
            maxWindMps = 14.5,
            minSessionMinutes = 90,
            maxDriveMinutes = 120,
            ridingFromHour = 7,
            ridingToHour = 20,
            windUnit = WindUnitDto.KNOTS,
        )
    private val firstKey = "3f9d6c1e-8a42-4f4b-9d0e-6b1c2a7e5f10"
    private val secondKey = "7c1b2a90-5d3e-4f6a-8b7c-9d0e1f2a3b4c"
    private val keys = "${Database.SCHEMA}.idempotency_keys"

    private fun RiderProfileDto.asJson(): String = ContractJson.encodeToString(RiderProfileDto.serializer(), this)

    private suspend fun Rider.save(
        profile: RiderProfileDto,
        key: String? = null,
    ): HttpResponse = call(ApiRoute.PUT_PROFILE, profile.asJson(), key)

    private suspend fun Rider.profile(): HttpResponse = call(ApiRoute.GET_PROFILE)

    private suspend fun HttpResponse.assertInvalid(what: String) {
        assertEquals(HttpStatusCode.UnprocessableEntity, status, what)
        assertEquals(ProblemCode.VALIDATION_FAILED, problem().code, what)
    }

    @Test
    fun `a rider who has not saved a profile is told so`() =
        serverTest {
            val response = rider().profile()

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ProblemCode.NOT_FOUND, response.problem().code)
        }

    @Test
    fun `a saved profile comes back as it was saved, and a second save replaces it`() =
        serverTest {
            val rider = rider()

            val saved = rider.save(alex)

            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(alex, saved.json(RiderProfileDto.serializer()))
            assertNull(saved.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(alex, rider.profile().json(RiderProfileDto.serializer()))

            val heavier = alex.copy(weightKg = 82.0, quiverM2 = emptyList(), minWindMps = null, maxWindMps = null)
            assertEquals(heavier, rider.save(heavier).json(RiderProfileDto.serializer()))
            assertEquals(heavier, rider.profile().json(RiderProfileDto.serializer()))
        }

    @Test
    fun `riding hours left out are nine to nine in the evening`() =
        serverTest {
            val rider = rider()
            val body =
                """{"displayName":"Dana","weightKg":61,"level":"BEGINNER","quiverM2":[9],"minSessionMinutes":60,""" +
                    """"maxDriveMinutes":45,"windUnit":"METERS_PER_SECOND","somethingNew":true}"""

            val saved = rider.call(ApiRoute.PUT_PROFILE, body).json(RiderProfileDto.serializer())

            assertEquals(9 to 21, saved.ridingFromHour to saved.ridingToHour)
            assertEquals(saved, rider.profile().json(RiderProfileDto.serializer()))
        }

    @Test
    fun `a profile is its rider's own`() =
        serverTest {
            val first = rider()
            val second = rider()
            first.save(alex)
            second.save(alex.copy(displayName = "Dana"))

            assertEquals("Alex", first.profile().json(RiderProfileDto.serializer()).displayName)
            assertEquals("Dana", second.profile().json(RiderProfileDto.serializer()).displayName)
            assertEquals(HttpStatusCode.NotFound, rider().profile().status)
        }

    @Test
    fun `the profile is for signed-in riders only`() =
        serverTest {
            assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoute.GET_PROFILE.path).status)
            assertEquals(HttpStatusCode.Unauthorized, client.put(ApiRoute.PUT_PROFILE.path).status)
        }

    @Test
    fun `a profile outside the contract's limits is a validation problem and is not saved`() =
        serverTest {
            val rider = rider()
            val outside =
                mapOf(
                    "a name of spaces" to alex.copy(displayName = "   "),
                    "an empty name" to alex.copy(displayName = ""),
                    "a name of 41 characters" to alex.copy(displayName = "a".repeat(41)),
                    "a name with a character no database keeps" to alex.copy(displayName = "Al\u0000ex"),
                    "too light" to alex.copy(weightKg = 29.9),
                    "too heavy" to alex.copy(weightKg = 200.1),
                    "eleven kites" to alex.copy(quiverM2 = (1..11).map { it.toDouble() }),
                    "a size twice" to alex.copy(quiverM2 = listOf(9.0, 9.0)),
                    "a kite of no size" to alex.copy(quiverM2 = listOf(0.0)),
                    "a kite too big" to alex.copy(quiverM2 = listOf(25.5)),
                    "a negative wind limit" to alex.copy(minWindMps = -1.0),
                    "wind limits the wrong way round" to alex.copy(minWindMps = 12.0, maxWindMps = 12.0),
                    "a session too short" to alex.copy(minSessionMinutes = 29),
                    "a session too long" to alex.copy(minSessionMinutes = 721),
                    "a negative drive" to alex.copy(maxDriveMinutes = -1),
                    "a drive too long" to alex.copy(maxDriveMinutes = 721),
                    "a start hour of 24" to alex.copy(ridingFromHour = 24, ridingToHour = 24),
                    "an end hour of 25" to alex.copy(ridingToHour = 25),
                    "hours the wrong way round" to alex.copy(ridingFromHour = 12, ridingToHour = 12),
                )

            for ((what, profile) in outside) rider.save(profile).assertInvalid(what)
            rider.call(ApiRoute.PUT_PROFILE, """{"displayName":"Alex"}""").assertInvalid("fields missing")
            rider.call(ApiRoute.PUT_PROFILE, alex.asJson().replace("INTERMEDIATE", "EXPERT")).assertInvalid("an unknown level")
            rider.call(ApiRoute.PUT_PROFILE, "not json").assertInvalid("not json")

            assertEquals(HttpStatusCode.NotFound, rider.profile().status)
        }

    @Test
    fun `a name of forty emoji is forty characters`() =
        serverTest {
            val rider = rider()

            assertEquals(HttpStatusCode.OK, rider.save(alex.copy(displayName = "🏄".repeat(40))).status)
            rider.save(alex.copy(displayName = "🏄".repeat(41))).assertInvalid("41 emoji")
        }

    @Test
    fun `a save sent again with its key changes nothing and gets the first answer back`() =
        serverTest {
            val rider = rider()
            val first = rider.save(alex, firstKey)
            val later = alex.copy(displayName = "Alexander")
            rider.save(later, secondKey)

            val again = rider.save(alex, firstKey)

            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals("true", again.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(first.bodyAsText(), again.bodyAsText())
            assertNull(first.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(later, rider.profile().json(RiderProfileDto.serializer()))
        }

    @Test
    fun `a key sent with another body is refused, and nothing changes`() =
        serverTest {
            val rider = rider()
            rider.save(alex, firstKey)

            val response = rider.save(alex.copy(weightKg = 90.0), firstKey)

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(ProblemCode.IDEMPOTENCY_KEY_REUSED, response.problem().code)
            assertEquals(alex, rider.profile().json(RiderProfileDto.serializer()))
        }

    @Test
    fun `a key is one rider's, and must be a UUID`() =
        serverTest {
            val first = rider()
            val second = rider()
            first.save(alex, firstKey)

            val other = second.save(alex.copy(displayName = "Dana"), firstKey)

            assertEquals(HttpStatusCode.OK, other.status)
            assertNull(other.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            first.save(alex, "not-a-uuid").assertInvalid("a key that is not a UUID")
        }

    @Test
    fun `a key is kept for a day and is free again after it`() =
        serverTest {
            val morning = rider()
            morning.save(alex, firstKey)

            clock.now += 24.hours - 1.seconds
            val nextMorning = signedInAgain(morning)
            assertEquals("true", nextMorning.save(alex, firstKey).headers[ApiHeaders.IDEMPOTENT_REPLAYED])

            clock.now += 1.seconds
            val afterADay = nextMorning.save(alex.copy(weightKg = 80.0), firstKey)
            assertEquals(HttpStatusCode.OK, afterADay.status)
            assertNull(afterADay.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(80.0, nextMorning.profile().json(RiderProfileDto.serializer()).weightKg)
        }

    @Test
    fun `a save that failed is not kept, so the corrected one may use the same key`() =
        serverTest {
            val rider = rider()
            rider.save(alex.copy(weightKg = 500.0), firstKey).assertInvalid("too heavy")

            val corrected = rider.save(alex, firstKey)

            assertEquals(HttpStatusCode.OK, corrected.status)
            assertNull(corrected.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
        }

    @Test
    fun `a key is the same key in capitals and at another spelling of the path`() =
        serverTest {
            val rider = rider()
            rider.save(alex, firstKey)

            val inCapitals = rider.save(alex, firstKey.uppercase())
            val otherSpelling =
                client.put("/v1/me/%70rofile") {
                    bearerAuth(rider.tokens.accessToken)
                    header(ApiHeaders.IDEMPOTENCY_KEY, firstKey)
                    setBody(alex.asJson())
                }

            assertEquals("true", inCapitals.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals("true", otherSpelling.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(listOf("1"), TestPostgres.strings(database, "select count(*) from $keys"))
        }

    @Test
    fun `a key that has had its day is free however many others wait to be removed`() =
        serverTest {
            val morning = rider()
            morning.save(alex, firstKey)
            TestPostgres.execute(
                database,
                """
                insert into $keys (user_id, method, path, key, request_hash, status, created_at)
                select user_id, 'PUT', '/v1/old/' || n, key, request_hash, 200, created_at
                from $keys, generate_series(1, 250) as n
                """,
            )

            clock.now += 24.hours
            val nextDay = signedInAgain(morning).save(alex.copy(weightKg = 80.0), firstKey)

            assertEquals(HttpStatusCode.OK, nextDay.status)
            assertNull(nextDay.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(listOf("151"), TestPostgres.strings(database, "select count(*) from $keys"))
        }

    @Test
    fun `a change that broke inside is not kept, and runs again when it is sent again`() {
        var calls = 0
        val breaksOnce: Application.(Api) -> Unit = { api ->
            // Beside the server's own routes: every operation of the contract that changes something is served.
            routing {
                route("/breaking") {
                    changing(api, ApiRoute.CREATE_CREW) {
                        calls++
                        check(calls > 1) { "the first call breaks" }
                        Answer.noContent
                    }
                }
            }
        }
        serverTest(breaksOnce) {
            val rider = rider()

            suspend fun call() =
                client.post("/breaking" + ApiRoute.CREATE_CREW.path) {
                    bearerAuth(rider.tokens.accessToken)
                    header(ApiHeaders.IDEMPOTENCY_KEY, firstKey)
                    setBody("{}")
                }
            val broken = call()
            val again = call()
            val replayed = call()

            assertEquals(HttpStatusCode.InternalServerError, broken.status)
            assertEquals(HttpStatusCode.NoContent, again.status)
            assertNull(again.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(HttpStatusCode.NoContent to "true", replayed.status to replayed.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(2, calls)
        }
    }

    @Test
    fun `one save sent eight times at the same moment happens once`() =
        serverTest {
            val rider = rider()
            // Nobody can touch the keys until the test lets go, so the calls pile up behind it and then go together.
            val everyone = TestPostgres.holding(database, "lock table $keys in access exclusive mode")
            val waiting = "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"

            val responses =
                coroutineScope {
                    val calls = List(8) { async { rider.save(alex, firstKey) } }
                    var polls = 0
                    while (TestPostgres.strings(database, waiting).single().toInt() < 5 && polls++ < 500) delay(20)
                    assertEquals(listOf("5"), TestPostgres.strings(database, waiting))
                    everyone.close()
                    calls.awaitAll()
                }

            assertEquals(List(8) { HttpStatusCode.OK }, responses.map { it.status })
            assertEquals(7, responses.count { it.headers[ApiHeaders.IDEMPOTENT_REPLAYED] == "true" })
            assertEquals(1, responses.map { it.bodyAsText() }.toSet().size)
            assertEquals("application/json", responses.first().headers[HttpHeaders.ContentType])
            assertEquals(listOf("1"), TestPostgres.strings(database, "select count(*) from $keys"))
        }

    @Test
    fun `a body larger than the server reads is refused without being read to its end`() =
        serverTest {
            val rider = rider()

            rider.call(ApiRoute.PUT_PROFILE, alex.asJson() + " ".repeat(64 * 1024)).assertInvalid("a body over the limit")

            assertEquals(HttpStatusCode.NotFound, rider.profile().status)
        }

    @Test
    fun `a call that meets the database gone is told to come again`() =
        serverTest {
            val rider = rider()
            TestPostgres.drop(database)

            val response = rider.profile()

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(ProblemCode.SERVICE_UNAVAILABLE, response.problem().code)
            assertEquals("1", response.headers[ApiHeaders.RETRY_AFTER])
        }
}
