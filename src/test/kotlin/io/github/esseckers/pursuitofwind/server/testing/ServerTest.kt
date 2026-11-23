package io.github.esseckers.pursuitofwind.server.testing

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ProblemDto
import io.github.esseckers.pursuitofwind.server.db.Database
import io.github.esseckers.pursuitofwind.server.module
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals

/**
 * Runs [test] against the whole server: real HTTP handling in memory, on a real database of its own with this
 * build's schema. [extra] adds what a test needs beside the server's own routes.
 */
internal fun serverTest(
    extra: Application.() -> Unit = {},
    test: suspend ApplicationTestBuilder.(Database) -> Unit,
) = testApplication {
    TestPostgres.withDatabase { config ->
        Database(config).use { database ->
            database.migrate()
            application {
                module(database)
                extra()
            }
            test(database)
        }
    }
}

/** The problem a failed call answered with; fails unless it came as `application/problem+json`. */
internal suspend fun HttpResponse.problem(): ProblemDto {
    assertEquals(ContentType.parse(ProblemDto.MEDIA_TYPE), contentType()?.withoutParameters())
    return ContractJson.decodeFromString(ProblemDto.serializer(), bodyAsText())
}
