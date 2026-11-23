package io.github.esseckers.pursuitofwind.server.spot

import io.github.esseckers.pursuitofwind.contract.CreateSpotRequestDto
import io.github.esseckers.pursuitofwind.contract.GeoPointDto
import io.github.esseckers.pursuitofwind.contract.MySpotDto
import io.github.esseckers.pursuitofwind.contract.MySpotUpdateDto
import io.github.esseckers.pursuitofwind.contract.SectorDto
import io.github.esseckers.pursuitofwind.contract.SpotDto
import io.github.esseckers.pursuitofwind.contract.SpotUpdateDto
import io.github.esseckers.pursuitofwind.engine.DecisionEngine
import io.github.esseckers.pursuitofwind.engine.model.Bearing
import io.github.esseckers.pursuitofwind.engine.model.Sector
import io.github.esseckers.pursuitofwind.server.auth.Credentials
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/** The catalog of spots and the spots each rider follows, in the database. */
internal object Spots {
    /** The spot [id], or null if the catalog has none. [forChange] holds it until the transaction ends. */
    fun find(
        handle: Handle,
        id: String,
        forChange: Boolean = false,
    ): SpotDto? =
        handle
            .createQuery("select * from spots where id = :id" + if (forChange) " for update" else "")
            .bind("id", id)
            .map { row, _ -> spot(row) }
            .findOne()
            .orElse(null)

    /** Up to [limit] spots in the order of their ids, starting after the id [after] or from the first. */
    fun page(
        handle: Handle,
        after: String?,
        limit: Int,
    ): List<SpotDto> =
        handle
            .createQuery("select * from spots where (cast(:after as text) is null or id > :after) order by id limit :limit")
            .bind("after", after)
            .bind("limit", limit)
            .map { row, _ -> spot(row) }
            .list()

    /** Adds the spot [request] describes to the catalog, as [userId]'s. */
    fun create(
        handle: Handle,
        userId: String,
        request: CreateSpotRequestDto,
        now: Instant,
    ): SpotDto {
        val id = Credentials.newId("spt")
        handle
            .createUpdate(
                """
                insert into spots (id, name, lat, lon, time_zone, water_bearing_deg, created_by, created_at)
                values (:id, :name, :lat, :lon, :zone, :water, :user, :now)
                """,
            ).bind("id", id)
            .bind("name", request.name)
            .bind("lat", request.location.lat)
            .bind("lon", request.location.lon)
            .bind("zone", request.timeZone)
            .bind("water", request.waterBearingDeg)
            .bind("user", userId)
            .bind("now", now.toJavaInstant())
            .execute()
        return checkNotNull(find(handle, id))
    }

    /** Renames or moves the spot [id], or turns its water. Who added it, and when, stay. */
    fun update(
        handle: Handle,
        id: String,
        update: SpotUpdateDto,
    ): SpotDto {
        handle
            .createUpdate("update spots set name = :name, lat = :lat, lon = :lon, water_bearing_deg = :water where id = :id")
            .bind("id", id)
            .bind("name", update.name)
            .bind("lat", update.location.lat)
            .bind("lon", update.location.lon)
            .bind("water", update.waterBearingDeg)
            .execute()
        return checkNotNull(find(handle, id))
    }

    /** The spots [userId] follows, in the order of their ids. */
    fun followed(
        handle: Handle,
        userId: String,
    ): List<MySpotDto> =
        handle
            .createQuery(
                """
                select s.*, m.drive_minutes, m.favorite
                from my_spots m join spots s on s.id = m.spot_id
                where m.user_id = :user
                order by s.id
                """,
            ).bind("user", userId)
            .map { row, _ -> MySpotDto(spot(row), row.getInt("drive_minutes"), row.getBoolean("favorite")) }
            .list()

    /** Makes [userId] follow the spot [spotId] with these settings, in place of the ones they had for it. */
    fun follow(
        handle: Handle,
        userId: String,
        spotId: String,
        settings: MySpotUpdateDto,
    ) {
        handle
            .createUpdate(
                """
                insert into my_spots (user_id, spot_id, drive_minutes, favorite)
                values (:user, :spot, :drive, :favorite)
                on conflict (user_id, spot_id) do update set drive_minutes = excluded.drive_minutes, favorite = excluded.favorite
                """,
            ).bind("user", userId)
            .bind("spot", spotId)
            .bind("drive", settings.driveMinutes)
            .bind("favorite", settings.favorite)
            .execute()
    }

    /** Makes [userId] stop following the spot [spotId]; nothing happens if they did not. */
    fun unfollow(
        handle: Handle,
        userId: String,
        spotId: String,
    ) {
        handle
            .createUpdate("delete from my_spots where user_id = :user and spot_id = :spot")
            .bind("user", userId)
            .bind("spot", spotId)
            .execute()
    }

    /** A row of `spots` as the contract's spot, with the sectors the decision engine gives a beach facing that way. */
    private fun spot(row: ResultSet): SpotDto {
        val water = row.getDouble("water_bearing_deg")
        val sectors = DecisionEngine.sectorsFacing(Bearing(water))
        return SpotDto(
            id = row.getString("id"),
            name = row.getString("name"),
            location = GeoPointDto(row.getDouble("lat"), row.getDouble("lon")),
            timeZone = row.getString("time_zone"),
            rideableSectors = listOf(sectors.rideable.toDto()),
            dangerSectors = listOf(sectors.danger.toDto()),
            waterBearingDeg = water,
            createdBy = row.getString("created_by"),
            createdAt = row.getTimestamp("created_at").toInstant().toKotlinInstant(),
        )
    }

    private fun Sector.toDto() = SectorDto(from.degrees, to.degrees)
}
