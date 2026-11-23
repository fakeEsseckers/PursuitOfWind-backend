package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.server.auth.Sessions
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route

// The contract's route table (`ApiRoute`) is where a method and a path come from: a handler is registered for an
// operation, not for a string, so the server cannot answer at a path the contract does not have.

/** Registers the handler of [operation], one of the two the contract opens to callers without a token. */
internal fun Route.open(
    operation: ApiRoute,
    handler: suspend RoutingContext.() -> Unit,
) {
    require(!operation.requiresAuth) { "$operation needs a signed-in user: register it with signedIn" }
    route(operation.path, HttpMethod.parse(operation.method)) { handle { handler() } }
}

/**
 * Registers the handler of [operation] for signed-in users. It runs only for a call with an access token that
 * works, and is told the id of the user the token was issued to; any other call is answered `401 UNAUTHORIZED`.
 */
internal fun Route.signedIn(
    operation: ApiRoute,
    sessions: Sessions,
    handler: suspend RoutingContext.(userId: String) -> Unit,
) {
    require(operation.requiresAuth) { "$operation is open to everyone: register it with open" }
    route(operation.path, HttpMethod.parse(operation.method)) {
        handle {
            val token = call.bearerToken() ?: throw unauthorized(tokenSent = false)
            handler(sessions.userOf(token) ?: throw unauthorized(tokenSent = true))
        }
    }
}

/** The token of `Authorization: Bearer <token>`, or null when the call carries no such header. */
private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val scheme = header.substringBefore(' ')
    return header.substringAfter(' ', missingDelimiterValue = "").trim().takeIf {
        scheme.equals("Bearer", ignoreCase = true) &&
            it.isNotEmpty()
    }
}
