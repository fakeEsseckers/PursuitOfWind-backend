package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiHeaders
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.server.auth.Credentials
import io.ktor.http.HttpStatusCode
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.SqlStatement
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * `Idempotency-Key`: a call that changes something, sent again with the same key, changes nothing again and gets the
 * first answer back.
 *
 * - A key belongs to one user, method and path, for [RETENTION].
 * - The key is claimed and the answer kept in the transaction of the change itself. Two calls with one key at the
 *   same moment therefore cannot both run: the second waits for the first to commit, then gets its answer. That
 *   relies on PostgreSQL's default isolation, read committed, where the waiting insert sees the row that committed.
 * - The same key with another body is `422 IDEMPOTENCY_KEY_REUSED`.
 * - Only a call that succeeded is kept. One that failed changed nothing, rolls back with its key, and runs again when
 *   it is retried.
 */
internal object Idempotency {
    val RETENTION = 24.hours
    private const val EXPIRED_REMOVED_AT_ONCE = 100
    private const val SAME_KEY = "user_id = :user and method = :method and path = :path and key = :key"

    /** The status of a row between its claim and its answer; no row is ever committed with it. */
    private const val NOT_ANSWERED = 0
    private val UUID_FORM = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    /** A call that came with a key: whose it is, what it asks for, and what it said. */
    class Request(
        val userId: String,
        val method: String,
        val path: String,
        val key: UUID,
        val bodyHash: ByteArray,
    )

    /** The call as [once] wants it; a [key] that is not a UUID is `422 VALIDATION_FAILED`. */
    fun request(
        userId: String,
        method: String,
        path: String,
        key: String,
        body: String,
    ): Request {
        if (!UUID_FORM.matches(key)) throw invalid("${ApiHeaders.IDEMPOTENCY_KEY} must be a UUID.")
        return Request(userId, method, path, UUID.fromString(key), Credentials.hash(body))
    }

    /**
     * Runs [change] unless [request]'s key has been used: then the answer kept for it comes back, marked as
     * replayed. A call without a key ([request] is null) just runs.
     */
    fun once(
        handle: Handle,
        request: Request?,
        now: Instant,
        change: () -> Answer,
    ): Answer =
        when {
            request == null -> change()
            !claim(handle, request, now) -> kept(handle, request)
            else -> change().also { keep(handle, request, it) }
        }

    private fun keep(
        handle: Handle,
        request: Request,
        answer: Answer,
    ) {
        handle
            .createUpdate("update idempotency_keys set status = :status, body = :body where $SAME_KEY")
            .bind("status", answer.status.value)
            .bind("body", answer.json)
            .bindKey(request)
            .execute()
    }

    /**
     * Takes the key for this call, after freeing the keys that have had their day. False if a call that committed
     * already holds it; one in flight is waited for.
     */
    private fun claim(
        handle: Handle,
        request: Request,
        now: Instant,
    ): Boolean {
        // This key first, whatever else is waiting to be removed: a key that has had its day must be free now.
        handle
            .createUpdate("delete from idempotency_keys where $SAME_KEY and created_at <= :cutoff")
            .bindKey(request)
            .bind("cutoff", (now - RETENTION).toJavaInstant())
            .execute()
        removeExpired(handle, now)
        return handle
            .createUpdate(
                """
                insert into idempotency_keys (user_id, method, path, key, request_hash, status, created_at)
                values (:user, :method, :path, :key, :hash, $NOT_ANSWERED, :now)
                on conflict do nothing
                """,
            ).bindKey(request)
            .bind("hash", request.bodyHash)
            .bind("now", now.toJavaInstant())
            .execute() == 1
    }

    private fun kept(
        handle: Handle,
        request: Request,
    ): Answer =
        handle
            .createQuery("select request_hash, status, body from idempotency_keys where $SAME_KEY")
            .bindKey(request)
            .map { row, _ ->
                if (!row.getBytes("request_hash").contentEquals(request.bodyHash)) throw keyReused()
                Answer(HttpStatusCode.fromValue(row.getInt("status")), row.getString("body"), replayed = true)
            }.one()

    /** Keys older than [RETENTION] are free again; rows someone else is removing are skipped. */
    private fun removeExpired(
        handle: Handle,
        now: Instant,
    ) {
        handle
            .createUpdate(
                """
                delete from idempotency_keys
                where (user_id, method, path, key) in (
                    select user_id, method, path, key from idempotency_keys
                    where created_at <= :cutoff
                    limit :batch
                    for update skip locked
                )
                """,
            ).bind("cutoff", (now - RETENTION).toJavaInstant())
            .bind("batch", EXPIRED_REMOVED_AT_ONCE)
            .execute()
    }

    private fun keyReused() =
        ProblemException(
            HttpStatusCode.UnprocessableEntity,
            ProblemCode.IDEMPOTENCY_KEY_REUSED,
            "Idempotency key was used for a different request",
        )

    private fun <S : SqlStatement<S>> S.bindKey(request: Request): S =
        bind("user", request.userId).bind("method", request.method).bind("path", request.path).bind("key", request.key)
}
