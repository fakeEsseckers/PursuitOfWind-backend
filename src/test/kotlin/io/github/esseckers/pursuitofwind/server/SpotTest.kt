package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiHeaders
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.CreateSpotRequestDto
import io.github.esseckers.pursuitofwind.contract.GeoPointDto
import io.github.esseckers.pursuitofwind.contract.MySpotDto
import io.github.esseckers.pursuitofwind.contract.MySpotListDto
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.contract.SectorDto
import io.github.esseckers.pursuitofwind.contract.SpotDto
import io.github.esseckers.pursuitofwind.contract.SpotPageDto
import io.github.esseckers.pursuitofwind.contract.SpotUpdateDto
import io.github.esseckers.pursuitofwind.engine.DecisionEngine
import io.github.esseckers.pursuitofwind.engine.model.Bearing
import io.github.esseckers.pursuitofwind.server.testing.Rider
import io.github.esseckers.pursuitofwind.server.testing.json
import io.github.esseckers.pursuitofwind.server.testing.problem
import io.github.esseckers.pursuitofwind.server.testing.rider
import io.github.esseckers.pursuitofwind.server.testing.serverTest
import io.github.esseckers.pursuitofwind.server.testing.signedInAgain
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** The catalog of spots and a rider's own list, through HTTP on a real database. */
class SpotTest {
    private val bay =
        CreateSpotRequestDto("Nordstrand Bay", GeoPointDto(54.4936, 8.8131), waterBearingDeg = 240.0, timeZone = "Europe/Berlin")
    private val key = "3f9d6c1e-8a42-4f4b-9d0e-6b1c2a7e5f10"

    private suspend fun Rider.add(
        spot: CreateSpotRequestDto = bay,
        idempotencyKey: String? = null,
    ): HttpResponse = call(ApiRoute.CREATE_SPOT, ContractJson.encodeToString(CreateSpotRequestDto.serializer(), spot), idempotencyKey)

    private suspend fun Rider.added(spot: CreateSpotRequestDto = bay): SpotDto = add(spot).json(SpotDto.serializer())

    private suspend fun Rider.change(
        spotId: String,
        update: SpotUpdateDto,
    ): HttpResponse = call(ApiRoute.UPDATE_SPOT, ContractJson.encodeToString(SpotUpdateDto.serializer(), update), null, spotId)

    private suspend fun Rider.catalog(query: String? = null): HttpResponse = call(ApiRoute.LIST_SPOT_CATALOG, query = query)

    private suspend fun Rider.follow(
        spotId: String,
        driveMinutes: Int = 45,
        favorite: Boolean = false,
    ): HttpResponse = call(ApiRoute.PUT_MY_SPOT, """{"driveMinutes":$driveMinutes,"favorite":$favorite}""", null, spotId)

    private suspend fun Rider.mine(): List<MySpotDto> = call(ApiRoute.LIST_MY_SPOTS).json(MySpotListDto.serializer()).items

    private fun sectorsFacing(degrees: Double): Pair<List<SectorDto>, List<SectorDto>> {
        val sectors = DecisionEngine.sectorsFacing(Bearing(degrees))
        return listOf(SectorDto(sectors.rideable.from.degrees, sectors.rideable.to.degrees)) to
            listOf(SectorDto(sectors.danger.from.degrees, sectors.danger.to.degrees))
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
    fun `a rider adds a spot, with the sectors the decision engine gives a beach facing that way`() =
        serverTest {
            val rider = rider()

            val response = rider.add()

            assertEquals(HttpStatusCode.Created, response.status)
            val spot = response.json(SpotDto.serializer())
            assertTrue(Regex("spt_[A-Za-z0-9_-]+").matches(spot.id), spot.id)
            assertEquals(
                SpotDto(
                    id = spot.id,
                    name = "Nordstrand Bay",
                    location = GeoPointDto(54.4936, 8.8131),
                    timeZone = "Europe/Berlin",
                    rideableSectors = sectorsFacing(240.0).first,
                    dangerSectors = sectorsFacing(240.0).second,
                    waterBearingDeg = 240.0,
                    createdBy = rider.userId,
                    createdAt = clock.now,
                ),
                spot,
            )
        }

    @Test
    fun `the catalog is everyone's and comes in pages, in the order of the ids`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            assertEquals(SpotPageDto(emptyList()), dana.catalog().json(SpotPageDto.serializer()))
            val added = List(5) { alex.added(bay.copy(name = "Spot $it")) }.sortedBy { it.id }

            val first = dana.catalog("limit=2").json(SpotPageDto.serializer())
            val second = dana.catalog("limit=2&cursor=${first.nextCursor}").json(SpotPageDto.serializer())
            val third = dana.catalog("limit=2&cursor=${second.nextCursor}").json(SpotPageDto.serializer())

            assertEquals(added, first.items + second.items + third.items)
            assertEquals(listOf(2, 2, 1), listOf(first, second, third).map { it.items.size })
            assertNull(third.nextCursor)
            assertEquals(SpotPageDto(added), dana.catalog().json(SpotPageDto.serializer()))
            assertNull(dana.catalog("limit=5").json(SpotPageDto.serializer()).nextCursor)
        }

    @Test
    fun `a page the contract does not allow is a validation problem`() =
        serverTest {
            val rider = rider()

            for (query in listOf("limit=0", "limit=101", "limit=many", "cursor=not-a-cursor")) {
                rider.catalog(query).assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED, query)
            }
            assertEquals(HttpStatusCode.OK, rider.catalog("limit=100").status)
        }

    @Test
    fun `the rider who added a spot renames it, moves it and turns its water, and it stays theirs from the day it was added`() =
        serverTest {
            val spot = rider().let { it.added() to it }
            clock.now += 3.days
            val rider = signedInAgain(spot.second)

            val changed = rider.change(spot.first.id, SpotUpdateDto("Nordstrand North", GeoPointDto(54.5, 8.8), waterBearingDeg = 300.0))

            assertEquals(HttpStatusCode.OK, changed.status)
            val expected =
                spot.first.copy(
                    name = "Nordstrand North",
                    location = GeoPointDto(54.5, 8.8),
                    waterBearingDeg = 300.0,
                    rideableSectors = sectorsFacing(300.0).first,
                    dangerSectors = sectorsFacing(300.0).second,
                )
            assertEquals(expected, changed.json(SpotDto.serializer()))
            assertEquals(listOf(expected), rider.catalog().json(SpotPageDto.serializer()).items)
        }

    @Test
    fun `nobody else changes a spot, and nobody changes one that is not there`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val spot = alex.added()
            val update = SpotUpdateDto("Mine now", spot.location, spot.waterBearingDeg)

            dana.change(spot.id, update).assertProblem(HttpStatusCode.Forbidden, ProblemCode.NOT_THE_CREATOR)
            dana.call(ApiRoute.UPDATE_SPOT, "not json", null, spot.id).assertProblem(HttpStatusCode.Forbidden, ProblemCode.NOT_THE_CREATOR)
            alex.change("spt_nowhere", update).assertProblem(HttpStatusCode.NotFound, ProblemCode.NOT_FOUND)

            assertEquals(listOf(spot), alex.catalog().json(SpotPageDto.serializer()).items)
        }

    @Test
    fun `a spot outside the contract's limits is a validation problem and is not added`() =
        serverTest {
            val rider = rider()
            val outside =
                mapOf(
                    "a name of spaces" to bay.copy(name = "   "),
                    "a name of 41 characters" to bay.copy(name = "a".repeat(41)),
                    "a latitude past the pole" to bay.copy(location = GeoPointDto(90.1, 8.0)),
                    "a longitude past the date line" to bay.copy(location = GeoPointDto(54.0, -180.1)),
                    "a full turn" to bay.copy(waterBearingDeg = 360.0),
                    "a negative bearing" to bay.copy(waterBearingDeg = -0.1),
                    "an offset for a time zone" to bay.copy(timeZone = "+02:00"),
                    "a zone that is not a region" to bay.copy(timeZone = "GMT+1"),
                    "a zone nobody knows" to bay.copy(timeZone = "Mars/Olympus"),
                )

            for ((what, spot) in outside) {
                rider
                    .add(
                        spot,
                    ).assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED, what)
            }
            rider.call(ApiRoute.CREATE_SPOT, """{"name":"No place"}""").assertProblem(
                HttpStatusCode.UnprocessableEntity,
                ProblemCode.VALIDATION_FAILED,
            )

            assertEquals(emptyList(), rider.catalog().json(SpotPageDto.serializer()).items)
            assertEquals(HttpStatusCode.Created, rider.add(bay.copy(timeZone = "UTC", waterBearingDeg = 0.0)).status)
        }

    @Test
    fun `a change outside the limits is a validation problem and changes nothing`() =
        serverTest {
            val rider = rider()
            val spot = rider.added()

            rider
                .change(spot.id, SpotUpdateDto(" ", spot.location, spot.waterBearingDeg))
                .assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
            rider
                .change(spot.id, SpotUpdateDto(spot.name, spot.location, 400.0))
                .assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)

            assertEquals(listOf(spot), rider.catalog().json(SpotPageDto.serializer()).items)
        }

    @Test
    fun `a spot added twice with one key is one spot`() =
        serverTest {
            val rider = rider()

            val first = rider.add(idempotencyKey = key)
            val again = rider.add(idempotencyKey = key)

            assertEquals(HttpStatusCode.Created to "true", again.status to again.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(first.json(SpotDto.serializer()), again.json(SpotDto.serializer()))
            assertEquals(
                1,
                rider
                    .catalog()
                    .json(SpotPageDto.serializer())
                    .items.size,
            )
        }

    @Test
    fun `a rider follows spots with their own drive and favourites, and the list is theirs`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val spots = List(3) { alex.added(bay.copy(name = "Spot $it")) }.sortedBy { it.id }
            assertEquals(emptyList(), alex.mine())

            val followed = alex.follow(spots[2].id, driveMinutes = 90, favorite = true)
            alex.follow(spots[0].id)
            dana.follow(spots[1].id, driveMinutes = 0)

            assertEquals(HttpStatusCode.OK, followed.status)
            assertEquals(MySpotDto(spots[2], 90, true), followed.json(MySpotDto.serializer()))
            assertEquals(listOf(MySpotDto(spots[0], 45, false), MySpotDto(spots[2], 90, true)), alex.mine())
            assertEquals(listOf(MySpotDto(spots[1], 0, false)), dana.mine())

            alex.follow(spots[2].id, driveMinutes = 30, favorite = false)
            assertEquals(listOf(MySpotDto(spots[0], 45, false), MySpotDto(spots[2], 30, false)), alex.mine())
        }

    @Test
    fun `a followed spot shows what its owner changed about it`() =
        serverTest {
            val alex = rider()
            val dana = rider()
            val spot = alex.added()
            dana.follow(spot.id)

            alex.change(spot.id, SpotUpdateDto("Renamed", spot.location, spot.waterBearingDeg))

            assertEquals(
                "Renamed",
                dana
                    .mine()
                    .single()
                    .spot.name,
            )
        }

    @Test
    fun `a rider stops following a spot, and doing it twice is no problem`() =
        serverTest {
            val rider = rider()
            val spot = rider.added()
            rider.follow(spot.id)

            val first = rider.call(ApiRoute.DELETE_MY_SPOT, null, null, spot.id)
            val second = rider.call(ApiRoute.DELETE_MY_SPOT, null, null, spot.id)

            assertEquals(listOf(HttpStatusCode.NoContent, HttpStatusCode.NoContent), listOf(first.status, second.status))
            assertEquals(emptyList(), rider.mine())
            assertEquals(
                1,
                rider
                    .catalog()
                    .json(SpotPageDto.serializer())
                    .items.size,
            )
        }

    @Test
    fun `following a spot that is not there, or with a drive the contract does not allow, is refused`() =
        serverTest {
            val rider = rider()
            val spot = rider.added()

            rider.follow("spt_nowhere").assertProblem(HttpStatusCode.NotFound, ProblemCode.NOT_FOUND)
            rider.follow(spot.id, driveMinutes = -1).assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
            rider.follow(spot.id, driveMinutes = 1441).assertProblem(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED)
            assertEquals(HttpStatusCode.OK, rider.follow(spot.id, driveMinutes = 1440).status)
        }

    @Test
    fun `spots are for signed-in riders only`() =
        serverTest {
            val operations =
                listOf(
                    ApiRoute.LIST_SPOT_CATALOG,
                    ApiRoute.CREATE_SPOT,
                    ApiRoute.UPDATE_SPOT,
                    ApiRoute.LIST_MY_SPOTS,
                    ApiRoute.PUT_MY_SPOT,
                    ApiRoute.DELETE_MY_SPOT,
                )

            for (operation in operations) {
                val path = if (operation.pathParameters.isEmpty()) operation.path else operation.pathWith("spt_any")
                val response = client.request(path) { method = HttpMethod.parse(operation.method) }

                assertEquals(HttpStatusCode.Unauthorized, response.status, operation.name)
            }
        }
}
