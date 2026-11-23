package io.github.esseckers.pursuitofwind.server.crew

import io.github.esseckers.pursuitofwind.contract.PersonDto
import org.jdbi.v3.core.Handle

/** Users as other crew members see them (the contract's `Person`). */
internal object People {
    /**
     * [userId] under the name they have now: their profile's or, until they have saved one, `Rider` and the end of
     * their id (the view `people`).
     */
    fun of(
        handle: Handle,
        userId: String,
    ): PersonDto =
        handle
            .createQuery("select display_name from people where user_id = :user")
            .bind("user", userId)
            .map { row, _ -> PersonDto(userId, row.getString("display_name")) }
            .one()
}
