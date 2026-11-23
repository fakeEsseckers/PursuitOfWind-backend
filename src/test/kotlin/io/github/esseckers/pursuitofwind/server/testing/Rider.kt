package io.github.esseckers.pursuitofwind.server.testing

import io.github.esseckers.pursuitofwind.contract.ApiHeaders
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.AuthTokensDto
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.util.UUID

/**
 * A signed-in user of the server under test, calling it as the app would: with their access token, the contract's
 * method and path for an operation, and JSON.
 */
internal class Rider(
    private val client: HttpClient,
    val installationId: String,
    val tokens: AuthTokensDto,
) {
    val userId: String get() = tokens.userId

    /** Calls [operation] with [body] as JSON, and with [idempotencyKey] if one is given. */
    suspend fun call(
        operation: ApiRoute,
        body: String? = null,
        idempotencyKey: String? = null,
        vararg pathParameters: String,
    ): HttpResponse =
        client.request(operation.pathWith(*pathParameters)) {
            method = HttpMethod.parse(operation.method)
            bearerAuth(tokens.accessToken)
            if (idempotencyKey != null) header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
}

/** A new user on a new installation, signed in. */
internal suspend fun TestServer.rider(): Rider = signIn(UUID.randomUUID().toString())

/** The same user as [rider] with new tokens, for a test in which more than an access token's hour goes by. */
internal suspend fun TestServer.signedInAgain(rider: Rider): Rider = signIn(rider.installationId)

private suspend fun TestServer.signIn(installationId: String): Rider {
    val response =
        client.request(ApiRoute.SIGN_IN_ANONYMOUSLY.path) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody("""{"installationId":"$installationId","platform":"ANDROID"}""")
        }
    return Rider(client, installationId, response.json(AuthTokensDto.serializer()))
}
