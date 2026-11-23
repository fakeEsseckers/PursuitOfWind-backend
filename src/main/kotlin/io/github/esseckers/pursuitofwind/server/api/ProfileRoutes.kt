package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.contract.RiderProfileDto
import io.github.esseckers.pursuitofwind.server.profile.ProfileRules
import io.github.esseckers.pursuitofwind.server.profile.Profiles
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route

/** `GET` and `PUT /v1/me/profile`: the rider's own profile. */
internal fun Route.profileRoutes(api: Api) {
    signedIn(api, ApiRoute.GET_PROFILE) { userId ->
        val profile = api.database.read { Profiles.find(it, userId) } ?: throw noProfile()
        call.respondJson(RiderProfileDto.serializer(), profile)
    }
    changing(api, ApiRoute.PUT_PROFILE) {
        val profile = body(RiderProfileDto.serializer())
        ProfileRules.check(profile)
        Profiles.put(handle, userId, profile, now)
        Answer.json(RiderProfileDto.serializer(), profile)
    }
}

/** A user who has signed in and not yet saved a profile: the app shows its onboarding on this. */
private fun noProfile() = ProblemException(HttpStatusCode.NotFound, ProblemCode.NOT_FOUND, "Profile not found", "Save a profile first.")
