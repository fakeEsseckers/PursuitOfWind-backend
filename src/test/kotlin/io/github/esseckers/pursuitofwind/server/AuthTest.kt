package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.AuthTokensDto
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.server.api.signedIn
import io.github.esseckers.pursuitofwind.server.auth.Sessions
import io.github.esseckers.pursuitofwind.server.db.Database
import io.github.esseckers.pursuitofwind.server.testing.TestPostgres
import io.github.esseckers.pursuitofwind.server.testing.TestServer
import io.github.esseckers.pursuitofwind.server.testing.json
import io.github.esseckers.pursuitofwind.server.testing.problem
import io.github.esseckers.pursuitofwind.server.testing.serverTest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Anonymous sign-in, token pairs and the check of an access token, through HTTP on a real database. */
class AuthTest {
    private val phone = "0b6c7a52-3f1e-4c8d-9a7b-5e2f1d0c4b3a"
    private val tablet = "9d3e1f60-7a2b-4c5d-8e9f-0a1b2c3d4e5f"

    /** One operation that wants a signed-in user, answering with who the call was made as. */
    private val whoAmI: Application.(Sessions) -> Unit = { sessions ->
        routing { signedIn(ApiRoute.GET_PROFILE, sessions) { userId -> call.respondText(userId) } }
    }

    private suspend fun TestServer.post(
        operation: ApiRoute,
        body: String,
    ): HttpResponse =
        client.post(operation.path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun TestServer.signIn(
        installationId: String = phone,
        platform: String = "ANDROID",
    ): HttpResponse = post(ApiRoute.SIGN_IN_ANONYMOUSLY, """{"installationId":"$installationId","platform":"$platform"}""")

    private suspend fun TestServer.refresh(refreshToken: String): HttpResponse =
        post(ApiRoute.REFRESH_TOKENS, """{"refreshToken":"$refreshToken"}""")

    private suspend fun TestServer.tokens(installationId: String = phone): AuthTokensDto =
        signIn(installationId).json(AuthTokensDto.serializer())

    private suspend fun TestServer.whoAmI(accessToken: String?): HttpResponse =
        client.get(ApiRoute.GET_PROFILE.path) { if (accessToken != null) bearerAuth(accessToken) }

    private suspend fun HttpResponse.assertUnauthorized(challenge: String) {
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(ProblemCode.UNAUTHORIZED, problem().code)
        assertEquals(challenge, headers[HttpHeaders.WWWAuthenticate])
    }

    private suspend fun HttpResponse.assertInvalid() {
        assertEquals(HttpStatusCode.UnprocessableEntity, status, bodyAsText())
        assertEquals(ProblemCode.VALIDATION_FAILED, problem().code)
    }

    private fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    @Test
    fun `the first sign-in of an installation makes a user and hands out tokens for an hour`() =
        serverTest {
            val response = signIn()

            assertEquals(HttpStatusCode.Created, response.status)
            val tokens = response.json(AuthTokensDto.serializer())
            assertTrue(tokens.userId.startsWith("usr_"), tokens.userId)
            assertTrue(tokens.accessToken.startsWith("at_") && tokens.refreshToken.startsWith("rt_"))
            assertEquals(clock.now + 1.hours, tokens.accessTokenExpiresAt)
        }

    @Test
    fun `signing in again is the same user with new tokens, and the earlier tokens keep working`() =
        serverTest(whoAmI) {
            val first = tokens()
            val again = signIn()

            assertEquals(HttpStatusCode.OK, again.status)
            val second = again.json(AuthTokensDto.serializer())
            assertEquals(first.userId, second.userId)
            assertNotEquals(first.accessToken, second.accessToken)
            assertNotEquals(first.refreshToken, second.refreshToken)
            assertEquals(first.userId, whoAmI(first.accessToken).bodyAsText())
            assertEquals(HttpStatusCode.OK, refresh(first.refreshToken).status)
        }

    @Test
    fun `an installation id is one user however it is written, and another installation is another user`() =
        serverTest {
            val lower = tokens(phone)
            val upper = tokens(phone.uppercase())
            val other = signIn(tablet, platform = "IOS")

            assertEquals(lower.userId, upper.userId)
            assertEquals(HttpStatusCode.Created, other.status)
            assertNotEquals(lower.userId, other.json(AuthTokensDto.serializer()).userId)
        }

    @Test
    fun `two first sign-ins of one installation at the same moment make one user`() =
        serverTest {
            val responses = coroutineScope { List(8) { async { signIn() } }.awaitAll() }

            assertEquals(1, responses.count { it.status == HttpStatusCode.Created }, responses.map { it.status }.toString())
            assertEquals(7, responses.count { it.status == HttpStatusCode.OK })
            assertEquals(1, responses.map { it.json(AuthTokensDto.serializer()).userId }.toSet().size)
        }

    @Test
    fun `a sign-in the contract does not allow is a validation problem`() =
        serverTest {
            signIn(installationId = "not-a-uuid").assertInvalid()
            signIn(installationId = "1-2-3-4-5").assertInvalid()
            signIn(platform = "WINDOWS").assertInvalid()
            post(ApiRoute.SIGN_IN_ANONYMOUSLY, """{"installationId":"$phone"}""").assertInvalid()
            post(ApiRoute.SIGN_IN_ANONYMOUSLY, "{").assertInvalid()
        }

    @Test
    fun `a refresh token is exchanged once for the next pair of the same user`() =
        serverTest(whoAmI) {
            val first = tokens()
            clock.now += 30.minutes

            val response = refresh(first.refreshToken)

            assertEquals(HttpStatusCode.OK, response.status)
            val next = response.json(AuthTokensDto.serializer())
            assertEquals(first.userId, next.userId)
            assertEquals(clock.now + 1.hours, next.accessTokenExpiresAt)
            assertEquals(first.userId, whoAmI(next.accessToken).bodyAsText())
            refresh(first.refreshToken).assertUnauthorized("""Bearer error="invalid_token"""")
            assertEquals(HttpStatusCode.OK, refresh(next.refreshToken).status)
        }

    @Test
    fun `one refresh token used twice at the same moment works once`() =
        serverTest {
            val first = tokens()

            val responses = coroutineScope { List(8) { async { refresh(first.refreshToken) } }.awaitAll() }

            assertEquals(1, responses.count { it.status == HttpStatusCode.OK }, responses.map { it.status }.toString())
            assertEquals(7, responses.count { it.status == HttpStatusCode.Unauthorized })
        }

    @Test
    fun `a refresh token nobody issued, or left unused for too long, is refused`() =
        serverTest {
            val first = tokens()

            refresh("rt_nobody-issued-this").assertUnauthorized("""Bearer error="invalid_token"""")
            refresh(first.accessToken).assertUnauthorized("""Bearer error="invalid_token"""")
            post(ApiRoute.REFRESH_TOKENS, """{"refreshToken":""}""").assertInvalid()
            post(ApiRoute.REFRESH_TOKENS, "{}").assertInvalid()

            clock.now += 90.days
            refresh(first.refreshToken).assertUnauthorized("""Bearer error="invalid_token"""")
            assertEquals(HttpStatusCode.OK, signIn().status)
        }

    @Test
    fun `an operation for signed-in users is told who calls, and refuses a call without a token that works`() =
        serverTest(whoAmI) {
            val alex = tokens(phone)
            val dana = tokens(tablet)

            assertEquals(alex.userId, whoAmI(alex.accessToken).bodyAsText())
            assertEquals(dana.userId, whoAmI(dana.accessToken).bodyAsText())
            assertEquals(
                alex.userId,
                client
                    .get(ApiRoute.GET_PROFILE.path) {
                        header(HttpHeaders.Authorization, "bearer ${alex.accessToken}")
                    }.bodyAsText(),
            )

            whoAmI(accessToken = null).assertUnauthorized("Bearer")
            client.get(ApiRoute.GET_PROFILE.path) { header(HttpHeaders.Authorization, "Basic YTpi") }.assertUnauthorized("Bearer")
            whoAmI("at_nobody-issued-this").assertUnauthorized("""Bearer error="invalid_token"""")
            whoAmI(alex.refreshToken).assertUnauthorized("""Bearer error="invalid_token"""")
        }

    @Test
    fun `an access token works for an hour and not a second longer`() =
        serverTest(whoAmI) {
            val alex = tokens()

            clock.now += 1.hours - 1.seconds
            assertEquals(alex.userId, whoAmI(alex.accessToken).bodyAsText())

            clock.now += 1.seconds
            whoAmI(alex.accessToken).assertUnauthorized("""Bearer error="invalid_token"""")
            assertEquals(HttpStatusCode.OK, refresh(alex.refreshToken).status)
        }

    @Test
    fun `the database holds hashes of the credentials and none of them as it was given`() =
        serverTest {
            val alex = tokens()
            val schema = Database.SCHEMA

            assertEquals(
                listOf(sha256(phone)),
                TestPostgres.strings(database, "select encode(installation_id_hash, 'hex') from $schema.users"),
            )
            assertEquals(
                listOf(sha256(alex.accessToken)),
                TestPostgres.strings(database, "select encode(token_hash, 'hex') from $schema.access_tokens"),
            )
            assertEquals(
                listOf(sha256(alex.refreshToken)),
                TestPostgres.strings(database, "select encode(token_hash, 'hex') from $schema.refresh_tokens"),
            )
        }

    @Test
    fun `tokens that no longer work are dropped when their user gets new ones`() =
        serverTest {
            tokens()
            clock.now += 91.days

            tokens()

            assertEquals(listOf("1"), TestPostgres.strings(database, "select count(*) from ${Database.SCHEMA}.access_tokens"))
            assertEquals(listOf("1"), TestPostgres.strings(database, "select count(*) from ${Database.SCHEMA}.refresh_tokens"))
        }
}
