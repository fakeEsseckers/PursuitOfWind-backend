package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiHeaders
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.server.api.Api
import io.github.esseckers.pursuitofwind.server.api.ProblemException
import io.github.esseckers.pursuitofwind.server.api.authRoutes
import io.github.esseckers.pursuitofwind.server.api.forecastRoutes
import io.github.esseckers.pursuitofwind.server.api.profileRoutes
import io.github.esseckers.pursuitofwind.server.api.respondProblem
import io.github.esseckers.pursuitofwind.server.api.spotRoutes
import io.github.esseckers.pursuitofwind.server.api.unavailable
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jdbi.v3.core.JdbiException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The server as an application: what every route shares, and the routes. The API's routes arrive one slice of the
 * contract at a time; until a route exists it answers `404` like any other unknown path.
 *
 * [api] is what the handlers share: the database, the sessions, and the clock, which is the server's only source of
 * the time, so that a test can say what time it is.
 *
 * - JSON is the contract's own, read and written by the server itself (`body`, `respondJson`), so it reads and
 *   writes exactly what the app does whatever the call's headers say.
 * - Every failure is a problem body with a `code` ([problems]).
 * - Every request carries an id (`X-Request-Id`, taken from the request or made here) into the logs and the response.
 */
internal fun Application.module(api: Api) {
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
    install(StatusPages) {
        problems()
    }
    routing {
        authRoutes(api)
        profileRoutes(api)
        spotRoutes(api)
        forecastRoutes(api)
        // For the host's checks: the process is up and its database answers. Not part of the API contract.
        get(HEALTH_PATH) {
            if (api.database.isUp()) {
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
 * - A rule of the contract a route found broken ([ProblemException]) is answered as the route said.
 * - A call that met the database gone, or found no free connection in time, is `503 SERVICE_UNAVAILABLE` with
 *   `Retry-After`, as the app's in-app backend answers when it is unavailable.
 * - A request the server could not read (a body that is not JSON, or not the JSON of the operation) is
 *   `422 VALIDATION_FAILED`, as the app's in-app backend answers it. The text of the parser's complaint stays in
 *   the server.
 * - A request no route took is `404 NOT_FOUND`. A route's own 404 (a profile not saved yet, an invite nobody issued)
 *   is already a problem with its own code and detail, and reaches the client as the route wrote it.
 * - Anything else is logged with its stack trace and answered with `500 INTERNAL`, which tells the client nothing
 *   about what went wrong inside.
 */
private fun StatusPagesConfig.problems() {
    exception<ProblemException> { call, problem -> call.respondProblem(problem) }
    // A call that met the database gone, or found no free connection in time, is not at fault and may come again.
    exception<JdbiException> { call, cause ->
        if (cause.isOutage()) {
            call.application.environment.log
                .warn("The database was not there for a call: {}", cause.cause?.message ?: cause.message)
            call.respondProblem(unavailable())
        } else {
            call.respondUnexpected(cause)
        }
    }
    exception<BadRequestException> { call, _ -> call.respondUnreadable() }
    exception<ContentTransformationException> { call, _ -> call.respondUnreadable() }
    exception<NotFoundException> { call, _ -> call.respondNotFound() }
    exception<Throwable> { call, cause ->
        // A cancelled request (the client went away, the server is stopping) is not a failure of the server.
        if (cause is CancellationException) throw cause
        call.respondUnexpected(cause)
    }
    unhandled { call -> call.respondNotFound() }
}

private suspend fun ApplicationCall.respondUnexpected(cause: Throwable) {
    application.environment.log.error("Unhandled failure", cause)
    respondProblem(HttpStatusCode.InternalServerError, ProblemCode.INTERNAL, "Internal error")
}

/**
 * Whether a failed query failed because the database could not be reached: no free connection in time, a connection
 * that broke (SQLSTATE class 08), the server shutting down or restarting (57P), or the database not there (3D000).
 */
private fun JdbiException.isOutage(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { cause ->
        cause is SQLTransientConnectionException ||
            (cause is SQLException && OUTAGE_STATES.any { cause.sqlState.orEmpty().startsWith(it) })
    }

private val OUTAGE_STATES = listOf("08", "57P", "3D000")

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
