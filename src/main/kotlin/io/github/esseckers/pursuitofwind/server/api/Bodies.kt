package io.github.esseckers.pursuitofwind.server.api

import io.github.esseckers.pursuitofwind.contract.ApiLimits
import io.github.esseckers.pursuitofwind.contract.ContractJson
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy

// What comes in. A body is read by the server itself, up to a limit, and decoded with the contract's JSON; nothing
// here depends on the call's Content-Type, as nothing in the app's in-app backend does.

/** No request of the contract comes near this; a body beyond it is not read to its end. */
private const val MAX_BODY_KB = 64
private const val MAX_BODY_BYTES = MAX_BODY_KB * 1024

/** The body of the call as text. One that is larger than the server reads is `422 VALIDATION_FAILED`. */
internal suspend fun ApplicationCall.bodyText(): String {
    val tooLarge = { invalid("The body is larger than $MAX_BODY_KB KB.") }
    if ((request.contentLength() ?: 0) > MAX_BODY_BYTES) throw tooLarge()
    val bytes = receiveChannel().readBuffer(MAX_BODY_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) throw tooLarge()
    return bytes.decodeToString()
}

/** The body of the call as the contract's [type]; a body that is not that is `422 VALIDATION_FAILED`. */
internal suspend fun <T> ApplicationCall.body(type: DeserializationStrategy<T>): T = decoded(type, bodyText())

/** [text] as the contract's [type]; text that is not that is `422 VALIDATION_FAILED`. */
internal fun <T> decoded(
    type: DeserializationStrategy<T>,
    text: String,
): T =
    try {
        ContractJson.decodeFromString(type, text)
    } catch (e: IllegalArgumentException) {
        throw invalid("The body is not valid JSON for this operation.", e)
    }

/** The contract's pattern for an id in a path, with an end to its length: letters, digits, `_` and `-`. */
private val ID_FORM = Regex("[A-Za-z0-9_-]{1,128}")

/** Whether [text] could be an id of this API at all. What is not cannot name anything, and is kept away from SQL. */
internal fun isId(text: String): Boolean = ID_FORM.matches(text)

/** The whole number the query parameter [name] gives, within [range]; [default] when the call does not give it. */
internal fun intQuery(
    parameters: Parameters,
    name: String,
    range: IntRange,
    default: Int,
): Int {
    val raw = parameters[name] ?: return default
    return raw.toIntOrNull()?.takeIf { it in range } ?: throw invalid("$name must be an integer from ${range.first} to ${range.last}.")
}

/**
 * The contract's rule for a line of text a person types (`minLength: 1`, `maxLength` in code points, `pattern: '\S'`):
 * [value] has something in it besides spaces and at most [maxLength] characters. It also has no U+0000, which nobody
 * types and PostgreSQL cannot keep in a text.
 */
internal fun requireText(
    value: String,
    name: String,
    maxLength: Int,
) = check422(value.any { !it.isWhitespace() } && '\u0000' !in value && ApiLimits.lengthOf(value) <= maxLength) {
    "$name must have 1 to $maxLength characters and not only spaces."
}
