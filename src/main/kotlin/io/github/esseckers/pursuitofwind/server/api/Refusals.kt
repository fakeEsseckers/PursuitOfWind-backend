package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.ktor.http.HttpStatusCode

// The refusals that belong to one part of the contract, each with the status, code and title the contract's examples
// and the app's in-app backend give it.

/** `403 NOT_THE_CREATOR`: the spot is someone else's. */
internal fun notTheCreator() =
    ProblemException(HttpStatusCode.Forbidden, ProblemCode.NOT_THE_CREATOR, "Only the rider who added this spot can change it")

/** `403 NOT_A_MEMBER`: the crew, or the crew of the ride, is not one of the caller's. */
internal fun notAMember() = ProblemException(HttpStatusCode.Forbidden, ProblemCode.NOT_A_MEMBER, "Not a member of this crew")

/** `404 INVITE_INVALID`: no crew has the invite code the call gave. */
internal fun inviteInvalid() = ProblemException(HttpStatusCode.NotFound, ProblemCode.INVITE_INVALID, "Invite code is not valid")

/** `410 FEED_RESET`: the feed has no such place to go on from; the reader reloads and starts over. */
internal fun feedReset() = ProblemException(HttpStatusCode.Gone, ProblemCode.FEED_RESET, "Feed position is no longer available")
