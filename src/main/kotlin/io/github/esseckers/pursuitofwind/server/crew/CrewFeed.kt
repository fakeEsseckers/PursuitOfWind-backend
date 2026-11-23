package io.github.esseckers.pursuitofwind.server.crew

import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.github.esseckers.pursuitofwind.contract.CrewEventDto
import io.github.esseckers.pursuitofwind.contract.CrewFeedDto
import io.github.esseckers.pursuitofwind.server.api.feedReset
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.Query

/**
 * The feed of a crew: what happened in it, in the order it happened, each event as the contract's JSON it was
 * written as. The ids are `evt_<number>`; to a client they are opaque.
 */
internal object CrewFeed {
    private const val ID_PREFIX = "evt_"
    private val ID = Regex("evt_[1-9][0-9]{0,17}")

    /**
     * Adds what [event] builds, given its id, to the feed of [crewId]. The caller holds the crew ([Crews.ofMember],
     * [Crews.join]): that is what makes the numbers of a crew's events the order they happened in.
     */
    fun add(
        handle: Handle,
        crewId: String,
        event: (id: String) -> CrewEventDto,
    ) {
        val number = handle.createQuery("select nextval('crew_event_numbers')").map { row, _ -> row.getLong(1) }.one()
        handle
            .createUpdate("insert into crew_events (number, crew_id, event) values (:number, :crew, cast(:event as jsonb))")
            .bind("number", number)
            .bind("crew", crewId)
            .bind("event", ContractJson.encodeToString(CrewEventDto.serializer(), event(ID_PREFIX + number)))
            .execute()
    }

    /** The newest [limit] events of [crewId], oldest first: where a reader without a place in the feed starts. */
    fun newest(
        handle: Handle,
        crewId: String,
        limit: Int,
    ): CrewFeedDto {
        val events =
            handle
                .createQuery(
                    """
                    select event from (
                        select number, cast(event as text) as event from crew_events
                        where crew_id = :crew order by number desc limit :limit
                    ) newest
                    order by number
                    """,
                ).bind("crew", crewId)
                .bind("limit", limit)
                .events()
        return CrewFeedDto(events, hasMore = false, lastEventId = events.lastOrNull()?.id)
    }

    /**
     * Up to [limit] events of [crewId] after the event [since], oldest first, and whether more follow. A [since]
     * that is not an event of this crew's feed is `410 FEED_RESET`: the reader starts over.
     */
    fun after(
        handle: Handle,
        crewId: String,
        since: String,
        limit: Int,
    ): CrewFeedDto {
        val number = since.takeIf(ID::matches)?.removePrefix(ID_PREFIX)?.toLong()
        if (number == null || !has(handle, crewId, number)) throw feedReset()
        // One more than asked for says whether more follow.
        val found =
            handle
                .createQuery(
                    """
                    select cast(event as text) as event from crew_events
                    where crew_id = :crew and number > :number order by number limit :limit
                    """,
                ).bind("crew", crewId)
                .bind("number", number)
                .bind("limit", limit + 1)
                .events()
        val page = found.take(limit)
        return CrewFeedDto(page, hasMore = found.size > limit, lastEventId = page.lastOrNull()?.id)
    }

    private fun has(
        handle: Handle,
        crewId: String,
        number: Long,
    ): Boolean =
        handle
            .createQuery("select exists (select 1 from crew_events where crew_id = :crew and number = :number)")
            .bind("crew", crewId)
            .bind("number", number)
            .map { row, _ -> row.getBoolean(1) }
            .one()

    private fun Query.events(): List<CrewEventDto> =
        map { row, _ -> ContractJson.decodeFromString(CrewEventDto.serializer(), row.getString("event")) }.list()
}
