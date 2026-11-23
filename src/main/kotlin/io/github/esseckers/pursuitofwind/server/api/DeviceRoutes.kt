package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.PushTokenRequestDto
import io.github.esseckers.pursuitofwind.server.device.PushTokens
import io.ktor.server.routing.Route

/**
 * `PUT /v1/me/push-token` (the contract's tag `Devices`): the app says where its device is reached with a push. The
 * token is kept; nothing is sent to it yet.
 */
internal fun Route.deviceRoutes(api: Api) {
    changing(api, ApiRoute.PUT_PUSH_TOKEN) {
        val device = body(PushTokenRequestDto.serializer())
        // The contract asks for one character at least. U+0000 is in no push service's token, and PostgreSQL keeps none.
        check422(device.token.isNotEmpty() && '\u0000' !in device.token) { "token must not be empty." }
        PushTokens.put(handle, userId, device, now)
        Answer.noContent
    }
}
