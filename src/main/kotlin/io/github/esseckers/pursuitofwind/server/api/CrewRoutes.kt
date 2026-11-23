package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiLimits
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.CreateCrewRequestDto
import io.github.esseckers.pursuitofwind.contract.CrewDto
import io.github.esseckers.pursuitofwind.contract.CrewFeedDto
import io.github.esseckers.pursuitofwind.contract.CrewListDto
import io.github.esseckers.pursuitofwind.contract.CrewMemberListDto
import io.github.esseckers.pursuitofwind.contract.JoinCrewRequestDto
import io.github.esseckers.pursuitofwind.server.crew.CrewFeed
import io.github.esseckers.pursuitofwind.server.crew.CrewRules
import io.github.esseckers.pursuitofwind.server.crew.Crews
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route

private const val CREW_ID = "crewId"
private const val DEFAULT_LIMIT = 50
private val LIMITS = 1..ApiLimits.Paging.LIMIT_MAX

/** Crews, their members and their feed (the contract's tag `Crews`). */
internal fun Route.crewRoutes(api: Api) {
    changing(api, ApiRoute.CREATE_CREW) {
        val name = body(CreateCrewRequestDto.serializer()).name
        CrewRules.checkName(name)
        Answer.json(CrewDto.serializer(), Crews.create(handle, userId, name, now), HttpStatusCode.Created)
    }
    signedIn(api, ApiRoute.LIST_MY_CREWS) { userId ->
        call.respondJson(CrewListDto.serializer(), CrewListDto(api.database.read { Crews.of(it, userId) }))
    }
    changing(api, ApiRoute.JOIN_CREW) {
        val typed = body(JoinCrewRequestDto.serializer()).inviteCode
        check422(typed.isNotEmpty()) { "inviteCode must not be empty." }
        val crew = CrewRules.inviteCode(typed)?.let { Crews.join(handle, it, userId, now) } ?: throw inviteInvalid()
        Answer.json(CrewDto.serializer(), crew)
    }
    signedIn(api, ApiRoute.LIST_CREW_MEMBERS) { userId ->
        val id = call.parameters[CREW_ID].orEmpty()
        val members =
            api.database.read { handle ->
                Crews.ofMember(handle, id, userId)
                Crews.members(handle, id)
            }
        call.respondJson(CrewMemberListDto.serializer(), CrewMemberListDto(members))
    }
    signedIn(api, ApiRoute.GET_CREW_FEED) { userId ->
        val id = call.parameters[CREW_ID].orEmpty()
        val since = call.parameters["since"]
        val feed =
            api.database.read { handle ->
                Crews.ofMember(handle, id, userId)
                val limit = intQuery(call.parameters, "limit", LIMITS, DEFAULT_LIMIT)
                if (since == null) CrewFeed.newest(handle, id, limit) else CrewFeed.after(handle, id, since, limit)
            }
        call.respondJson(CrewFeedDto.serializer(), feed)
    }
}
