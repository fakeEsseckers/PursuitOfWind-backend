package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiRoute
import io.github.esseckers.pursuitofwind.contract.CreateRideRequestDto
import io.github.esseckers.pursuitofwind.contract.RideCancelledEventDto
import io.github.esseckers.pursuitofwind.contract.RideCreatedEventDto
import io.github.esseckers.pursuitofwind.contract.RideDto
import io.github.esseckers.pursuitofwind.contract.RideListDto
import io.github.esseckers.pursuitofwind.contract.RideStatusDto
import io.github.esseckers.pursuitofwind.contract.SeatReleasedEventDto
import io.github.esseckers.pursuitofwind.contract.SeatTakenEventDto
import io.github.esseckers.pursuitofwind.server.crew.CrewFeed
import io.github.esseckers.pursuitofwind.server.crew.Crews
import io.github.esseckers.pursuitofwind.server.crew.People
import io.github.esseckers.pursuitofwind.server.ride.RideRules
import io.github.esseckers.pursuitofwind.server.ride.Rides
import io.github.esseckers.pursuitofwind.server.spot.Spots
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route

private const val CREW_ID = "crewId"
private const val RIDE_ID = "rideId"

/**
 * Rides and seats (the contract's tag `Rides`). Every change to a ride makes it one version further and is told in
 * the feed of its crew.
 */
internal fun Route.rideRoutes(api: Api) {
    signedIn(api, ApiRoute.LIST_CREW_RIDES) { userId ->
        val crewId = call.parameters[CREW_ID].orEmpty()
        val rides =
            api.database.read { handle ->
                Crews.ofMember(handle, crewId, userId)
                Rides.of(handle, crewId, endingAfter = RideRules.kept(instantQuery(call.parameters, "from", api.now())))
            }
        call.respondJson(RideListDto.serializer(), RideListDto(rides))
    }
    changing(api, ApiRoute.CREATE_RIDE) { createRide() }
    signedIn(api, ApiRoute.GET_RIDE) { userId ->
        val id = call.parameters[RIDE_ID].orEmpty()
        val ride =
            api.database.read { handle ->
                val ride = Rides.find(handle, id) ?: throw notFound("Ride $id")
                Crews.ofMember(handle, ride.crewId, userId)
                ride
            }
        call.respondJson(RideDto.serializer(), ride)
    }
    changing(api, ApiRoute.CANCEL_RIDE) { cancelRide() }
    changing(api, ApiRoute.TAKE_SEAT) { takeSeat() }
    changing(api, ApiRoute.RELEASE_SEAT) { releaseSeat() }
}

/** A member offers a ride to a spot of the catalog, and drives it. */
private fun Change.createRide(): Answer {
    val crew = Crews.ofMember(handle, parameters[CREW_ID].orEmpty(), userId, held = true)
    val request = body(CreateRideRequestDto.serializer())
    // What is not an id cannot be a spot, and is not asked about.
    check422(isId(request.spotId) && Spots.find(handle, request.spotId) != null) { "spotId is not a spot of the catalog." }
    val ride = Rides.create(handle, crew.id, userId, RideRules.plan(request), now)
    CrewFeed.add(handle, crew.id) { id -> RideCreatedEventDto(id, crew.id, now, ride.driver, ride) }
    return Answer.json(RideDto.serializer(), ride, HttpStatusCode.Created)
}

/** Only the driver calls a ride off. A ride that is off already stays as it is. */
private fun Change.cancelRide(): Answer {
    val ride = heldRide()
    if (ride.driver.userId != userId) throw notTheDriver()
    if (ride.status == RideStatusDto.CANCELLED) return Answer.json(RideDto.serializer(), ride)
    val cancelled = Rides.cancel(handle, ride.id)
    CrewFeed.add(handle, ride.crewId) { id -> RideCancelledEventDto(id, ride.crewId, now, cancelled.driver, ride.id, cancelled.version) }
    return Answer.json(RideDto.serializer(), cancelled)
}

/** A member takes a free seat. The crew is held, so of two riders after the last seat the second finds it taken. */
private fun Change.takeSeat(): Answer {
    val ride = heldRide()
    val refusal =
        when {
            ride.status == RideStatusDto.CANCELLED -> rideCancelled()
            ride.driver.userId == userId || ride.passengers.any { it.userId == userId } -> alreadyJoined()
            ride.seatsFree == 0 -> seatsFull(ride.id)
            else -> null
        }
    if (refusal != null) throw refusal
    val taken = Rides.takeSeat(handle, ride.id, userId)
    val passenger = People.of(handle, userId)
    CrewFeed.add(handle, ride.crewId) { id -> SeatTakenEventDto(id, ride.crewId, now, passenger, ride.id, taken.version, taken.seatsFree) }
    return Answer.json(RideDto.serializer(), taken, HttpStatusCode.Created)
}

/** A passenger gives their seat back. Someone who has none changes nothing. */
private fun Change.releaseSeat(): Answer {
    val ride = heldRide()
    if (ride.passengers.none { it.userId == userId }) return Answer.json(RideDto.serializer(), ride)
    val released = Rides.releaseSeat(handle, ride.id, userId)
    val passenger = People.of(handle, userId)
    CrewFeed.add(handle, ride.crewId) { id ->
        SeatReleasedEventDto(id, ride.crewId, now, passenger, ride.id, released.version, released.seatsFree)
    }
    return Answer.json(RideDto.serializer(), released)
}

/**
 * The ride a change is about, read once its crew is held, so that nothing changes it between this look and the
 * change. `404 NOT_FOUND` if there is no such ride, `403 NOT_A_MEMBER` if the caller is not in its crew.
 */
private fun Change.heldRide(): RideDto {
    val id = parameters[RIDE_ID].orEmpty()
    val crewId = Rides.crewOf(handle, id) ?: throw notFound("Ride $id")
    Crews.ofMember(handle, crewId, userId, held = true)
    return checkNotNull(Rides.find(handle, id))
}
