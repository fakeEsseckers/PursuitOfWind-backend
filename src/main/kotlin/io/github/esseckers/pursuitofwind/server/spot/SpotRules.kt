package io.github.esseckers.pursuitofwind.server.spot

import io.github.esseckers.pursuitofwind.contract.ApiLimits
import io.github.esseckers.pursuitofwind.contract.GeoPointDto
import io.github.esseckers.pursuitofwind.server.api.check422
import io.github.esseckers.pursuitofwind.server.api.requireText
import java.time.ZoneId

/**
 * The limits of the contract's `CreateSpotRequest`, `SpotUpdate` and `MySpotUpdate`, the ones the app's in-app
 * backend checks; a request that breaks one is `422 VALIDATION_FAILED`.
 */
internal object SpotRules {
    private val LATITUDES = -90.0..90.0
    private val LONGITUDES = -180.0..180.0
    private const val FULL_TURN_DEG = 360.0
    private val DRIVE_MINUTES = 0..ApiLimits.MySpot.DRIVE_MAX_MINUTES
    private val NOT_REGIONS = listOf("Etc/", "SystemV/")

    /** A spot as a rider gives it; [timeZone] comes only with a new spot. */
    fun check(
        name: String,
        location: GeoPointDto,
        waterBearingDeg: Double,
        timeZone: String? = null,
    ) {
        requireText(name, "name", ApiLimits.Spot.NAME_MAX_LENGTH)
        check422(location.lat in LATITUDES && location.lon in LONGITUDES) {
            "location must have a latitude from -90 to 90 and a longitude from -180 to 180."
        }
        check422(waterBearingDeg >= 0.0 && waterBearingDeg < FULL_TURN_DEG) { "waterBearingDeg must be from 0 up to 360." }
        check422(timeZone == null || isRegionZone(timeZone)) { "timeZone is not an IANA time zone this server knows." }
    }

    fun checkDrive(minutes: Int) = check422(minutes in DRIVE_MINUTES) { "driveMinutes must be from 0 to ${DRIVE_MINUTES.last}." }

    /**
     * An IANA region id this server knows (`Europe/Berlin`), or `UTC`; not a fixed offset such as `+02:00` or `GMT+1`.
     * A fixed offset written as a region (`Etc/GMT+1`) is one all the same, and the `SystemV` ids are not in every
     * platform's zone data, so a spot with one could not be shown on every rider's phone.
     */
    private fun isRegionZone(id: String): Boolean =
        id == "UTC" || id == "Etc/UTC" || ('/' in id && NOT_REGIONS.none(id::startsWith) && id in ZoneId.getAvailableZoneIds())
}
