package dev.openauth.core.importing

import dev.openauth.core.model.HashAlgorithm
import dev.openauth.core.model.OtpEntry
import dev.openauth.core.model.OtpType
import dev.openauth.core.otp.Base32
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

enum class ImportFailure {
    INVALID_URI, URI_TOO_LONG, INVALID_SECRET, MISSING_SECRET, DUPLICATE_PARAMETER,
    ISSUER_CONFLICT, UNSUPPORTED_PARAMETER, INVALID_MIGRATION, UNSUPPORTED_VERSION,
    INCOMPLETE_BATCH, BATCH_CONFLICT, DUPLICATE_PART, TOO_MANY_ENTRIES, DUPLICATE_ID,
}

/** Только код причины: URI и исключение системного парсера могут содержать секрет. */
class ImportException(val reason: ImportFailure) : IllegalArgumentException("Импорт OTP: ${reason.name}")

object OtpUriParser {
    fun parse(uri: String, id: UUID): OtpEntry {
        val parsed = parseBoundedUri(uri)
        if (!parsed.scheme.equals("otpauth", ignoreCase = true)) fail(ImportFailure.INVALID_URI)
        val type = when (parsed.rawAuthority?.lowercase()) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> fail(ImportFailure.INVALID_URI)
        }
        val path = parsed.rawPath ?: fail(ImportFailure.INVALID_URI)
        if (!path.startsWith('/') || path.length == 1) fail(ImportFailure.INVALID_URI)
        val label = decodeUriComponent(path.substring(1))
        val parameters = parseQuery(parsed.rawQuery)
        val separator = label.indexOf(':')
        val labelIssuer = if (separator >= 0) label.substring(0, separator) else null
        val account = if (separator >= 0) label.substring(separator + 1).trimStart(' ') else label
        val issuer = parameters["issuer"] ?: labelIssuer.orEmpty()
        if (account.isEmpty() || labelIssuer?.isEmpty() == true || ':' in account || ':' in issuer ||
            label.any { it.isISOControl() } || issuer.any { it.isISOControl() }
        ) fail(ImportFailure.INVALID_URI)
        if (labelIssuer != null && parameters.containsKey("issuer") && labelIssuer != issuer) fail(ImportFailure.ISSUER_CONFLICT)
        val secret = Base32.decode(parameters["secret"] ?: fail(ImportFailure.MISSING_SECRET))
        val algorithm = when (parameters["algorithm"]?.uppercase()) {
            null, "SHA1" -> HashAlgorithm.SHA1
            "SHA256" -> HashAlgorithm.SHA256
            "SHA512" -> HashAlgorithm.SHA512
            else -> fail(ImportFailure.UNSUPPORTED_PARAMETER)
        }
        val digits = parameters["digits"]?.let { decimal(it).takeIf { n -> n == 6L || n == 8L }?.toInt() ?: fail(ImportFailure.UNSUPPORTED_PARAMETER) } ?: 6
        val period = parameters["period"]?.let { decimal(it).takeIf { n -> n in 15L..120L }?.toInt() ?: fail(ImportFailure.UNSUPPORTED_PARAMETER) } ?: 30
        val counter = if (type == OtpType.HOTP) {
            if (algorithm != HashAlgorithm.SHA1 || parameters.containsKey("period")) fail(ImportFailure.UNSUPPORTED_PARAMETER)
            decimal(parameters["counter"] ?: fail(ImportFailure.UNSUPPORTED_PARAMETER))
        } else {
            if (parameters.containsKey("counter")) fail(ImportFailure.UNSUPPORTED_PARAMETER)
            0L
        }
        return OtpEntry(id, issuer, account, secret, type, algorithm, digits, period, counter, null, false, 0)
    }

    private fun decimal(value: String): Long {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) fail(ImportFailure.UNSUPPORTED_PARAMETER)
        return value.toLongOrNull() ?: fail(ImportFailure.UNSUPPORTED_PARAMETER)
    }
}

internal fun fail(reason: ImportFailure): Nothing = throw ImportException(reason)

internal fun parseBoundedUri(text: String): URI {
    if (text.length > 8192 || text.toByteArray(UTF_8).size > 8192) fail(ImportFailure.URI_TOO_LONG)
    val parsed = try { URI(text) } catch (_: URISyntaxException) { fail(ImportFailure.INVALID_URI) }
    if (parsed.isOpaque || parsed.rawFragment != null || parsed.rawUserInfo != null || parsed.port != -1) fail(ImportFailure.INVALID_URI)
    return parsed
}

/** RFC 3986: знак + остаётся знаком +, а не пробелом HTML-формы. */
internal fun decodeUriComponent(text: String): String {
    try {
        val encoded = UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        val bytes = ByteArray(encoded.remaining())
        var size = 0
        while (encoded.hasRemaining()) {
            val byte = encoded.get()
            if (byte.toInt() == 37) {
                if (encoded.remaining() < 2) fail(ImportFailure.INVALID_URI)
                val high = Character.digit(encoded.get().toInt().toChar(), 16)
                val low = Character.digit(encoded.get().toInt().toChar(), 16)
                if (high < 0 || low < 0) fail(ImportFailure.INVALID_URI)
                bytes[size++] = ((high shl 4) or low).toByte()
            } else bytes[size++] = byte
        }
        return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString()
    } catch (_: CharacterCodingException) {
        fail(ImportFailure.INVALID_URI)
    }
}

internal fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrEmpty()) return emptyMap()
    val parameters = LinkedHashMap<String, String>()
    for (part in rawQuery.split('&')) {
        val separator = part.indexOf('=')
        if (separator <= 0) fail(ImportFailure.INVALID_URI)
        val key = decodeUriComponent(part.substring(0, separator))
        val value = decodeUriComponent(part.substring(separator + 1))
        if (parameters.put(key, value) != null) fail(ImportFailure.DUPLICATE_PARAMETER)
    }
    return parameters
}
