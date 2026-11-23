package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ProblemDto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

// Every failed call answers with the contract's problem body (RFC 9457, `application/problem+json`): clients branch on
// its `code`, never on the text.

private val PROBLEM_JSON = ContentType.parse(ProblemDto.MEDIA_TYPE)

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
