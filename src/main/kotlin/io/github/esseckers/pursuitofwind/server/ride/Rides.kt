package io.github.esseckers.pursuitofwind.server.ride

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.CreateRideRequestDto
import io.github.esseckers.pursuitofwind.contract.PersonDto
import io.github.esseckers.pursuitofwind.contract.RideDto
import io.github.esseckers.pursuitofwind.contract.RideStatusDto
import io.github.esseckers.pursuitofwind.server.auth.Credentials
import kotlinx.serialization.builtins.ListSerializer
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * The rides of crews and who has a seat in them, in the database. A ride is read whole in one statement, so its
 * passengers and its version are of the same moment. Whoever changes a ride holds its crew first (ADR 0008).
 */
internal object Rides {
    /** A ride with its driver and its passengers, in the order they took their seats, under the names they have now. */
    private const val RIDE =
        """
        select r.*, d.display_name as driver_name,
            cast(coalesce((
                select jsonb_agg(jsonb_build_object('userId', p.user_id, 'displayName', n.display_name) order by p.position)
                from ride_passengers p join people n on n.user_id = p.user_id
                where p.ride_id = r.id
            ), '[]') as text) as passengers
        from rides r join people d on d.user_id = r.driver_id
        """
    private val PEOPLE = ListSerializer(PersonDto.serializer())

    /** The ride [id], or null if there is none. */
    fun find(
        handle: Handle,
        id: String,
    ): RideDto? =
        handle
            .createQuery("$RIDE where r.id = :id")
            .bind("id", id)
            .map { row, _ -> ride(row) }
            .findOne()
            .orElse(null)

    /** The id of the crew the ride [id] belongs to, which never changes; null if there is no such ride. */
    fun crewOf(
        handle: Handle,
        id: String,
    ): String? =
        handle
            .createQuery("select crew_id from rides where id = :id")
            .bind("id", id)
            .map { row, _ -> row.getString("crew_id") }
            .findOne()
            .orElse(null)

    /**
     * The rides of [crewId] whose window ends after [endingAfter], cancelled ones included, by departure and, when
     * that is the same, in the order they were offered.
     */
    fun of(
        handle: Handle,
        crewId: String,
        endingAfter: Instant,
    ): List<RideDto> =
        handle
            .createQuery("$RIDE where r.crew_id = :crew and r.window_end > :after order by r.depart_at, r.position")
            .bind("crew", crewId)
            .bind("after", endingAfter.toJavaInstant())
            .map { row, _ -> ride(row) }
            .list()

    /** A new ride in [crewId] as [request] plans it, driven by [driverId]. */
    fun create(
        handle: Handle,
        crewId: String,
        driverId: String,
        request: CreateRideRequestDto,
        now: Instant,
    ): RideDto {
        val id = Credentials.newId("rid")
        handle
            .createUpdate(
                """
                insert into rides (
                    id, crew_id, driver_id, spot_id, window_start, window_end, depart_at, meeting_point, seats_total,
                    status, version, created_at
                ) values (:id, :crew, :driver, :spot, :start, :end, :depart, :meeting, :seats, 'SCHEDULED', 1, :now)
                """,
            ).bind("id", id)
            .bind("crew", crewId)
            .bind("driver", driverId)
            .bind("spot", request.spotId)
            .bind("start", request.windowStart.toJavaInstant())
            .bind("end", request.windowEnd.toJavaInstant())
            .bind("depart", request.departAt.toJavaInstant())
            .bind("meeting", request.meetingPoint)
            .bind("seats", request.seatsTotal)
            .bind("now", now.toJavaInstant())
            .execute()
        return changed(handle, id, bump = false)
    }

    /** Calls the ride [id] off. It stays where it was, as cancelled. */
    fun cancel(
        handle: Handle,
        id: String,
    ): RideDto {
        handle.createUpdate("update rides set status = 'CANCELLED' where id = :id").bind("id", id).execute()
        return changed(handle, id)
    }

    /** Gives [userId] a seat in the ride [id]. That there is one free, and that they have none yet, is for the caller. */
    fun takeSeat(
        handle: Handle,
        id: String,
        userId: String,
    ): RideDto {
        handle
            .createUpdate("insert into ride_passengers (ride_id, user_id) values (:ride, :user)")
            .bind("ride", id)
            .bind("user", userId)
            .execute()
        return changed(handle, id)
    }

    /** Frees the seat [userId] has in the ride [id]. */
    fun releaseSeat(
        handle: Handle,
        id: String,
        userId: String,
    ): RideDto {
        handle
            .createUpdate("delete from ride_passengers where ride_id = :ride and user_id = :user")
            .bind("ride", id)
            .bind("user", userId)
            .execute()
        return changed(handle, id)
    }

    /** The ride [id] as it is after a change, which is one version further unless the change made the ride. */
    private fun changed(
        handle: Handle,
        id: String,
        bump: Boolean = true,
    ): RideDto {
        if (bump) handle.createUpdate("update rides set version = version + 1 where id = :id").bind("id", id).execute()
        return checkNotNull(find(handle, id))
    }

    private fun ride(row: ResultSet): RideDto {
        val passengers = ContractJson.decodeFromString(PEOPLE, row.getString("passengers"))
        val seats = row.getInt("seats_total")
        return RideDto(
            id = row.getString("id"),
            version = row.getInt("version"),
            crewId = row.getString("crew_id"),
            spotId = row.getString("spot_id"),
            driver = PersonDto(row.getString("driver_id"), row.getString("driver_name")),
            windowStart = row.getTimestamp("window_start").toInstant().toKotlinInstant(),
            windowEnd = row.getTimestamp("window_end").toInstant().toKotlinInstant(),
            departAt = row.getTimestamp("depart_at").toInstant().toKotlinInstant(),
            meetingPoint = row.getString("meeting_point"),
            seatsTotal = seats,
            seatsFree = seats - passengers.size,
            passengers = passengers,
            status = RideStatusDto.valueOf(row.getString("status")),
            createdAt = row.getTimestamp("created_at").toInstant().toKotlinInstant(),
        )
    }
}
