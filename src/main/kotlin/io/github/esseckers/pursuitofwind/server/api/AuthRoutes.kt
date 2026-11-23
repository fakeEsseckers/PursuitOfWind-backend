package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.AnonymousSignInRequestDto
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.AuthTokensDto
import io.github.esseckers.pursuitofwind.contract.RefreshTokensRequestDto
import io.github.esseckers.pursuitofwind.server.auth.Sessions
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import java.util.UUID

/** `POST /v1/auth/anonymous` and `POST /v1/auth/refresh`: the two operations that hand out tokens. */
internal fun Route.authRoutes(sessions: Sessions) {
    open(ApiRoute.SIGN_IN_ANONYMOUSLY) { signIn(sessions) }
    open(ApiRoute.REFRESH_TOKENS) { refresh(sessions) }
}

/** `201` with tokens for an installation seen for the first time, `200` with new tokens for one seen before. */
private suspend fun RoutingContext.signIn(sessions: Sessions) {
    val request = call.receive<AnonymousSignInRequestDto>()
    val installationId = uuidOrNull(request.installationId) ?: throw invalid("installationId must be a UUID.")
    val signedIn = sessions.signIn(installationId, request.platform)
    call.respondJson(AuthTokensDto.serializer(), signedIn.tokens, if (signedIn.newUser) HttpStatusCode.Created else HttpStatusCode.OK)
}

/** The next pair for a refresh token that works; `401` for one that does not, which sends the app back to sign-in. */
private suspend fun RoutingContext.refresh(sessions: Sessions) {
    val request = call.receive<RefreshTokensRequestDto>()
    if (request.refreshToken.isEmpty()) throw invalid("refreshToken must not be empty.")
    call.respondJson(AuthTokensDto.serializer(), sessions.refresh(request.refreshToken) ?: throw unauthorized(tokenSent = true))
}

private val UUID_FORM = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

/** [text] as a UUID if it is written as one in full; `UUID.fromString` alone also takes shorter groups. */
private fun uuidOrNull(text: String): UUID? = if (UUID_FORM.matches(text)) UUID.fromString(text) else null
