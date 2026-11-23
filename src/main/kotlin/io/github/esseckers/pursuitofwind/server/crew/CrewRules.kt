package io.github.esseckers.pursuitofwind.server.crew

import io.github.esseckers.pursuitofwind.contract.ApiLimits
import io.github.esseckers.pursuitofwind.server.api.requireText
import java.security.SecureRandom

/** The limits of the contract's `CreateCrewRequest`, and the invite codes this server gives out. */
internal object CrewRules {
    /** No 0/O or 1/I, so a code survives being read out loud; the app's in-app backend draws from the same letters. */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val GROUP = 4
    private val CODE = Regex("[A-Z0-9-]{1,20}")
    private val random = SecureRandom()

    fun checkName(name: String) = requireText(name, "name", ApiLimits.Crew.NAME_MAX_LENGTH)

    /**
     * A new invite code, `WIND-4KQ7-X2MP`: forty random bits, short enough to type and too many to try them all. It
     * fits what the app takes for a code in an invitation link: letters, digits and `-`, twenty at most.
     */
    fun newInviteCode(): String = "WIND-" + group() + "-" + group()

    /**
     * The invite code a rider meant by [typed], in the form codes are kept in: the contract ignores case and the
     * spaces around a code. Null when no crew can have it as its code; such text never reaches a query.
     */
    fun inviteCode(typed: String): String? = typed.trim().uppercase().takeIf(CODE::matches)

    private fun group(): String = String(CharArray(GROUP) { ALPHABET[random.nextInt(ALPHABET.length)] })
}
