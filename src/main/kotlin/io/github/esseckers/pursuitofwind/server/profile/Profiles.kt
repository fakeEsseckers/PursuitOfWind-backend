package io.github.esseckers.pursuitofwind.server.profile

import io.github.esseckers.pursuitofwind.contract.RiderProfileDto
import io.github.esseckers.pursuitofwind.contract.SkillLevelDto
import io.github.esseckers.pursuitofwind.contract.WindUnitDto
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** The riders' profiles in the database: one per user, read and replaced as a whole. */
internal object Profiles {
    /** The profile of [userId], or null if they have not saved one. */
    fun find(
        handle: Handle,
        userId: String,
    ): RiderProfileDto? =
        handle
            .createQuery("select * from profiles where user_id = :user")
            .bind("user", userId)
            .map { row, _ -> profile(row) }
            .findOne()
            .orElse(null)

    /** Saves [profile] as the profile of [userId], in place of the one they had. */
    fun put(
        handle: Handle,
        userId: String,
        profile: RiderProfileDto,
        now: Instant,
    ) {
        handle
            .createUpdate(
                """
                insert into profiles (
                    user_id, display_name, weight_kg, level, quiver_m2, min_wind_mps, max_wind_mps,
                    min_session_minutes, max_drive_minutes, riding_from_hour, riding_to_hour, wind_unit, updated_at
                ) values (
                    :user, :name, :weight, :level, :quiver, :minWind, :maxWind,
                    :minSession, :maxDrive, :fromHour, :toHour, :windUnit, :now
                )
                on conflict (user_id) do update set
                    display_name = excluded.display_name,
                    weight_kg = excluded.weight_kg,
                    level = excluded.level,
                    quiver_m2 = excluded.quiver_m2,
                    min_wind_mps = excluded.min_wind_mps,
                    max_wind_mps = excluded.max_wind_mps,
                    min_session_minutes = excluded.min_session_minutes,
                    max_drive_minutes = excluded.max_drive_minutes,
                    riding_from_hour = excluded.riding_from_hour,
                    riding_to_hour = excluded.riding_to_hour,
                    wind_unit = excluded.wind_unit,
                    updated_at = excluded.updated_at
                """,
            ).bind("user", userId)
            .bind("name", profile.displayName)
            .bind("weight", profile.weightKg)
            .bind("level", profile.level.name)
            .bindArray("quiver", Double::class.javaObjectType, profile.quiverM2)
            .bind("minWind", profile.minWindMps)
            .bind("maxWind", profile.maxWindMps)
            .bind("minSession", profile.minSessionMinutes)
            .bind("maxDrive", profile.maxDriveMinutes)
            .bind("fromHour", profile.ridingFromHour)
            .bind("toHour", profile.ridingToHour)
            .bind("windUnit", profile.windUnit.name)
            .bind("now", now.toJavaInstant())
            .execute()
    }

    private fun profile(row: ResultSet) =
        RiderProfileDto(
            displayName = row.getString("display_name"),
            weightKg = row.getDouble("weight_kg"),
            level = SkillLevelDto.valueOf(row.getString("level")),
            quiverM2 = (row.getArray("quiver_m2").array as Array<*>).map { it as Double },
            minWindMps = row.getObject("min_wind_mps") as Double?,
            maxWindMps = row.getObject("max_wind_mps") as Double?,
            minSessionMinutes = row.getInt("min_session_minutes"),
            maxDriveMinutes = row.getInt("max_drive_minutes"),
            ridingFromHour = row.getInt("riding_from_hour"),
            ridingToHour = row.getInt("riding_to_hour"),
            windUnit = WindUnitDto.valueOf(row.getString("wind_unit")),
        )
}
