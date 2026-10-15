package dev.openauth.core.backup

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/** Владеет UTF-8 копией; вызывающий код самостоятельно очищает исходный CharArray. */
class BackupCredential private constructor(internal val kind: Int, private val bytes: ByteArray) : AutoCloseable {
    private var closed = false

    @Synchronized internal fun copyBytes(): ByteArray {
        if (closed) throw BackupException(BackupFailure.CLOSED)
        return bytes.copyOf()
    }

    @Synchronized override fun close() {
        bytes.fill(0)
        closed = true
    }

    override fun toString(): String = "BackupCredential([REDACTED])"

    companion object {
        fun password(chars: CharArray): BackupCredential {
            val normalized = Normalizer.normalize(CharBuffer.wrap(chars), Normalizer.Form.NFC)
            if (normalized.codePointCount(0, normalized.length) < 12) invalid()
            val encoded = try {
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(normalized))
            } catch (_: java.nio.charset.CharacterCodingException) { invalid() }
            try {
                if (encoded.remaining() > 1024) invalid()
                return BackupCredential(1, ByteArray(encoded.remaining()).also { encoded.get(it) })
            } finally {
                if (encoded.hasArray()) encoded.array().fill(0)
            }
        }

        fun pin(chars: CharArray): BackupCredential {
            if (chars.size !in 8..128 || chars.any { it !in '0'..'9' }) invalid()
            return BackupCredential(2, ByteArray(chars.size) { chars[it].code.toByte() })
        }

        private fun invalid(): Nothing = throw BackupException(BackupFailure.INVALID_CREDENTIAL)
    }
}
