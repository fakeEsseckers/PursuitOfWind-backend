package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiLimits
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.CreateSpotRequestDto
import io.github.esseckers.pursuitofwind.contract.MySpotDto
import io.github.esseckers.pursuitofwind.contract.MySpotListDto
import io.github.esseckers.pursuitofwind.contract.MySpotUpdateDto
import io.github.esseckers.pursuitofwind.contract.SpotDto
import io.github.esseckers.pursuitofwind.contract.SpotPageDto
import io.github.esseckers.pursuitofwind.contract.SpotUpdateDto
import io.github.esseckers.pursuitofwind.server.spot.SpotRules
import io.github.esseckers.pursuitofwind.server.spot.Spots
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.Route

private const val SPOT_ID = "spotId"
private const val CURSOR_PREFIX = "c_"
private const val DEFAULT_LIMIT = 50
private val LIMITS = 1..ApiLimits.Paging.LIMIT_MAX

/** The catalog of spots and the rider's own list (the contract's tag `Spots`, without the forecast). */
internal fun Route.spotRoutes(api: Api) {
    signedIn(api, ApiRoute.LIST_SPOT_CATALOG) {
        val limit = intQuery(call.parameters, "limit", LIMITS, DEFAULT_LIMIT)
        val after = cursor(call.parameters)
        // One more than asked for says whether another page follows.
        val found = api.database.read { Spots.page(it, after, limit + 1) }
        val page = found.take(limit)
        call.respondJson(SpotPageDto.serializer(), SpotPageDto(page, if (found.size > limit) CURSOR_PREFIX + page.last().id else null))
    }
    changing(api, ApiRoute.CREATE_SPOT) {
        val request = body(CreateSpotRequestDto.serializer())
        SpotRules.check(request.name, request.location, request.waterBearingDeg, request.timeZone)
        Answer.json(SpotDto.serializer(), Spots.create(handle, userId, request, now), HttpStatusCode.Created)
    }
    changing(api, ApiRoute.UPDATE_SPOT) { updateSpot() }
    signedIn(api, ApiRoute.LIST_MY_SPOTS) { userId ->
        call.respondJson(MySpotListDto.serializer(), MySpotListDto(api.database.read { Spots.followed(it, userId) }))
    }
    changing(api, ApiRoute.PUT_MY_SPOT) {
        val spot = Spots.find(handle, parameters[SPOT_ID].orEmpty()) ?: throw notFound("Spot ${parameters[SPOT_ID]}")
        val settings = body(MySpotUpdateDto.serializer())
        SpotRules.checkDrive(settings.driveMinutes)
        Spots.follow(handle, userId, spot.id, settings)
        Answer.json(MySpotDto.serializer(), MySpotDto(spot, settings.driveMinutes, settings.favorite))
    }
    changing(api, ApiRoute.DELETE_MY_SPOT) {
        Spots.unfollow(handle, userId, parameters[SPOT_ID].orEmpty())
        Answer.noContent
    }
}

/** Only the rider who added a spot may change it; the spot is held from the check to the change. */
private fun Change.updateSpot(): Answer {
    val id = parameters[SPOT_ID].orEmpty()
    val spot = Spots.find(handle, id, forChange = true) ?: throw notFound("Spot $id")
    if (spot.createdBy != userId) throw notTheCreator()
    val update = body(SpotUpdateDto.serializer())
    SpotRules.check(update.name, update.location, update.waterBearingDeg)
    return Answer.json(SpotDto.serializer(), Spots.update(handle, id, update))
}

/** The id the page starts after: the `cursor` the last page gave, or null for the first page. */
private fun cursor(parameters: Parameters): String? =
    parameters["cursor"]?.let { cursor ->
        check422(cursor.startsWith(CURSOR_PREFIX) && isId(cursor.removePrefix(CURSOR_PREFIX))) { "cursor is not a cursor from this API." }
        cursor.removePrefix(CURSOR_PREFIX)
    }
