package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.server.api.respondProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.UUID

/**
 * The server as an application: what every route shares, and the routes. The API's routes arrive one slice of the
 * contract at a time; until a route exists it answers `404` like any other unknown path.
 *
 * - JSON is the contract's own ([ContractJson]), so the server reads and writes exactly what the app does.
 * - Every failure is a problem body with a `code`; an unexpected one is logged with its stack trace and tells the
 *   client nothing about what went wrong inside.
 * - Every request carries an id (`X-Request-Id`, taken from the request or made here) into the logs and the response.
 */
internal fun Application.module() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() && it.length <= MAX_REQUEST_ID_LENGTH }
    }
    install(CallLogging) {
        callIdMdc("requestId")
    }
    install(ContentNegotiation) {
        json(ContractJson)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log
                .error("Unhandled failure", cause)
            call.respondProblem(HttpStatusCode.InternalServerError, ProblemCode.INTERNAL, "Internal error")
        }
        // Only a request no route took. A route's own 404 (a profile not saved yet, an invite nobody issued) is
        // already a problem with its own code and detail, and must reach the client as the route wrote it.
        unhandled { call ->
            call.respondProblem(HttpStatusCode.NotFound, ProblemCode.NOT_FOUND, "Not found", "No such route.")
        }
    }
    routing {
        // For the host's checks: the process is up and answering. Not part of the API contract.
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
    }
}

/** A request id from outside ends up in every log line of the request, so it is not taken at any length. */
private const val MAX_REQUEST_ID_LENGTH = 128
