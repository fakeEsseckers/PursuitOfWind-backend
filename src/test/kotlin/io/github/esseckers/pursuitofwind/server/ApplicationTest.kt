package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ProblemCode
import io.github.esseckers.pursuitofwind.contract.ProblemDto
import io.github.esseckers.pursuitofwind.server.api.respondProblem
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** What every route of the server shares, through real HTTP handling in memory. */
class ApplicationTest {
    private fun server(
        extra: Application.() -> Unit = {},
        test: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            module()
            extra()
        }
        test()
    }

    private suspend fun HttpResponse.problem(): ProblemDto {
        assertEquals(ContentType.parse(ProblemDto.MEDIA_TYPE), contentType()?.withoutParameters())
        return ContractJson.decodeFromString(ProblemDto.serializer(), bodyAsText())
    }

    @Test
    fun `health says the server is up`() =
        server {
            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"status":"ok"}""", response.bodyAsText())
        }

    @Test
    fun `a path the server does not know is a problem the client can read`() =
        server {
            val response = client.get("/v1/nowhere")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(
                ProblemDto(
                    type = "/problems/not-found",
                    title = "Not found",
                    status = 404,
                    code = ProblemCode.NOT_FOUND,
                    detail = "No such route.",
                ),
                response.problem(),
            )
        }

    @Test
    fun `a method the path does not take is a problem too`() =
        server {
            val response = client.post("/health")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ProblemCode.NOT_FOUND, response.problem().code)
        }

    @Test
    fun `a route's own not found keeps its code`() =
        server(
            extra = {
                routing {
                    get("/v1/crews/join") {
                        call.respondProblem(HttpStatusCode.NotFound, ProblemCode.INVITE_INVALID, "Invite is not valid")
                    }
                }
            },
        ) {
            val response = client.get("/v1/crews/join")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ProblemCode.INVITE_INVALID, response.problem().code)
        }

    @Test
    fun `a failure inside the server is a problem that says nothing about the inside`() =
        server(extra = { routing { get("/boom") { error("the database password is hunter2") } } }) {
            val response = client.get("/boom")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(
                ProblemDto(type = "/problems/internal", title = "Internal error", status = 500, code = ProblemCode.INTERNAL),
                response.problem(),
            )
            assertFalse("hunter2" in response.bodyAsText())
        }

    @Test
    fun `a request gets an id and keeps the one it came with`() =
        server {
            val made = client.get("/health").headers[HttpHeaders.XRequestId]
            val kept = client.get("/health") { header(HttpHeaders.XRequestId, "req-42") }.headers[HttpHeaders.XRequestId]

            assertNotNull(made)
            assertEquals("req-42", kept)
        }
}
