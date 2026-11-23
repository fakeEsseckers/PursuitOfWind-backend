package io.github.esseckers.pursuitofwind.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/** Secrets the server hands out, and what it keeps of them. */
internal object Credentials {
    private const val TOKEN_BYTES = 32
    private const val ID_BYTES = 10
    private val random = SecureRandom()
    private val urlSafe = Base64.getUrlEncoder().withoutPadding()

    /** A new token nobody can guess: [prefix] says what it is (`at`, `rt`), the rest is 256 random bits. */
    fun newToken(prefix: String): String = prefix + "_" + urlSafe.encodeToString(randomBytes(TOKEN_BYTES))

    /** A new id for a row, `usr_3f9a…`: URL-safe, as the contract asks of ids, and not a count of anything. */
    fun newId(prefix: String): String = prefix + "_" + HexFormat.of().formatHex(randomBytes(ID_BYTES))

    /**
     * What the database keeps of a credential: enough to recognise it, useless to sign in with. No salt: every
     * credential here is itself long and random, and has to be found by this value.
     */
    fun hash(credential: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(credential.toByteArray())

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)
}
