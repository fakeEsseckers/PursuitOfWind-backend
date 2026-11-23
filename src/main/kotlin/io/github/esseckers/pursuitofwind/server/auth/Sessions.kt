package io.github.esseckers.pursuitofwind.server.auth

import io.github.esseckers.pursuitofwind.contract.AuthTokensDto
import io.github.esseckers.pursuitofwind.contract.PlatformDto
import io.github.esseckers.pursuitofwind.server.db.Database
import org.jdbi.v3.core.Handle
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Who a call is made as: anonymous sign-in by installation id, token pairs, and the check of an access token.
 *
 * - An installation id is one user, for good: signing in again is the same user with new tokens, and the tokens
 *   handed out before keep working until they expire or are used.
 * - An access token works for an hour. A refresh token works once: exchanging it removes it in the same transaction
 *   that issues the next pair, so two calls with one token cannot both succeed.
 * - A refresh token nobody used for [REFRESH_TOKEN_LIFETIME] stops working. The app then signs in again with its
 *   installation id, as the contract tells it to after any failed refresh.
 */
internal class Sessions(
    private val database: Database,
    private val clock: Clock,
) {
    /** Tokens for the user of [installationId], who is created if this is the installation's first sign-in. */
    suspend fun signIn(
        installationId: UUID,
        platform: PlatformDto,
    ): SignedIn {
        val now = now()
        val installation = Credentials.hash(installationId.toString())
        return database.transaction { handle ->
            // Two first sign-ins of one installation at once: the second insert waits for the first, then does nothing.
            val created =
                handle
                    .createQuery(
                        """
                        insert into users (id, installation_id_hash, platform, created_at)
                        values (:id, :installation, :platform, :now)
                        on conflict (installation_id_hash) do nothing
                        returning id
                        """,
                    ).bind("id", Credentials.newId("usr"))
                    .bind("installation", installation)
                    .bind("platform", platform.name)
                    .bind("now", now.toJavaInstant())
                    .mapTo(String::class.java)
                    .findOne()
                    .orElse(null)
            val userId =
                created ?: handle
                    .createQuery("select id from users where installation_id_hash = :installation")
                    .bind("installation", installation)
                    .mapTo(String::class.java)
                    .one()
            SignedIn(issue(handle, userId, now), newUser = created != null)
        }
    }

    /** The next pair for the owner of [refreshToken], which stops working; null if it is not a token that works. */
    suspend fun refresh(refreshToken: String): AuthTokensDto? {
        val now = now()
        return database.transaction { handle ->
            handle
                .createQuery("delete from refresh_tokens where token_hash = :token and expires_at > :now returning user_id")
                .bind("token", Credentials.hash(refreshToken))
                .bind("now", now.toJavaInstant())
                .mapTo(String::class.java)
                .findOne()
                .orElse(null)
                ?.let { userId -> issue(handle, userId, now) }
        }
    }

    /** The id of the user [accessToken] was issued to, while it has not expired. */
    suspend fun userOf(accessToken: String): String? {
        val now = now()
        return database.transaction { handle ->
            handle
                .createQuery("select user_id from access_tokens where token_hash = :token and expires_at > :now")
                .bind("token", Credentials.hash(accessToken))
                .bind("now", now.toJavaInstant())
                .mapTo(String::class.java)
                .findOne()
                .orElse(null)
        }
    }

    /** The time as the API and the database both keep it: to the millisecond. */
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    private fun issue(
        handle: Handle,
        userId: String,
        now: Instant,
    ): AuthTokensDto {
        val tokens =
            AuthTokensDto(
                userId = userId,
                accessToken = Credentials.newToken("at"),
                refreshToken = Credentials.newToken("rt"),
                accessTokenExpiresAt = now + ACCESS_TOKEN_LIFETIME,
            )
        // The moment a user gets new tokens is the moment to drop their old ones that no longer work.
        for (table in listOf("access_tokens", "refresh_tokens")) {
            handle
                .createUpdate("delete from $table where user_id = :user and expires_at <= :now")
                .bind("user", userId)
                .bind("now", now.toJavaInstant())
                .execute()
        }
        insert(handle, "access_tokens", tokens.accessToken, userId, tokens.accessTokenExpiresAt)
        insert(handle, "refresh_tokens", tokens.refreshToken, userId, now + REFRESH_TOKEN_LIFETIME)
        return tokens
    }

    private fun insert(
        handle: Handle,
        table: String,
        token: String,
        userId: String,
        expiresAt: Instant,
    ) {
        handle
            .createUpdate("insert into $table (token_hash, user_id, expires_at) values (:token, :user, :expires)")
            .bind("token", Credentials.hash(token))
            .bind("user", userId)
            .bind("expires", expiresAt.toJavaInstant())
            .execute()
    }

    companion object {
        val ACCESS_TOKEN_LIFETIME = 1.hours
        val REFRESH_TOKEN_LIFETIME = 90.days
    }
}

/** The outcome of a sign-in: the tokens, and whether the installation was seen for the first time. */
internal data class SignedIn(
    val tokens: AuthTokensDto,
    val newUser: Boolean,
)
