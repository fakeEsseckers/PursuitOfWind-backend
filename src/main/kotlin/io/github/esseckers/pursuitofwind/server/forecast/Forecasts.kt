package io.github.esseckers.pursuitofwind.server.forecast

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ForecastHourDto
import io.github.esseckers.pursuitofwind.contract.SpotDto
import io.github.esseckers.pursuitofwind.server.api.unavailable
import io.github.esseckers.pursuitofwind.server.db.Database
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * The forecast of a spot, fetched from the source at most once in [FRESH_FOR] and kept in the database between.
 *
 * - Riders looking at one spot share one forecast, so the number of requests to the source grows with the spots
 *   people look at, not with the people.
 * - When the source does not answer, the forecast that is there is given, however old: `generatedAt` says how old.
 *   Only a spot that has no forecast at all is `503 SERVICE_UNAVAILABLE`.
 * - A spot that moved gets the forecast of its new place.
 */
internal class Forecasts(
    private val database: Database,
    private val source: ForecastSource,
) {
    private val log = LoggerFactory.getLogger(Forecasts::class.java)

    /** One fetch per spot at a time in this process; a second caller waits for the first and takes what it got. */
    private val fetching = ConcurrentHashMap<String, Mutex>()

    /** The forecast of [spot]: every hour the source gave, and when it gave them. */
    suspend fun of(
        spot: SpotDto,
        now: Instant,
    ): Forecast {
        kept(spot)?.takeIf { it.isFresh(now) }?.let { return it }
        return fetching.getOrPut(spot.id) { Mutex() }.withLock {
            val kept = kept(spot)
            if (kept != null && kept.isFresh(now)) kept else fetched(spot, now) ?: kept ?: throw unavailable()
        }
    }

    private fun Forecast.isFresh(now: Instant) = fetchedAt > now - FRESH_FOR

    /** What the source has for [spot] now, kept for the next caller; null if the source did not answer. */
    private suspend fun fetched(
        spot: SpotDto,
        now: Instant,
    ): Forecast? =
        try {
            Forecast(now, source.hours(spot)).also { keep(spot, it) }
        } catch (e: ForecastSourceException) {
            log.warn("No forecast for the spot {}: {}", spot.id, e.message)
            null
        }

    /** The forecast kept for [spot], if it was fetched for the place the spot is at now. */
    private suspend fun kept(spot: SpotDto): Forecast? =
        database.read { handle ->
            handle
                .createQuery(
                    "select fetched_at, cast(hours as text) as hours from forecasts where spot_id = :spot and lat = :lat and lon = :lon",
                ).bind("spot", spot.id)
                .bind("lat", spot.location.lat)
                .bind("lon", spot.location.lon)
                .map { row, _ ->
                    Forecast(
                        row.getTimestamp("fetched_at").toInstant().toKotlinInstant(),
                        ContractJson.decodeFromString(HOURS, row.getString("hours")),
                    )
                }.findOne()
                .orElse(null)
        }

    private suspend fun keep(
        spot: SpotDto,
        forecast: Forecast,
    ) {
        database.transaction { handle ->
            handle
                .createUpdate(
                    """
                    insert into forecasts (spot_id, lat, lon, fetched_at, hours)
                    values (:spot, :lat, :lon, :at, cast(:hours as jsonb))
                    on conflict (spot_id) do update
                    set lat = excluded.lat, lon = excluded.lon, fetched_at = excluded.fetched_at, hours = excluded.hours
                    """,
                ).bind("spot", spot.id)
                .bind("lat", spot.location.lat)
                .bind("lon", spot.location.lon)
                .bind("at", forecast.fetchedAt.toJavaInstant())
                .bind("hours", ContractJson.encodeToString(HOURS, forecast.hours))
                .execute()
        }
    }

    companion object {
        /** The models behind the source run every few hours; asking more often than this brings nothing new. */
        val FRESH_FOR = 1.hours
        private val HOURS = ListSerializer(ForecastHourDto.serializer())
    }
}

/** Every hour the source gave for a spot, oldest first, and when it gave them. */
internal class Forecast(
    val fetchedAt: Instant,
    val hours: List<ForecastHourDto>,
)
