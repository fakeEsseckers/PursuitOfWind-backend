package io.github.esseckers.pursuitofwind.server.testing

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.assertEquals

private const val POLLS = 500
private const val POLL_MILLIS = 20L

/**
 * Makes [calls] meet: the test holds what [lock] locks, starts them all, waits until every one of them stands behind
 * that lock, and only then lets go. What they answered comes back in their order.
 *
 * It fails if they do not all come to wait there, which is also how a test shows that a call takes that lock at all.
 * No more calls than the server has connections (five) can wait at once.
 */
internal suspend fun <T> TestServer.together(
    lock: String,
    calls: List<suspend () -> T>,
): List<T> {
    val waiting = "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
    val held = TestPostgres.holding(database, lock)
    return coroutineScope {
        val running = calls.map { async { it() } }
        try {
            var polls = 0
            while (TestPostgres.strings(database, waiting).single().toInt() < calls.size && polls++ < POLLS) delay(POLL_MILLIS)
            assertEquals(calls.size, TestPostgres.strings(database, waiting).single().toInt(), "calls waiting behind: $lock")
        } finally {
            held.close()
        }
        running.awaitAll()
    }
}
