package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiHeaders
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.CreateRideRequestDto
import io.github.esseckers.pursuitofwind.contract.CrewDto
import io.github.esseckers.pursuitofwind.contract.CrewEventDto
import io.github.esseckers.pursuitofwind.contract.CrewFeedDto
import io.github.esseckers.pursuitofwind.contract.PersonDto
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.contract.ProblemDto
import io.github.esseckers.pursuitofwind.contract.RideCancelledEventDto
import io.github.esseckers.pursuitofwind.contract.RideCreatedEventDto
import io.github.esseckers.pursuitofwind.contract.RideDto
import io.github.esseckers.pursuitofwind.contract.RideListDto
import io.github.esseckers.pursuitofwind.contract.RideStatusDto
import io.github.esseckers.pursuitofwind.contract.SeatReleasedEventDto
import io.github.esseckers.pursuitofwind.contract.SeatTakenEventDto
import io.github.esseckers.pursuitofwind.contract.SpotDto
import io.github.esseckers.pursuitofwind.server.db.Database
import io.github.esseckers.pursuitofwind.server.testing.Rider
import io.github.esseckers.pursuitofwind.server.testing.json
import io.github.esseckers.pursuitofwind.server.testing.problem
import io.github.esseckers.pursuitofwind.server.testing.rider
import io.github.esseckers.pursuitofwind.server.testing.serverTest
import io.github.esseckers.pursuitofwind.server.testing.together
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Rides and seats, and what the crew's feed says of them, through HTTP on a real database. */
class RideTest {
    private val key = "3f9d6c1e-8a42-4f4b-9d0e-6b1c2a7e5f10"

    /** Saturday's session, two days after the clock of a test: leave at 08:30, ride from 11:00 to 15:00 UTC. */
    private val saturday =
        CreateRideRequestDto(
            spotId = "",
            windowStart = Instant.parse("2026-07-18T11:00:00Z"),
            windowEnd = Instant.parse("2026-07-18T15:00:00Z"),
            departAt = Instant.parse("2026-07-18T08:30:00Z"),
            meetingPoint = "Hauptbahnhof, north exit",
            seatsTotal = 3,
        )

    /** The name the server gives a rider who has not saved a profile. */
    private val Rider.unnamed: String get() = "Rider " + userId.takeLast(4)

    private val Rider.person: PersonDto get() = PersonDto(userId, unnamed)

    /** What a test's rides happen in: a crew of riders, the first its owner, and a spot to ride to. */
    private class Scene(
        val crew: CrewDto,
        val spot: SpotDto,
    )

    private suspend fun scene(
        owner: Rider,
        vararg others: Rider,
    ): Scene {
        val crew = owner.call(ApiRoute.CREATE_CREW, """{"name":"Weekend Crew"}""").json(CrewDto.serializer())
        for (other in others) {
            assertEquals(HttpStatusCode.OK, other.call(ApiRoute.JOIN_CREW, """{"inviteCode":"${crew.inviteCode}"}""").status)
        }
        val spot =
            owner
                .call(
                    ApiRoute.CREATE_SPOT,
                    """{"name":"Nordstrand Bay","location":{"lat":54.4936,"lon":8.8131},"waterBearingDeg":240,"timeZone":"UTC"}""",
                ).json(SpotDto.serializer())
        return Scene(crew, spot)
    }

    private suspend fun Rider.offer(
        scene: Scene,
        plan: CreateRideRequestDto = saturday,
        idempotencyKey: String? = null,
    ): HttpResponse =
        call(
            ApiRoute.CREATE_RIDE,
            ContractJson.encodeToString(CreateRideRequestDto.serializer(), plan.copy(spotId = plan.spotId.ifEmpty { scene.spot.id })),
            idempotencyKey,
            scene.crew.id,
        )

    private suspend fun Rider.offered(
        scene: Scene,
        plan: CreateRideRequestDto = saturday,
    ): RideDto = offer(scene, plan).json(RideDto.serializer())

    private suspend fun Rider.ride(rideId: String): HttpResponse = call(ApiRoute.GET_RIDE, null, null, rideId)

    private suspend fun Rider.rides(
        crewId: String,
        query: String? = null,
    ): HttpResponse = call(ApiRoute.LIST_CREW_RIDES, null, null, crewId, query = query)

    private suspend fun Rider.cancel(rideId: String): HttpResponse = call(ApiRoute.CANCEL_RIDE, null, null, rideId)

    private suspend fun Rider.takeSeat(
        rideId: String,
        idempotencyKey: String? = null,
    ): HttpResponse = call(ApiRoute.TAKE_SEAT, null, idempotencyKey, rideId)

    private suspend fun Rider.releaseSeat(rideId: String): HttpResponse = call(ApiRoute.RELEASE_SEAT, null, null, rideId)

    /** The events of the crew's feed, with their ids, which a test cannot know, taken out of the way. */
    private suspend fun Rider.events(crewId: String): List<CrewEventDto> =
        call(ApiRoute.GET_CREW_FEED, null, null, crewId).json(CrewFeedDto.serializer()).items.map {
            when (it) {
                is RideCreatedEventDto -> it.copy(id = "")
                is SeatTakenEventDto -> it.copy(id = "")
                is SeatReleasedEventDto -> it.copy(id = "")
                is RideCancelledEventDto -> it.copy(id = "")
                else -> it
            }
        }

    private suspend fun HttpResponse.assertProblem(
        status: HttpStatusCode,
        code: String,
        what: String = "",
    ) {
        assertEquals(status, this.status, what)
        assertEquals(code, problem().code, what)
    }

    @Test
    fun `a member offers a ride and drives it`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val scene = scene(alex, dana)
            clock.now += 5.minutes

            val response = dana.offer(scene)

            assertEquals(HttpStatusCode.Created, response.status)
            val ride = response.json(RideDto.serializer())
            assertTrue(ride.id.startsWith("rid_"), ride.id)
            assertEquals(
                RideDto(
                    id = ride.id,
                    version = 1,
                    crewId = scene.crew.id,
                    spotId = scene.spot.id,
                    driver = dana.person,
                    windowStart = saturday.windowStart,
                    windowEnd = saturday.windowEnd,
                    departAt = saturday.departAt,
                    meetingPoint = "Hauptbahnhof, north exit",
                    seatsTotal = 3,
                    seatsFree = 3,
                    passengers = emptyList(),
                    status = RideStatusDto.SCHEDULED,
                    createdAt = clock.now,
                ),
                ride,
            )
            assertEquals(ride, alex.ride(ride.id).json(RideDto.serializer()))
            assertEquals(listOf(ride), alex.rides(scene.crew.id).json(RideListDto.serializer()).items)
            assertEquals(RideCreatedEventDto("", scene.crew.id, clock.now, dana.person, ride), alex.events(scene.crew.id).last())
        }

    @Test
    fun `a ride's plan follows the contract, and its spot is one of the catalog`() =
        serverTest {
            val alex = rider()
            val scene = scene(alex)
            val wrong =
                mapOf(
                    "a window that ends when it starts" to saturday.copy(windowEnd = saturday.windowStart),
                    "a window that ends before it starts" to saturday.copy(windowEnd = saturday.windowStart - 1.hours),
                    "leaving after the window starts" to saturday.copy(departAt = saturday.windowStart + 1.minutes),
                    "no meeting point" to saturday.copy(meetingPoint = ""),
                    "a meeting point of spaces" to saturday.copy(meetingPoint = "   "),
                    "a meeting point too long" to saturday.copy(meetingPoint = "a".repeat(121)),
                    "a meeting point nobody typed" to saturday.copy(meetingPoint = "north\u0000exit"),
                    "no seats" to saturday.copy(seatsTotal = 0),
                    "nine seats" to saturday.copy(seatsTotal = 9),
                    "a spot that is not there" to saturday.copy(spotId = "spt_nowhere"),
                    "a spot that is no id" to saturday.copy(spotId = "nord\u0000strand"),
                    "a window the calendar ends before" to
                        saturday.copy(
                            windowStart = Instant.parse("+12000-01-01T00:00:00Z"),
                            windowEnd = Instant.parse("+12000-01-01T04:00:00Z"),
                        ),
                    "leaving before any calendar" to saturday.copy(departAt = Instant.parse("-0001-01-01T00:00:00Z")),
                    "a window shorter than the server tells moments apart" to
                        saturday.copy(windowEnd = saturday.windowStart + 900.microseconds),
                    "leaving after a start the server keeps as the same moment" to
                        saturday.copy(
                            windowStart = saturday.windowStart + 100.microseconds,
                            departAt =
                                saturday.windowStart + 1.milliseconds,
                        ),
                )

            for ((what, plan) in wrong) {
                alex.offer(scene, plan).assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED, what)
            }
            alex
                .call(
                    ApiRoute.CREATE_RIDE,
                    "{}",
                    null,
                    scene.crew.id,
                ).assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
            assertEquals(emptyList(), alex.rides(scene.crew.id, "from=2000-01-01T00:00:00Z").json(RideListDto.serializer()).items)
            assertEquals(emptyList(), alex.events(scene.crew.id))

            val allowed =
                listOf(
                    saturday.copy(departAt = saturday.windowStart, seatsTotal = 1),
                    saturday.copy(meetingPoint = "🚗".repeat(120), seatsTotal = 8),
                )
            for (plan in allowed) {
                val ride = alex.offered(scene, plan)
                assertEquals(
                    Triple(plan.departAt, plan.meetingPoint, plan.seatsTotal),
                    Triple(ride.departAt, ride.meetingPoint, ride.seatsFree),
                )
            }
            // A moment is kept to the millisecond, and comes back as it is kept.
            val precise = alex.offered(scene, saturday.copy(departAt = Instant.parse("2026-07-18T08:30:00.123456789Z")))
            assertEquals(Instant.parse("2026-07-18T08:30:00.123Z"), precise.departAt)
            assertEquals(precise, alex.ride(precise.id).json(RideDto.serializer()))
        }

    @Test
    fun `the rides of a crew are those whose window ends after a moment, by departure, cancelled ones too`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val scene = scene(alex, dana)
            val sunday = saturday.copy(windowStart = saturday.windowStart + 24.hours, windowEnd = saturday.windowEnd + 24.hours)
            val early = alex.offered(scene, saturday.copy(departAt = saturday.departAt - 2.hours))
            val late = dana.offered(scene, saturday)
            val tomorrow = alex.offered(scene, sunday.copy(departAt = saturday.departAt + 24.hours))
            val over =
                dana.offered(scene, saturday.copy(windowStart = clock.now - 5.hours, windowEnd = clock.now, departAt = clock.now - 8.hours))
            alex.cancel(early.id)

            suspend fun ids(query: String? = null) =
                alex
                    .rides(scene.crew.id, query)
                    .json(RideListDto.serializer())
                    .items
                    .map { it.id }

            assertEquals(listOf(early.id, late.id, tomorrow.id), ids())
            assertEquals(listOf(over.id, early.id, late.id, tomorrow.id), ids("from=${clock.now - 1.minutes}"))
            assertEquals(listOf(tomorrow.id), ids("from=${saturday.windowEnd}"))
            assertEquals(emptyList(), ids("from=${sunday.windowEnd}"))
            assertEquals(emptyList(), ids("from=%2B12000-01-01T00:00:00Z"))
            assertEquals(
                RideStatusDto.CANCELLED,
                alex
                    .rides(scene.crew.id)
                    .json(RideListDto.serializer())
                    .items
                    .first()
                    .status,
            )
            alex.rides(scene.crew.id, "from=saturday").assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
        }

    @Test
    fun `seats are taken in the order riders came, each once, until none is left`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val eli = rider()
            val fay = rider()
            val scene = scene(alex, dana, eli, fay)
            val ride = alex.offered(scene, saturday.copy(seatsTotal = 2))
            clock.now += 1.minutes

            val first = dana.takeSeat(ride.id)
            val second = eli.takeSeat(ride.id)
            val full = fay.takeSeat(ride.id)

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(ride.copy(version = 2, seatsFree = 1, passengers = listOf(dana.person)), first.json(RideDto.serializer()))
            assertEquals(
                ride.copy(version = 3, seatsFree = 0, passengers = listOf(dana.person, eli.person)),
                second.json(RideDto.serializer()),
            )
            assertEquals(HttpStatusCode.Conflict, full.status)
            assertEquals(
                ProblemDto(
                    type = "/problems/seats-full",
                    title = "No seats left",
                    status = 409,
                    code = ProblemCode.SEATS_FULL,
                    detail = "Ride ${ride.id} has no free seats.",
                ),
                full.problem(),
            )
            val twice = dana.takeSeat(ride.id)
            assertEquals(
                ProblemDto(
                    type = "/problems/already-joined",
                    title = "Already in this ride",
                    status = 409,
                    code = ProblemCode.ALREADY_JOINED,
                ),
                twice.problem(),
            )
            alex.takeSeat(ride.id).assertProblem(HttpStatusCode.Conflict, ProblemCode.ALREADY_JOINED, "the driver")
            assertEquals(
                listOf(
                    SeatTakenEventDto("", scene.crew.id, clock.now, dana.person, ride.id, rideVersion = 2, seatsFree = 1),
                    SeatTakenEventDto("", scene.crew.id, clock.now, eli.person, ride.id, rideVersion = 3, seatsFree = 0),
                ),
                alex.events(scene.crew.id).takeLast(2),
            )
            assertEquals(3, fay.ride(ride.id).json(RideDto.serializer()).version)
        }

    @Test
    fun `a seat given back is free again, and giving back a seat one does not have changes nothing`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val eli = rider()
            val scene = scene(alex, dana, eli)
            val ride = alex.offered(scene)
            dana.takeSeat(ride.id)
            eli.takeSeat(ride.id)
            clock.now += 1.minutes

            val released = dana.releaseSeat(ride.id)
            val again = dana.releaseSeat(ride.id)
            val driver = alex.releaseSeat(ride.id)

            val after = ride.copy(version = 4, seatsFree = 2, passengers = listOf(eli.person))
            assertEquals(HttpStatusCode.OK to after, released.status to released.json(RideDto.serializer()))
            assertEquals(HttpStatusCode.OK to after, again.status to again.json(RideDto.serializer()))
            assertEquals(HttpStatusCode.OK to after, driver.status to driver.json(RideDto.serializer()))
            val events = alex.events(scene.crew.id)
            assertEquals(
                SeatReleasedEventDto("", scene.crew.id, clock.now, dana.person, ride.id, rideVersion = 4, seatsFree = 2),
                events.last(),
            )
            assertEquals(1, events.count { it is SeatReleasedEventDto })
            // Who comes back takes the last seat, not the one they had.
            val back = dana.takeSeat(ride.id)
            assertEquals(HttpStatusCode.Created, back.status)
            assertEquals(listOf(eli.person, dana.person), back.json(RideDto.serializer()).passengers)
        }

    @Test
    fun `only the driver calls a ride off, once, and nobody takes a seat in a ride that is off`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val eli = rider()
            val scene = scene(alex, dana, eli)
            val ride = alex.offered(scene)
            dana.takeSeat(ride.id)
            clock.now += 1.minutes

            val notTheirs = dana.cancel(ride.id)
            val cancelled = alex.cancel(ride.id)
            val again = alex.cancel(ride.id)

            assertEquals(HttpStatusCode.Forbidden, notTheirs.status)
            assertEquals(
                ProblemDto(
                    type = "/problems/not-the-driver",
                    title = "Only the driver can cancel this ride",
                    status = 403,
                    code = ProblemCode.NOT_THE_DRIVER,
                ),
                notTheirs.problem(),
            )
            val off = ride.copy(version = 3, seatsFree = 2, passengers = listOf(dana.person), status = RideStatusDto.CANCELLED)
            assertEquals(HttpStatusCode.OK to off, cancelled.status to cancelled.json(RideDto.serializer()))
            assertEquals(HttpStatusCode.OK to off, again.status to again.json(RideDto.serializer()))
            val events = alex.events(scene.crew.id)
            assertEquals(RideCancelledEventDto("", scene.crew.id, clock.now, alex.person, ride.id, rideVersion = 3), events.last())
            assertEquals(1, events.count { it is RideCancelledEventDto })
            val late = eli.takeSeat(ride.id)
            assertEquals(
                ProblemDto(
                    type = "/problems/ride-cancelled",
                    title = "The ride was cancelled",
                    status = 409,
                    code = ProblemCode.RIDE_CANCELLED,
                ),
                late.problem(),
            )
            // That the ride is off is said before anything about the caller.
            dana.takeSeat(ride.id).assertProblem(HttpStatusCode.Conflict, ProblemCode.RIDE_CANCELLED)
            // A seat in it can still be given back, as the app's in-app backend lets it.
            assertEquals(
                off.copy(version = 4, seatsFree = 3, passengers = emptyList()),
                dana.releaseSeat(ride.id).json(RideDto.serializer()),
            )
        }

    @Test
    fun `rides are for the members of their crew, and a ride or a crew that is not there is not found`() =
        serverTest {
            val alex = rider()
            val stranger = rider()
            val scene = scene(alex)
            val ride = alex.offered(scene)

            val refused =
                listOf(
                    stranger.rides(scene.crew.id),
                    stranger.offer(scene),
                    // Who is not in the crew is told so before anything about the call itself.
                    stranger.call(ApiRoute.CREATE_RIDE, "{}", null, scene.crew.id),
                    stranger.rides(scene.crew.id, "from=saturday"),
                    stranger.ride(ride.id),
                    stranger.cancel(ride.id),
                    stranger.takeSeat(ride.id),
                    stranger.releaseSeat(ride.id),
                )
            for ((index, response) in refused.withIndex()) {
                response.assertProblem(HttpStatusCode.Forbidden, ProblemCode.NOT_A_MEMBER, "call $index")
            }
            val missing = alex.ride("rid_nowhere")
            assertEquals(HttpStatusCode.NotFound to "Ride rid_nowhere does not exist.", missing.status to missing.problem().detail)
            for (response in listOf(alex.cancel("rid_nowhere"), alex.takeSeat("rid_nowhere"), alex.releaseSeat("rid_nowhere"))) {
                response.assertProblem(HttpStatusCode.NotFound, ProblemCode.NOT_FOUND)
            }
            for (response in listOf(alex.rides("crw_nowhere"), alex.call(ApiRoute.CREATE_RIDE, "{}", null, "crw_nowhere"))) {
                response.assertProblem(HttpStatusCode.NotFound, ProblemCode.NOT_FOUND)
            }
            alex.ride("rid.1").assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
            for (operation in listOf(ApiRoute.GET_RIDE, ApiRoute.CANCEL_RIDE, ApiRoute.TAKE_SEAT, ApiRoute.RELEASE_SEAT)) {
                val response = client.request(operation.pathWith(ride.id)) { method = HttpMethod.parse(operation.method) }
                assertEquals(HttpStatusCode.Unauthorized, response.status, operation.name)
            }
            assertEquals(ride, alex.ride(ride.id).json(RideDto.serializer()))
        }

    @Test
    fun `of riders after the last seats at the same moment, as many get one as there are seats`() =
        serverTest {
            val alex = rider()
            val riders = List(5) { rider() }
            val scene = scene(alex, *riders.toTypedArray())
            val ride = alex.offered(scene, saturday.copy(seatsTotal = 2))
            // A change to a ride holds its crew: behind the test's hold on it the five line up, and then go one by one.
            val theCrew = "select 1 from ${Database.SCHEMA}.crews where id = '${scene.crew.id}' for no key update"

            val answers = together(theCrew, riders.map { rider -> suspend { rider.takeSeat(ride.id) } })

            assertEquals(mapOf(HttpStatusCode.Created to 2, HttpStatusCode.Conflict to 3), answers.groupingBy { it.status }.eachCount())
            assertTrue(answers.filter { it.status == HttpStatusCode.Conflict }.all { it.problem().code == ProblemCode.SEATS_FULL })
            val after = alex.ride(ride.id).json(RideDto.serializer())
            assertEquals(Triple(3, 0, 2), Triple(after.version, after.seatsFree, after.passengers.toSet().size))
            val taken = alex.events(scene.crew.id).filterIsInstance<SeatTakenEventDto>()
            assertEquals(listOf(2 to 1, 3 to 0), taken.map { it.rideVersion to it.seatsFree })
            assertEquals(after.passengers, taken.map { it.actor })
        }

    @Test
    fun `rides that leave at the same moment are listed in the order they were offered`() =
        serverTest {
            val alex = rider()
            val scene = scene(alex)

            val offered = List(6) { alex.offered(scene, saturday.copy(meetingPoint = "Car $it")) }

            assertEquals(
                offered.map { it.id },
                alex
                    .rides(scene.crew.id)
                    .json(RideListDto.serializer())
                    .items
                    .map { it.id },
            )
        }

    @Test
    fun `a change to a ride with a key that was used already happens once`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val scene = scene(alex, dana)

            val offered = alex.offer(scene, idempotencyKey = key)
            val offeredAgain = alex.offer(scene, idempotencyKey = key)
            val ride = offered.json(RideDto.serializer())
            val taken = dana.takeSeat(ride.id, key)
            val takenAgain = dana.takeSeat(ride.id, key)

            assertEquals(HttpStatusCode.Created to offered.bodyAsText(), offeredAgain.status to offeredAgain.bodyAsText())
            assertEquals("true", offeredAgain.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(
                1,
                alex
                    .rides(scene.crew.id)
                    .json(RideListDto.serializer())
                    .items.size,
            )
            assertEquals(HttpStatusCode.Created to taken.bodyAsText(), takenAgain.status to takenAgain.bodyAsText())
            assertEquals("true", takenAgain.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(2, alex.ride(ride.id).json(RideDto.serializer()).version)
            assertEquals(2, alex.events(scene.crew.id).count { it is RideCreatedEventDto || it is SeatTakenEventDto })
        }

    @Test
    fun `a ride shows people under the names they have now, the feed under the names they had`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val scene = scene(alex, dana)
            val ride = alex.offered(scene)
            dana.takeSeat(ride.id)
            val profile =
                """{"displayName":"%s","weightKg":78.5,"level":"INTERMEDIATE","quiverM2":[9,12],"minSessionMinutes":90,""" +
                    """"maxDriveMinutes":120,"ridingFromHour":7,"ridingToHour":20,"windUnit":"KNOTS"}"""

            assertEquals(HttpStatusCode.OK, alex.call(ApiRoute.PUT_PROFILE, profile.format("Alex")).status)
            assertEquals(HttpStatusCode.OK, dana.call(ApiRoute.PUT_PROFILE, profile.format("Dana")).status)

            val now = dana.ride(ride.id).json(RideDto.serializer())
            assertEquals(PersonDto(alex.userId, "Alex") to listOf(PersonDto(dana.userId, "Dana")), now.driver to now.passengers)
            val told = dana.events(scene.crew.id)
            assertEquals(alex.person, (told.first { it is RideCreatedEventDto } as RideCreatedEventDto).ride.driver)
            assertEquals(dana.person, told.last().actor)
        }
}
