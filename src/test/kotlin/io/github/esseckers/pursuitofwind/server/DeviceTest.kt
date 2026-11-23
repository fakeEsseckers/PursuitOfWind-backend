package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiHeaders
import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.server.db.Database
import io.github.esseckers.pursuitofwind.server.testing.Rider
import io.github.esseckers.pursuitofwind.server.testing.TestPostgres
import io.github.esseckers.pursuitofwind.server.testing.TestServer
import io.github.esseckers.pursuitofwind.server.testing.problem
import io.github.esseckers.pursuitofwind.server.testing.rider
import io.github.esseckers.pursuitofwind.server.testing.serverTest
import io.github.esseckers.pursuitofwind.server.testing.together
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The push token of a rider's device, through HTTP on a real database. */
class DeviceTest {
    private val key = "3f9d6c1e-8a42-4f4b-9d0e-6b1c2a7e5f10"

    private suspend fun Rider.register(
        token: String,
        platform: String = "IOS",
        idempotencyKey: String? = null,
    ): HttpResponse = call(ApiRoute.PUT_PUSH_TOKEN, """{"platform":"$platform","token":"$token"}""", idempotencyKey)

    /** What the server keeps for [rider], as `platform token updated_at`; nothing in the API gives it back. */
    private fun TestServer.kept(rider: Rider): List<String> =
        TestPostgres.strings(
            database,
            """
            select platform || ' ' || token || ' ' || to_char(updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            from ${Database.SCHEMA}.push_tokens where user_id = '${rider.userId}'
            """,
        )

    @Test
    fun `a device's push token is kept, the last one the app sent`() =
        serverTest {
            val alex = rider()
            val dana = rider()

            val registered = alex.register("4f2b8c0d9e1a")

            assertEquals(HttpStatusCode.NoContent to "", registered.status to registered.bodyAsText())
            assertEquals(listOf("IOS 4f2b8c0d9e1a 2026-07-16T06:00:00Z"), kept(alex))
            assertEquals(emptyList(), kept(dana))

            clock.now += 10.minutes
            dana.register("d-8Zk:APA91b", platform = "ANDROID")
            assertEquals(HttpStatusCode.NoContent, alex.register("9e1a4f2b8c0d").status)

            assertEquals(listOf("IOS 9e1a4f2b8c0d 2026-07-16T06:10:00Z"), kept(alex))
            assertEquals(listOf("ANDROID d-8Zk:APA91b 2026-07-16T06:10:00Z"), kept(dana))
        }

    @Test
    fun `a push token that says nothing, or a platform there is none of, is refused`() =
        serverTest {
            val alex = rider()
            val wrong =
                listOf(
                    """{"platform":"IOS","token":""}""",
                    """{"platform":"IOS","token":"4f2b\u00008c0d"}""",
                    """{"platform":"WINDOWS","token":"4f2b8c0d9e1a"}""",
                    """{"platform":"IOS"}""",
                    """{"token":"4f2b8c0d9e1a"}""",
                    "{}",
                )

            for (body in wrong) {
                val response = alex.call(ApiRoute.PUT_PUSH_TOKEN, body)

                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
                assertEquals(ProblemCode.VALIDATION_FAILED, response.problem().code, body)
            }
            assertEquals(emptyList(), kept(alex))
            assertEquals(HttpStatusCode.Unauthorized, client.put(ApiRoute.PUT_PUSH_TOKEN.path).status)
        }

    @Test
    fun `a push token sent again with the same key is taken once`() =
        serverTest {
            val alex = rider()

            val first = alex.register("4f2b8c0d9e1a", idempotencyKey = key)
            clock.now += 10.minutes
            val again = alex.register("4f2b8c0d9e1a", idempotencyKey = key)

            assertEquals(HttpStatusCode.NoContent to null, first.status to first.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(HttpStatusCode.NoContent to "true", again.status to again.headers[ApiHeaders.IDEMPOTENT_REPLAYED])
            assertEquals(listOf("IOS 4f2b8c0d9e1a 2026-07-16T06:00:00Z"), kept(alex))
        }

    @Test
    fun `two calls of one rider at the same moment leave one token`() =
        serverTest {
            val alex = rider()
            alex.register("first")
            val tokens = listOf("4f2b8c0d9e1a", "9e1a4f2b8c0d")
            // Both wait for the table the test holds, and are then let go together.
            val theTable = "lock table ${Database.SCHEMA}.push_tokens in access exclusive mode"

            val answers = together(theTable, tokens.map { token -> suspend { alex.register(token) } })

            assertEquals(List(2) { HttpStatusCode.NoContent }, answers.map { it.status })
            val kept = kept(alex).single()
            assertTrue(tokens.any { kept == "IOS $it 2026-07-16T06:00:00Z" }, kept)
        }
}
