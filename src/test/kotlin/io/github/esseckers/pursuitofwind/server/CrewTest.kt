package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiHeaders
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.CreateCrewRequestDto
import io.github.esseckers.pursuitofwind.contract.CrewDto
import io.github.esseckers.pursuitofwind.contract.CrewFeedDto
import io.github.esseckers.pursuitofwind.contract.CrewListDto
import io.github.esseckers.pursuitofwind.contract.CrewMemberDto
import io.github.esseckers.pursuitofwind.contract.CrewMemberListDto
import io.github.esseckers.pursuitofwind.contract.JoinCrewRequestDto
import io.github.esseckers.pursuitofwind.contract.MemberJoinedEventDto
import io.github.esseckers.pursuitofwind.contract.MemberRoleDto
import io.github.esseckers.pursuitofwind.contract.PersonDto
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.contract.ProblemDto
import io.github.esseckers.pursuitofwind.contract.RiderProfileDto
import io.github.esseckers.pursuitofwind.contract.SkillLevelDto
import io.github.esseckers.pursuitofwind.contract.WindUnitDto
import io.github.esseckers.pursuitofwind.server.testing.Rider
import io.github.esseckers.pursuitofwind.server.testing.TestServer
import io.github.esseckers.pursuitofwind.server.testing.json
import io.github.esseckers.pursuitofwind.server.testing.problem
import io.github.esseckers.pursuitofwind.server.testing.rider
import io.github.esseckers.pursuitofwind.server.testing.serverTest
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Crews, their members and their feed, through HTTP on a real database. */
class CrewTest {
    private val key = "3f9d6c1e-8a42-4f4b-9d0e-6b1c2a7e5f10"

    /** The codes this server gives out: `WIND-`, then two groups of four from letters and digits that sound unlike. */
    private val inviteCode = Regex("WIND-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}")

    /** The name the server gives a rider who has not saved a profile. */
    private val Rider.unnamed: String get() = "Rider " + userId.takeLast(4)

    private suspend fun Rider.create(
        name: String = "Weekend Crew",
        idempotencyKey: String? = null,
    ): HttpResponse =
        call(
            ApiRoute.CREATE_CREW,
            ContractJson.encodeToString(CreateCrewRequestDto.serializer(), CreateCrewRequestDto(name)),
            idempotencyKey,
        )

    private suspend fun Rider.created(name: String = "Weekend Crew"): CrewDto = create(name).json(CrewDto.serializer())

    private suspend fun Rider.join(
        code: String,
        idempotencyKey: String? = null,
    ): HttpResponse =
        call(ApiRoute.JOIN_CREW, ContractJson.encodeToString(JoinCrewRequestDto.serializer(), JoinCrewRequestDto(code)), idempotencyKey)

    private suspend fun Rider.crews(): List<CrewDto> = call(ApiRoute.LIST_MY_CREWS).json(CrewListDto.serializer()).items

    private suspend fun Rider.members(crewId: String): HttpResponse = call(ApiRoute.LIST_CREW_MEMBERS, null, null, crewId)

    private suspend fun Rider.memberList(crewId: String): List<CrewMemberDto> = members(crewId).json(CrewMemberListDto.serializer()).items

    private suspend fun Rider.feed(
        crewId: String,
        query: String? = null,
    ): HttpResponse = call(ApiRoute.GET_CREW_FEED, null, null, crewId, query = query)

    private suspend fun Rider.feedPage(
        crewId: String,
        query: String? = null,
    ): CrewFeedDto = feed(crewId, query).json(CrewFeedDto.serializer())

    /** Saves a profile under [name], so that others see the rider by it. */
    private suspend fun Rider.named(name: String): Rider {
        val profile =
            RiderProfileDto(
                displayName = name,
                weightKg = 78.5,
                level = SkillLevelDto.INTERMEDIATE,
                quiverM2 = listOf(9.0, 12.0),
                minSessionMinutes = 90,
                maxDriveMinutes = 120,
                ridingFromHour = 7,
                ridingToHour = 20,
                windUnit = WindUnitDto.KNOTS,
            )
        assertEquals(
            HttpStatusCode.OK,
            call(ApiRoute.PUT_PROFILE, ContractJson.encodeToString(RiderProfileDto.serializer(), profile)).status,
        )
        return this
    }

    /** A crew of [owner] with [others] in it, joined in that order a minute apart. */
    private suspend fun TestServer.crewOf(
        owner: Rider,
        vararg others: Rider,
    ): CrewDto {
        val crew = owner.created()
        for (other in others) {
            clock.now += 1.minutes
            assertEquals(HttpStatusCode.OK, other.join(crew.inviteCode).status)
        }
        return crew
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
    fun `a rider who creates a crew is its owner and its only member`() =
        serverTest {
            val alex = rider()

            val response = alex.create("Weekend Crew")

            assertEquals(HttpStatusCode.Created, response.status)
            val crew = response.json(CrewDto.serializer())
            assertEquals(Triple("Weekend Crew", 1, clock.now), Triple(crew.name, crew.memberCount, crew.createdAt))
            assertTrue(crew.id.startsWith("crw_") && inviteCode.matches(crew.inviteCode), "$crew")
            assertEquals(listOf(crew), alex.crews())
            assertEquals(listOf(CrewMemberDto(alex.userId, alex.unnamed, MemberRoleDto.OWNER, clock.now)), alex.memberList(crew.id))
            assertEquals(CrewFeedDto(emptyList(), hasMore = false), alex.feedPage(crew.id))
        }

    @Test
    fun `a crew's name is a line of text of the contract's length`() =
        serverTest {
            val alex = rider()

            for (name in listOf("", "   ", " ", "a".repeat(41), "Week\u0000end")) {
                alex.create(name).assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED, "'$name'")
            }
            alex.call(ApiRoute.CREATE_CREW, "{}").assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
            assertEquals(emptyList(), alex.crews())

            for (name in listOf("W", "🪁".repeat(40), "  Weekend Crew ")) {
                assertEquals(name, alex.created(name).name)
            }
        }

    @Test
    fun `a rider joins with the invite code however it is typed, and joining again changes nothing`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val crew = alex.created()
            clock.now += 5.minutes

            val joined = dana.join("  " + crew.inviteCode.lowercase() + "\n")
            val again = dana.join(crew.inviteCode)

            assertEquals(HttpStatusCode.OK, joined.status)
            assertEquals(crew.copy(memberCount = 2), joined.json(CrewDto.serializer()))
            assertEquals(crew.copy(memberCount = 2), again.json(CrewDto.serializer()))
            assertEquals(crew.copy(memberCount = 2), alex.join(crew.inviteCode).json(CrewDto.serializer()))
            assertEquals(listOf(crew.copy(memberCount = 2)), dana.crews())
            assertEquals(
                listOf(
                    CrewMemberDto(alex.userId, alex.unnamed, MemberRoleDto.OWNER, crew.createdAt),
                    CrewMemberDto(dana.userId, dana.unnamed, MemberRoleDto.MEMBER, clock.now),
                ),
                dana.memberList(crew.id),
            )
            assertEquals(1, alex.feedPage(crew.id).items.size)
        }

    @Test
    fun `a code no crew has is not valid, and nothing at all is not a code`() =
        serverTest {
            val alex = rider()
            alex.created()

            val unknown = alex.join("WIND-AAAA-AAAA")

            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals(
                ProblemDto(
                    type = "/problems/invite-invalid",
                    title = "Invite code is not valid",
                    status = 404,
                    code = ProblemCode.INVITE_INVALID,
                ),
                unknown.problem(),
            )
            for (code in listOf("   ", "WIND\u0000AAAA", "WIND-AAAA-AAAA-AAAA-AAAA", "WIND AAAA", "' or 1=1 --")) {
                alex.join(code).assertProblem(HttpStatusCode.NotFound, ProblemCode.INVITE_INVALID, "'$code'")
            }
            alex.join("").assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
            alex.call(ApiRoute.JOIN_CREW, "{}").assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
        }

    @Test
    fun `a rider's crews are their own, oldest first, each with a code of its own`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val first = alex.created("First")
            clock.now += 1.minutes
            val second = dana.created("Second")
            clock.now += 1.minutes
            val third = alex.created("Third")
            alex.join(second.inviteCode)

            assertEquals(listOf("First", "Second", "Third"), alex.crews().map { it.name })
            assertEquals(listOf(second.copy(memberCount = 2)), dana.crews())
            assertEquals(emptyList(), rider().crews())
            assertEquals(3, setOf(first.inviteCode, second.inviteCode, third.inviteCode).size)
        }

    @Test
    fun `members and feed are for members, and a crew that is not there is not found`() =
        serverTest {
            val alex = rider()
            val stranger = rider()
            val crew = alex.created()

            val refused = stranger.members(crew.id)

            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals(
                ProblemDto(
                    type = "/problems/not-a-member",
                    title = "Not a member of this crew",
                    status = 403,
                    code = ProblemCode.NOT_A_MEMBER,
                ),
                refused.problem(),
            )
            stranger.feed(crew.id).assertProblem(HttpStatusCode.Forbidden, ProblemCode.NOT_A_MEMBER)
            // Who is not in the crew is told so before anything about the call itself.
            stranger.feed(crew.id, "limit=0").assertProblem(HttpStatusCode.Forbidden, ProblemCode.NOT_A_MEMBER)
            val missing = alex.members("crw_nowhere")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals("Crew crw_nowhere does not exist.", missing.problem().detail)
            alex.feed("crw_nowhere").assertProblem(HttpStatusCode.NotFound, ProblemCode.NOT_FOUND)
            alex.members("crw.1").assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
            for (operation in listOf(ApiRoute.LIST_CREW_MEMBERS, ApiRoute.GET_CREW_FEED)) {
                assertEquals(HttpStatusCode.Unauthorized, client.get(operation.pathWith(crew.id)).status, operation.name)
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoute.LIST_MY_CREWS.path).status)
        }

    @Test
    fun `the feed says who joined, in the order they joined, under the names they had then`() =
        serverTest {
            val alex = rider()
            val dana = rider().named("Dana")
            val eli = rider()
            val crew = alex.created()
            clock.now += 1.minutes
            val danaJoined = clock.now
            dana.join(crew.inviteCode)
            clock.now += 1.minutes
            val eliJoined = clock.now
            eli.join(crew.inviteCode)
            dana.named("Dana K")

            val feed = eli.feedPage(crew.id)

            val ids = feed.items.map { it.id }
            assertEquals(
                listOf(
                    MemberJoinedEventDto(
                        id = ids[0],
                        crewId = crew.id,
                        occurredAt = danaJoined,
                        actor = PersonDto(dana.userId, "Dana"),
                        member = CrewMemberDto(dana.userId, "Dana", MemberRoleDto.MEMBER, danaJoined),
                    ),
                    MemberJoinedEventDto(
                        id = ids[1],
                        crewId = crew.id,
                        occurredAt = eliJoined,
                        actor = PersonDto(eli.userId, eli.unnamed),
                        member = CrewMemberDto(eli.userId, eli.unnamed, MemberRoleDto.MEMBER, eliJoined),
                    ),
                ),
                feed.items,
            )
            assertEquals(false to ids[1], feed.hasMore to feed.lastEventId)
            assertTrue(ids.all { it.startsWith("evt_") } && ids[0] != ids[1], "$ids")
            assertEquals(listOf(alex.unnamed, "Dana K", eli.unnamed), eli.memberList(crew.id).map { it.displayName })
            // On the wire an event says what kind it is.
            assertTrue("\"type\":\"MEMBER_JOINED\"" in eli.feed(crew.id).bodyAsText())
        }

    @Test
    fun `the feed is read in pages from where the reader stopped`() =
        serverTest {
            val alex = rider()
            val crew = crewOf(alex, rider(), rider(), rider(), rider(), rider())
            val all = alex.feedPage(crew.id).items.map { it.id }
            assertEquals(5, all.size)

            val newest = alex.feedPage(crew.id, "limit=2")
            val first = alex.feedPage(crew.id, "since=${all[0]}&limit=2")
            val second = alex.feedPage(crew.id, "since=${first.lastEventId}&limit=2")
            val nothingNew = alex.feedPage(crew.id, "since=${second.lastEventId}")

            assertEquals(Triple(all.takeLast(2), false, all[4]), Triple(newest.items.map { it.id }, newest.hasMore, newest.lastEventId))
            assertEquals(Triple(listOf(all[1], all[2]), true, all[2]), Triple(first.items.map { it.id }, first.hasMore, first.lastEventId))
            assertEquals(
                Triple(listOf(all[3], all[4]), false, all[4]),
                Triple(second.items.map { it.id }, second.hasMore, second.lastEventId),
            )
            assertEquals(CrewFeedDto(emptyList(), hasMore = false), nothingNew)
            assertNull(nothingNew.lastEventId)
        }

    @Test
    fun `a place the feed does not have is gone, and a limit the contract does not allow is refused`() =
        serverTest {
            val alex = rider()
            val crew = crewOf(alex, rider())
            val other = crewOf(alex, rider())
            val elsewhere =
                alex
                    .feedPage(other.id)
                    .items
                    .single()
                    .id

            val gone = alex.feed(crew.id, "since=evt_999999999")

            assertEquals(HttpStatusCode.Gone, gone.status)
            assertEquals(
                ProblemDto(
                    type = "/problems/feed-reset",
                    title = "Feed position is no longer available",
                    status = 410,
                    code = ProblemCode.FEED_RESET,
                ),
                gone.problem(),
            )
            for (since in listOf(elsewhere, "yesterday", "evt_", "evt_-1", "evt_99999999999999999999", "")) {
                alex.feed(crew.id, "since=$since").assertProblem(HttpStatusCode.Gone, ProblemCode.FEED_RESET, "'$since'")
            }
            for (limit in listOf("0", "101", "many")) {
                alex.feed(crew.id, "limit=$limit").assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED, limit)
            }
            assertEquals(1, alex.feedPage(crew.id, "limit=100").items.size)
        }

    @Test
    fun `creating and joining with a key that was used already happen once`() =
        serverTest {
            val alex = rider()
            val dana = rider()

            val created = alex.create(idempotencyKey = key)
            val createdAgain = alex.create(idempotencyKey = key)
            val crew = created.json(CrewDto.serializer())
            val joined = dana.join(crew.inviteCode, key)
            clock.now += 1.minutes
            val joinedAgain = dana.join(crew.inviteCode, key)

            assertEquals(HttpStatusCode.Created to created.bodyAsText(), createdAgain.status to createdAgain.bodyAsText())
            assertEquals(
                null to "true",
                created.headers[ApiHeaders.IDEMPOTENT_REPLAYED] to createdAgain.headers[ApiHeaders.IDEMPOTENT_REPLAYED],
            )
            assertEquals(1, alex.crews().size)
            assertEquals(joined.bodyAsText() to "true", joinedAgain.bodyAsText() to joinedAgain.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(1, alex.feedPage(crew.id).items.size)
        }

    @Test
    fun `riders who join at the same moment each get in once, and the feed has them in the order of the members`() =
        serverTest {
            val alex = rider()
            val crew = alex.created()
            val riders = List(8) { rider() }

            val joined = coroutineScope { riders.flatMap { rider -> List(2) { async { rider.join(crew.inviteCode) } } }.awaitAll() }

            assertEquals(List(16) { HttpStatusCode.OK }, joined.map { it.status })
            val members = alex.memberList(crew.id)
            val events = alex.feedPage(crew.id).items
            assertEquals(9, alex.crews().single().memberCount)
            assertEquals(riders.map { it.userId }.toSet(), members.drop(1).map { it.userId }.toSet())
            assertEquals(members.drop(1).map { it.userId }, events.map { it.actor.userId })
            val numbers = events.map { it.id.removePrefix("evt_").toLong() }
            assertEquals(numbers.sorted(), numbers)
        }
}
