package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiHeaders
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.server.auth.Sessions
import io.github.esseckers.pursuitofwind.server.db.Database
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import org.jdbi.v3.core.Handle
import kotlin.time.Clock
import kotlin.time.Instant

// The contract's route table (`ApiRoute`) is where a method and a path come from: a handler is registered for an
// operation, not for a string, so the server cannot answer at a path the contract does not have.

/** What the handlers of the API share. [clock] is the server's only source of the time. */
internal class Api(
    val database: Database,
    val clock: Clock,
    val sessions: Sessions = Sessions(database, clock),
)

/** Registers the handler of [operation], one of the two the contract opens to callers without a token. */
internal fun Route.open(
    operation: ApiRoute,
    handler: suspend RoutingContext.() -> Unit,
) {
    require(!operation.requiresAuth) { "$operation needs a signed-in user: register it with signedIn or changing" }
    route(operation.path, HttpMethod.parse(operation.method)) { handle { handler() } }
}

/**
 * Registers the handler of [operation], one that only reads, for signed-in users. It runs only for a call with an
 * access token that works, and is told the id of the user the token was issued to; any other call is answered
 * `401 UNAUTHORIZED`.
 */
internal fun Route.signedIn(
    api: Api,
    operation: ApiRoute,
    handler: suspend RoutingContext.(userId: String) -> Unit,
) {
    require(operation.requiresAuth) { "$operation is open to everyone: register it with open" }
    route(operation.path, HttpMethod.parse(operation.method)) { handle { handler(api.userOf(call)) } }
}

/**
 * Registers the handler of [operation], one that changes something, for signed-in users.
 *
 * The handler runs inside one database transaction and returns what to answer; the answer is written after the
 * transaction has committed, and a [ProblemException] it throws rolls everything back. With an `Idempotency-Key` the
 * answer is kept with the change, in that same transaction, and a retry gets it back instead of changing anything
 * twice ([Idempotency]).
 */
internal fun Route.changing(
    api: Api,
    operation: ApiRoute,
    handler: Change.() -> Answer,
) {
    require(operation.requiresAuth && operation.method != "GET") { "$operation does not change anything for a signed-in user" }
    route(operation.path, HttpMethod.parse(operation.method)) {
        handle {
            val userId = api.userOf(call)
            val body = call.receiveText()
            val now = api.clock.now()
            val request =
                call.request.headers[ApiHeaders.IDEMPOTENCY_KEY]?.let {
                    Idempotency.request(userId, operation.method, call.request.path(), it, body)
                }
            val parameters = call.parameters
            val answer =
                api.database.transaction { handle ->
                    Idempotency.once(handle, request, now) { Change(userId, handle, body, parameters, now).handler() }
                }
            if (answer.replayed) call.response.header(ApiHeaders.IDEMPOTENT_REPLAYED, "true")
            call.respondAnswer(answer)
        }
    }
}

/**
 * What a handler that changes something works with.
 *
 * @property handle the transaction the change happens in.
 * @property body the request body as it came.
 * @property parameters the path and query parameters of the call.
 * @property now the time of the call, one value for everything the change writes.
 */
internal class Change(
    val userId: String,
    val handle: Handle,
    val body: String,
    val parameters: Parameters,
    val now: Instant,
) {
    /** The body as the contract's [type]; a body that is not that is `422 VALIDATION_FAILED`. */
    fun <T> body(type: DeserializationStrategy<T>): T =
        try {
            ContractJson.decodeFromString(type, body)
        } catch (e: IllegalArgumentException) {
            throw invalid("The body is not valid JSON for this operation.", e)
        }
}

/**
 * What an operation answers: a status and, unless there is nothing to say, the contract's JSON.
 *
 * @property replayed whether this is the answer kept from an earlier call with the same `Idempotency-Key`.
 */
internal data class Answer(
    val status: HttpStatusCode,
    val json: String?,
    val replayed: Boolean = false,
) {
    companion object {
        /** `204`: done, and nothing to say. */
        val noContent = Answer(HttpStatusCode.NoContent, null)

        /** [value] as the contract's JSON. */
        fun <T> json(
            type: SerializationStrategy<T>,
            value: T,
            status: HttpStatusCode = HttpStatusCode.OK,
        ) = Answer(status, ContractJson.encodeToString(type, value))
    }
}

private suspend fun ApplicationCall.respondAnswer(answer: Answer) {
    if (answer.json == null) respond(answer.status) else respondText(answer.json, ContentType.Application.Json, answer.status)
}

/** The id of the user [call] is made as; a call without an access token that works is `401 UNAUTHORIZED`. */
private suspend fun Api.userOf(call: ApplicationCall): String {
    val token = call.bearerToken() ?: throw unauthorized(tokenSent = false)
    return sessions.userOf(token) ?: throw unauthorized(tokenSent = true)
}

/**
 * The token of `Authorization: Bearer <token>`, or null when the call carries no Bearer credential at all. A Bearer
 * header with nothing after it is a token that was sent and does not work, as the app's in-app backend treats it.
 */
private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val isBearer = header.substringBefore(' ').equals("Bearer", ignoreCase = true)
    return if (isBearer) header.substringAfter(' ', missingDelimiterValue = "").trim() else null
}
