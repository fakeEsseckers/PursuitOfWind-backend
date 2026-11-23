package io.github.esseckers.pursuitofwind.server.forecast

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.ForecastHourDto
import io.github.esseckers.pursuitofwind.contract.GeoPointDto
import io.github.esseckers.pursuitofwind.server.api.unavailable
import io.github.esseckers.pursuitofwind.server.db.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * The forecast of a place, fetched from the source at most once in [FRESH_FOR] and kept in the database between.
 *
 * - Every spot at a place shares its forecast, whoever added the spot, so the number of requests to the source grows
 *   with the places people look at, not with the people or the spots.
 * - When the source does not answer, the forecast that is there is given, however old: `generatedAt` says how old.
 *   Only a place that has no forecast at all is `503 SERVICE_UNAVAILABLE`.
 * - The source is asked once at a time for a place: calls that come while it is being asked wait for that answer.
 *   The fetch belongs to the server, not to the call that set it off, so a call that goes away takes nothing from
 *   the others. After a failure the place is left alone for [LEFT_ALONE_AFTER_FAILURE], so a source that is down or
 *   has had enough of this server is not asked again by every call.
 *
 * @param server what fetches run in; they end when it does.
 */
internal class Forecasts(
    private val database: Database,
    private val source: ForecastSource,
    server: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(Forecasts::class.java)

    /** One fetch that fails must not end the others, or the server. */
    private val fetches = CoroutineScope(server.coroutineContext + SupervisorJob(server.coroutineContext[Job]) + Dispatchers.Default)

    /** The fetch that is under way for a place in this process, for everyone who asks meanwhile. */
    private val fetching = ConcurrentHashMap<Place, Deferred<Forecast?>>()

    /** When a fetch last failed for a place. */
    private val failedAt = ConcurrentHashMap<Place, Instant>()

    /** The forecast of the place at [location]: every hour the source gave, and when it gave them. */
    suspend fun at(
        location: GeoPointDto,
        now: Instant,
    ): Forecast {
        val place = Place.of(location)
        val kept = kept(place)
        if (kept != null && kept.isFresh(now)) return kept
        val fetched = if (isLeftAlone(place, now)) null else fetchedOnce(place, now)
        return fetched ?: kept ?: throw unavailable(retryAfterSeconds = comeAgainIn(place, now))
    }

    private fun Forecast.isFresh(now: Instant) = fetchedAt > now - FRESH_FOR

    private fun isLeftAlone(
        place: Place,
        now: Instant,
    ) = failedAt[place]?.let { it > now - LEFT_ALONE_AFTER_FAILURE } ?: false

    /** The seconds until the source is asked about [place] again: what is left of its pause, or at once if it has none. */
    private fun comeAgainIn(
        place: Place,
        now: Instant,
    ): Int =
        failedAt[place]
            ?.let { (it + LEFT_ALONE_AFTER_FAILURE - now).inWholeSeconds.coerceIn(1, LEFT_ALONE_AFTER_FAILURE.inWholeSeconds).toInt() }
            ?: 1

    /** What the fetch for [place] ends with, the one already under way if there is one; null if it got no forecast. */
    private suspend fun fetchedOnce(
        place: Place,
        now: Instant,
    ): Forecast? {
        val mine = fetches.async(start = CoroutineStart.LAZY) { settled(place, now) }
        val underWay = fetching.putIfAbsent(place, mine)
        if (underWay != null) {
            mine.cancel()
            return underWay.await()
        }
        mine.invokeOnCompletion { fetching.remove(place, mine) }
        return mine.await()
    }

    /**
     * The forecast for [place] as things stand once nobody else is fetching it. A call that looked before the last
     * fetch ended and got here after it finds the matter settled, one way or the other, and the source is not asked.
     */
    private suspend fun settled(
        place: Place,
        now: Instant,
    ): Forecast? = kept(place)?.takeIf { it.isFresh(now) } ?: if (isLeftAlone(place, now)) null else fetched(place, now)

    private suspend fun fetched(
        place: Place,
        now: Instant,
    ): Forecast? {
        var fetched: Forecast? = null
        try {
            fetched = Forecast(now, source.hours(place)).also { keep(place, it) }
        } catch (e: ForecastSourceException) {
            log.warn("No forecast for {}: {}", place, e.message)
        } finally {
            // Whatever stood in the way, the source or the database, the place is left alone for a while.
            if (fetched == null) leaveAlone(place, now) else failedAt.remove(place)
        }
        return fetched
    }

    private fun leaveAlone(
        place: Place,
        now: Instant,
    ) {
        failedAt.values.removeIf { it <= now - LEFT_ALONE_AFTER_FAILURE }
        failedAt[place] = now
    }

    /** The forecast kept for [place]. One that can no longer be read, after a change of the contract, is none. */
    private suspend fun kept(place: Place): Forecast? =
        database
            .read { handle ->
                handle
                    .createQuery("select fetched_at, cast(hours as text) as hours from forecasts where place = :place")
                    .bind("place", place.toString())
                    .map { row, _ -> row.getTimestamp("fetched_at").toInstant().toKotlinInstant() to row.getString("hours") }
                    .findOne()
                    .orElse(null)
            }?.let { (fetchedAt, hours) ->
                try {
                    Forecast(fetchedAt, ContractJson.decodeFromString(HOURS, hours))
                } catch (e: IllegalArgumentException) {
                    // What kotlinx.serialization throws for JSON that is not this type, and what a value in it throws.
                    log.warn("The forecast kept for {} cannot be read: {}", place, e.message)
                    null
                }
            }

    /** Keeps [forecast] unless a newer one got there first, and drops the forecasts of places nobody has asked about. */
    private suspend fun keep(
        place: Place,
        forecast: Forecast,
    ) {
        database.transaction { handle ->
            handle
                .createUpdate(
                    """
                    insert into forecasts (place, fetched_at, hours)
                    values (:place, :at, cast(:hours as jsonb))
                    on conflict (place) do update set fetched_at = excluded.fetched_at, hours = excluded.hours
                    where forecasts.fetched_at <= excluded.fetched_at
                    """,
                ).bind("place", place.toString())
                .bind("at", forecast.fetchedAt.toJavaInstant())
                .bind("hours", ContractJson.encodeToString(HOURS, forecast.hours))
                .execute()
        }
        // On its own, and past the rows another fetch is writing: two places kept at one moment, each with an old
        // row, would otherwise wait for each other for ever.
        database.transaction { handle ->
            handle
                .createUpdate(
                    """
                    delete from forecasts
                    where place in (select place from forecasts where fetched_at <= :old for update skip locked)
                    """,
                ).bind("old", (forecast.fetchedAt - FORGOTTEN_AFTER).toJavaInstant())
                .execute()
        }
    }

    companion object {
        /** The models behind the source run every few hours; asking more often than this brings nothing new. */
        val FRESH_FOR = 1.hours

        /** How long a source that failed for a place is not asked about it again. */
        val LEFT_ALONE_AFTER_FAILURE = 5.minutes

        /** A forecast nobody has asked about for this long is of no use to anyone. */
        private val FORGOTTEN_AFTER = 7.days
        private val HOURS = ListSerializer(ForecastHourDto.serializer())
    }
}

/** Every hour the source gave for a place, oldest first, and when it gave them. */
internal class Forecast(
    val fetchedAt: Instant,
    val hours: List<ForecastHourDto>,
)

/**
 * Where a forecast is for: a latitude and longitude in hundredths of a degree. That is about a kilometre, and the
 * grids of the weather models are no finer, so spots a few hundred metres apart are one place and share one forecast.
 */
internal data class Place(
    private val latHundredths: Long,
    private val lonHundredths: Long,
) {
    val lat: Double get() = latHundredths / HUNDREDTHS
    val lon: Double get() = lonHundredths / HUNDREDTHS

    override fun toString(): String = "$lat,$lon"

    companion object {
        private const val HUNDREDTHS = 100.0

        fun of(location: GeoPointDto) = Place((location.lat * HUNDREDTHS).roundToLong(), (location.lon * HUNDREDTHS).roundToLong())
    }
}
