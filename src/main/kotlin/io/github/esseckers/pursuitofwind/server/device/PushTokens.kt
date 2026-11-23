package io.github.esseckers.pursuitofwind.server.device

import io.github.esseckers.pursuitofwind.contract.PushTokenRequestDto
import org.jdbi.v3.core.Handle
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** The push token of each user's device, in the database. */
internal object PushTokens {
    /**
     * Keeps [device] as where [userId] is reached, in place of what was kept for them, unless that came with a later
     * call: of two calls that cross, the one made last stays, whichever is written last.
     */
    fun put(
        handle: Handle,
        userId: String,
        device: PushTokenRequestDto,
        now: Instant,
    ) {
        handle
            .createUpdate(
                """
                insert into push_tokens (user_id, platform, token, updated_at) values (:user, :platform, :token, :now)
                on conflict (user_id) do update
                set platform = excluded.platform, token = excluded.token, updated_at = excluded.updated_at
                where push_tokens.updated_at <= excluded.updated_at
                """,
            ).bind("user", userId)
            .bind("platform", device.platform.name)
            .bind("token", device.token)
            .bind("now", now.toJavaInstant())
            .execute()
    }
}
