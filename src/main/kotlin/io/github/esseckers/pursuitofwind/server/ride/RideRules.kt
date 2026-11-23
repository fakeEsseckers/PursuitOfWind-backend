package io.github.esseckers.pursuitofwind.server.ride

import io.github.esseckers.pursuitofwind.contract.ApiLimits
import io.github.esseckers.pursuitofwind.contract.CreateRideRequestDto
import io.github.esseckers.pursuitofwind.server.api.check422
import io.github.esseckers.pursuitofwind.server.api.requireText
import kotlin.time.Instant

/**
 * The rules of the contract's `CreateRideRequest`, the ones the app's in-app backend checks; a request that breaks one
 * is `422 VALIDATION_FAILED`. That the spot is in the catalog is for the caller to see to.
 */
internal object RideRules {
    private val SEATS = ApiLimits.Ride.SEATS_MIN..ApiLimits.Ride.SEATS_MAX

    /** The moments the database keeps: nobody plans a ride outside them, and one beyond its calendar it cannot hold. */
    private val KEPT = Instant.parse("1970-01-01T00:00:00Z")..Instant.parse("9999-12-31T23:59:59Z")

    /**
     * The plan of [request] as the server keeps it, checked: its moments to the millisecond, as every moment of this
     * API. The rules are asked of those, so that what is kept is what was checked.
     */
    fun plan(request: CreateRideRequestDto): CreateRideRequestDto {
        check422(listOf(request.windowStart, request.windowEnd, request.departAt).all { it in KEPT }) {
            "windowStart, windowEnd and departAt must be moments between the years 1970 and 9999."
        }
        val plan =
            request.copy(
                windowStart = request.windowStart.toMillisecond(),
                windowEnd = request.windowEnd.toMillisecond(),
                departAt = request.departAt.toMillisecond(),
            )
        check422(plan.windowStart < plan.windowEnd) { "windowStart must be before windowEnd." }
        check422(plan.departAt <= plan.windowStart) { "departAt must not be after windowStart." }
        requireText(plan.meetingPoint, "meetingPoint", ApiLimits.Ride.MEETING_POINT_MAX_LENGTH)
        check422(plan.seatsTotal in SEATS) { "seatsTotal must be from ${SEATS.first} to ${SEATS.last}." }
        return plan
    }

    private fun Instant.toMillisecond() = Instant.fromEpochMilliseconds(toEpochMilliseconds())

    /** [moment], or the nearest one the database keeps: a list asked from beyond them is the list from their edge. */
    fun kept(moment: Instant): Instant = moment.coerceIn(KEPT)
}
