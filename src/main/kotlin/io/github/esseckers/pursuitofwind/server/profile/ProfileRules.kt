package io.github.esseckers.pursuitofwind.server.profile

import io.github.esseckers.pursuitofwind.contract.ApiLimits
import io.github.esseckers.pursuitofwind.contract.RiderProfileDto
import io.github.esseckers.pursuitofwind.server.api.check422

/**
 * The limits of the contract's `RiderProfile` and the rules between its fields that the schema states in words.
 * They are the ones the app's in-app backend checks, with the limits from the same `ApiLimits`; a profile that breaks
 * one is `422 VALIDATION_FAILED`.
 */
internal object ProfileRules {
    private val limits = ApiLimits.RiderProfile
    private val WEIGHT_KG = limits.WEIGHT_MIN_KG..limits.WEIGHT_MAX_KG
    private val SESSION_MINUTES = limits.MIN_SESSION_MIN_MINUTES..limits.MIN_SESSION_MAX_MINUTES
    private val DRIVE_MINUTES = 0..limits.MAX_DRIVE_MAX_MINUTES
    private val FROM_HOURS = 0..limits.RIDING_FROM_HOUR_MAX
    private val TO_HOURS = limits.RIDING_TO_HOUR_MIN..limits.RIDING_TO_HOUR_MAX

    fun check(profile: RiderProfileDto) {
        name(profile.displayName)
        check422(
            profile.weightKg in WEIGHT_KG,
        ) { "weightKg must be between ${WEIGHT_KG.start.plain()} and ${WEIGHT_KG.endInclusive.plain()}." }
        quiver(profile.quiverM2)
        wind(profile.minWindMps, profile.maxWindMps)
        check422(profile.minSessionMinutes in SESSION_MINUTES) {
            "minSessionMinutes must be from ${SESSION_MINUTES.first} to ${SESSION_MINUTES.last}."
        }
        check422(profile.maxDriveMinutes in DRIVE_MINUTES) { "maxDriveMinutes must be from 0 to ${DRIVE_MINUTES.last}." }
        check422(profile.ridingFromHour in FROM_HOURS) { "ridingFromHour must be from 0 to ${FROM_HOURS.last}." }
        check422(profile.ridingToHour in TO_HOURS) { "ridingToHour must be from ${TO_HOURS.first} to ${TO_HOURS.last}." }
        check422(profile.ridingFromHour < profile.ridingToHour) { "ridingFromHour must be below ridingToHour." }
    }

    /** The schema's `minLength: 1`, `maxLength` in code points and `pattern: '\S'`. */
    private fun name(displayName: String) {
        val max = limits.DISPLAY_NAME_MAX_LENGTH
        check422(displayName.any { !it.isWhitespace() } && ApiLimits.lengthOf(displayName) <= max) {
            "displayName must have 1 to $max characters and not only spaces."
        }
    }

    private fun quiver(sizesM2: List<Double>) {
        check422(sizesM2.size <= limits.QUIVER_MAX_KITES) { "quiverM2 has at most ${limits.QUIVER_MAX_KITES} kites." }
        check422(sizesM2.toSet().size == sizesM2.size) { "quiverM2 lists a size twice." }
        check422(sizesM2.all { it > 0.0 && it <= limits.KITE_MAX_M2 }) {
            "Kite sizes must be above 0 and at most ${limits.KITE_MAX_M2.plain()} m²."
        }
    }

    private fun wind(
        minMps: Double?,
        maxMps: Double?,
    ) {
        check422(listOfNotNull(minMps, maxMps).all { it >= 0.0 }) { "Wind limits must not be negative." }
        check422(minMps == null || maxMps == null || minMps < maxMps) { "minWindMps must be below maxWindMps." }
    }

    /** `30` for 30.0, as the contract writes its limits. */
    private fun Double.plain(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()
}
