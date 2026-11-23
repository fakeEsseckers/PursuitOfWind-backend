package io.github.esseckers.pursuitofwind.server

import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.engine.DecisionEngine
import io.github.esseckers.pursuitofwind.engine.model.RiderProfile
import io.github.esseckers.pursuitofwind.engine.model.SkillLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The server stands on two artifacts of the client repository. This fails first, and plainly, when the build cannot
 * reach them or they are not the ones the server expects.
 */
class ClientArtifactsTest {
    @Test
    fun `the contract names the routes the server will implement`() {
        assertTrue(ApiRoute.entries.any { it.path == "/v1/auth/anonymous" })
        assertTrue(ApiRoute.entries.all { it.path.startsWith("/v1/") })
    }

    @Test
    fun `the engine decides here as it does in the app`() {
        val rider = RiderProfile(weightKg = 80.0, level = SkillLevel.INTERMEDIATE, quiverM2 = listOf(7.0, 9.0, 12.0))

        val days = DecisionEngine.decideDays(forecasts = emptyList(), profile = rider, now = Instant.parse("2026-10-22T06:00:00Z"))

        assertEquals(emptyList(), days)
    }
}
