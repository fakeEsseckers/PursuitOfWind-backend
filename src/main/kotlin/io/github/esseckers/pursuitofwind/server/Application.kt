package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.server.api.respondProblem
import io.github.esseckers.pursuitofwind.server.db.Database
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The server as an application: what every route shares, and the routes. The API's routes arrive one slice of the
 * contract at a time; until a route exists it answers `404` like any other unknown path.
 *
 * - JSON is the contract's own ([ContractJson]), so the server reads and writes exactly what the app does.
 * - Every failure is a problem body with a `code` ([problems]).
 * - Every request carries an id (`X-Request-Id`, taken from the request or made here) into the logs and the response.
 */
internal fun Application.module(database: Database) {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        // An id from outside is written into every log line of its request. Only one that cannot break a line or
        // pass for another field is taken; any other is replaced with an id made here.
        verify { REQUEST_ID.matches(it) }
    }
    install(CallLogging) {
        callIdMdc("requestId")
        // A host asks for the health every few seconds; those lines would bury the others.
        filter { call -> call.request.path() != HEALTH_PATH }
    }
    // Uptime checks often ask with HEAD.
    install(AutoHeadResponse)
    install(ContentNegotiation) {
        json(ContractJson)
    }
    install(StatusPages) {
        problems()
    }
    routing {
        // For the host's checks: the process is up and its database answers. Not part of the API contract.
        get(HEALTH_PATH) {
            if (database.isUp()) {
                call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
            } else {
                call.respondProblem(
                    HttpStatusCode.ServiceUnavailable,
                    ProblemCode.SERVICE_UNAVAILABLE,
                    "Service unavailable",
                    "The database does not answer.",
                )
            }
        }
    }
}

/**
 * What a failed call answers when no route wrote the answer itself.
 *
 * - A request the server could not read (a body that is not JSON, or not the JSON of the operation) is
 *   `422 VALIDATION_FAILED`, as the app's in-app backend answers it. The text of the parser's complaint stays in
 *   the server.
 * - A request no route took is `404 NOT_FOUND`. A route's own 404 (a profile not saved yet, an invite nobody issued)
 *   is already a problem with its own code and detail, and reaches the client as the route wrote it.
 * - Anything else is logged with its stack trace and answered with `500 INTERNAL`, which tells the client nothing
 *   about what went wrong inside.
 */
private fun StatusPagesConfig.problems() {
    exception<BadRequestException> { call, _ -> call.respondUnreadable() }
    exception<ContentTransformationException> { call, _ -> call.respondUnreadable() }
    exception<NotFoundException> { call, _ -> call.respondNotFound() }
    exception<Throwable> { call, cause ->
        // A cancelled request (the client went away, the server is stopping) is not a failure of the server.
        if (cause is CancellationException) throw cause
        call.application.environment.log
            .error("Unhandled failure", cause)
        call.respondProblem(HttpStatusCode.InternalServerError, ProblemCode.INTERNAL, "Internal error")
    }
    unhandled { call -> call.respondNotFound() }
}

private suspend fun ApplicationCall.respondUnreadable() =
    respondProblem(
        HttpStatusCode.UnprocessableEntity,
        ProblemCode.VALIDATION_FAILED,
        "Request is not valid",
        "The body is not valid JSON for this operation.",
    )

private suspend fun ApplicationCall.respondNotFound() =
    respondProblem(HttpStatusCode.NotFound, ProblemCode.NOT_FOUND, "Not found", "No such route.")

private const val HEALTH_PATH = "/health"

/** The request ids taken from outside: short, and made of characters that mean nothing to a log format. */
private val REQUEST_ID = Regex("[A-Za-z0-9._-]{1,128}")
