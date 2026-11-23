package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationStrategy

/**
 * Answers with [value] as the contract's JSON. The contract offers nothing but JSON, so what the call says it
 * accepts is not asked: a handler that has already changed something must not fail to say so over a header.
 */
internal suspend fun <T> ApplicationCall.respondJson(
    type: SerializationStrategy<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(ContractJson.encodeToString(type, value), ContentType.Application.Json, status)
}
