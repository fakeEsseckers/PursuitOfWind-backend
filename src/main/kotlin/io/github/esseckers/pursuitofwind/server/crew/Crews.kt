package io.github.esseckers.pursuitofwind.server.crew

import io.github.esseckers.pursuitofwind.contract.CrewDto
import io.github.esseckers.pursuitofwind.contract.CrewMemberDto
import io.github.esseckers.pursuitofwind.contract.MemberJoinedEventDto
import io.github.esseckers.pursuitofwind.contract.MemberRoleDto
import io.github.esseckers.pursuitofwind.server.api.notAMember
import io.github.esseckers.pursuitofwind.server.api.notFound
import io.github.esseckers.pursuitofwind.server.auth.Credentials
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/** Crews and who is in them, in the database. */
internal object Crews {
    private const val CREW =
        "select c.*, (select count(*) from crew_members m where m.crew_id = c.id) as member_count from crews c"

    private const val HOLD_BY_ID = "select id from crews where id = :value for no key update"
    private const val HOLD_BY_INVITE_CODE = "select id from crews where invite_code = :value for no key update"

    /** A code that is taken is drawn again; with forty random bits this many draws do not all fail. */
    private const val CODE_DRAWS = 20

    /** A new crew called [name] with [userId] as its owner and only member. */
    fun create(
        handle: Handle,
        userId: String,
        name: String,
        now: Instant,
    ): CrewDto {
        val id = Credentials.newId("crw")
        val code =
            generateSequence(CrewRules::newInviteCode).take(CODE_DRAWS).firstOrNull { code ->
                handle
                    .createUpdate(
                        """
                        insert into crews (id, name, invite_code, created_at) values (:id, :name, :code, :now)
                        on conflict (invite_code) do nothing
                        """,
                    ).bind("id", id)
                    .bind("name", name)
                    .bind("code", code)
                    .bind("now", now.toJavaInstant())
                    .execute() == 1
            }
        checkNotNull(code) { "No free invite code in $CODE_DRAWS draws" }
        // The owner is not announced in the feed: nobody was there to read it.
        addMember(handle, id, userId, MemberRoleDto.OWNER, now)
        return CrewDto(id, name, code, memberCount = 1, createdAt = now)
    }

    /** The crew [id], or null if there is none. */
    fun find(
        handle: Handle,
        id: String,
    ): CrewDto? =
        handle
            .createQuery("$CREW where c.id = :id")
            .bind("id", id)
            .map { row, _ -> crew(row) }
            .findOne()
            .orElse(null)

    /**
     * The crew [id] for a call made as [userId]: `404 NOT_FOUND` if there is none, `403 NOT_A_MEMBER` if they are not
     * in it. [held] holds the crew until the transaction ends, for a call that is about to change something in it.
     */
    fun ofMember(
        handle: Handle,
        id: String,
        userId: String,
        held: Boolean = false,
    ): CrewDto {
        if (held) hold(handle, HOLD_BY_ID, id)
        val crew = find(handle, id) ?: throw notFound("Crew $id")
        if (!isMember(handle, id, userId)) throw notAMember()
        return crew
    }

    /** The crews [userId] is in, oldest first. */
    fun of(
        handle: Handle,
        userId: String,
    ): List<CrewDto> =
        handle
            .createQuery(
                """
                $CREW join crew_members mine on mine.crew_id = c.id
                where mine.user_id = :user
                order by c.created_at, c.position
                """,
            ).bind("user", userId)
            .map { row, _ -> crew(row) }
            .list()

    /** The members of the crew [id], in the order they joined, under the names they have now. */
    fun members(
        handle: Handle,
        id: String,
    ): List<CrewMemberDto> =
        handle
            .createQuery(
                """
                select m.user_id, p.display_name, m.role, m.joined_at
                from crew_members m join people p on p.user_id = m.user_id
                where m.crew_id = :crew
                order by m.position
                """,
            ).bind("crew", id)
            .map { row, _ ->
                CrewMemberDto(
                    userId = row.getString("user_id"),
                    displayName = row.getString("display_name"),
                    role = MemberRoleDto.valueOf(row.getString("role")),
                    joinedAt = row.getTimestamp("joined_at").toInstant().toKotlinInstant(),
                )
            }.list()

    /**
     * Lets [userId] into the crew with the invite code [code] and says so in its feed; someone who is in it already
     * stays as they are. The crew as it is afterwards, or null if no crew has this code.
     */
    fun join(
        handle: Handle,
        code: String,
        userId: String,
        now: Instant,
    ): CrewDto? {
        val id = hold(handle, HOLD_BY_INVITE_CODE, code) ?: return null
        if (!isMember(handle, id, userId)) {
            addMember(handle, id, userId, MemberRoleDto.MEMBER, now)
            val person = People.of(handle, userId)
            val member = CrewMemberDto(userId, person.displayName, MemberRoleDto.MEMBER, now)
            CrewFeed.add(handle, id) { eventId -> MemberJoinedEventDto(eventId, id, now, person, member) }
        }
        return find(handle, id)
    }

    /**
     * Holds the crew [by] finds for [value] until the transaction ends, and gives its id; null if there is none.
     * Changes in one crew wait for each other here, which is what puts its feed in order. The lock leaves the crew's
     * key alone, so reading the crew and adding rows that point at it do not wait.
     */
    private fun hold(
        handle: Handle,
        by: String,
        value: String,
    ): String? =
        handle
            .createQuery(by)
            .bind("value", value)
            .map { row, _ -> row.getString("id") }
            .findOne()
            .orElse(null)

    private fun isMember(
        handle: Handle,
        id: String,
        userId: String,
    ): Boolean =
        handle
            .createQuery("select exists (select 1 from crew_members where crew_id = :crew and user_id = :user)")
            .bind("crew", id)
            .bind("user", userId)
            .map { row, _ -> row.getBoolean(1) }
            .one()

    private fun addMember(
        handle: Handle,
        id: String,
        userId: String,
        role: MemberRoleDto,
        now: Instant,
    ) {
        handle
            .createUpdate("insert into crew_members (crew_id, user_id, role, joined_at) values (:crew, :user, :role, :now)")
            .bind("crew", id)
            .bind("user", userId)
            .bind("role", role.name)
            .bind("now", now.toJavaInstant())
            .execute()
    }

    private fun crew(row: ResultSet) =
        CrewDto(
            id = row.getString("id"),
            name = row.getString("name"),
            inviteCode = row.getString("invite_code"),
            memberCount = row.getInt("member_count"),
            createdAt = row.getTimestamp("created_at").toInstant().toKotlinInstant(),
        )
}
