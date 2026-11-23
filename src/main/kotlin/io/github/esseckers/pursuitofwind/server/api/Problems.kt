package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.contract.ProblemDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText

// Every failed call answers with the contract's problem body (RFC 9457, `application/problem+json`): clients branch on
// its `code`, never on the text.

private val PROBLEM_JSON = ContentType.parse(ProblemDto.MEDIA_TYPE)

/**
 * A failure a route answers with as it is. It is thrown where a rule is broken and turned into the problem body in
 * one place, so a handler reads as the path where everything is in order.
 *
 * @property code one of the contract's `ProblemCode`s.
 * @property headers what the answer carries beside the body (`WWW-Authenticate` on a 401).
 */
internal class ProblemException(
    val status: HttpStatusCode,
    val code: String,
    val title: String,
    val detail: String? = null,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(code)

/** `422 VALIDATION_FAILED`: the request is readable but breaks a rule of the contract; [detail] says which. */
internal fun invalid(detail: String) =
    ProblemException(HttpStatusCode.UnprocessableEntity, ProblemCode.VALIDATION_FAILED, "Request is not valid", detail)

/**
 * `401 UNAUTHORIZED`, with the challenge the contract and RFC 6750 ask for: a token that was sent and does not work
 * is `invalid_token`, a call without one is asked for one.
 */
internal fun unauthorized(tokenSent: Boolean) =
    ProblemException(
        HttpStatusCode.Unauthorized,
        ProblemCode.UNAUTHORIZED,
        "Unauthorized",
        headers = mapOf(HttpHeaders.WWWAuthenticate to if (tokenSent) "Bearer error=\"invalid_token\"" else "Bearer"),
    )

/** Answers with [problem]. */
internal suspend fun ApplicationCall.respondProblem(problem: ProblemException) {
    problem.headers.forEach { (name, value) -> response.header(name, value) }
    respondProblem(problem.status, problem.code, problem.title, problem.detail)
}

/**
 * Answers with a problem body. [code] is one of the contract's `ProblemCode`s; the `type` is derived from it the way
 * the client's in-app fake backend does (`/problems/not-found`), so both backends answer alike.
 */
internal suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    code: String,
    title: String,
    detail: String? = null,
) {
    val problem =
        ProblemDto(
            type = "/problems/" + code.lowercase().replace('_', '-'),
            title = title,
            status = status.value,
            code = code,
            detail = detail,
        )
    respondText(ContractJson.encodeToString(ProblemDto.serializer(), problem), PROBLEM_JSON, status)
}
