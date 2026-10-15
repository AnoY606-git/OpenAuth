package dev.openauth.core.otp

import dev.openauth.core.importing.ImportException
import dev.openauth.core.importing.ImportFailure
import dev.openauth.core.model.OtpSecret

/** RFC 4648: принимает ASCII обоих регистров, возвращает каноническую запись без padding. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(text: String): OtpSecret {
        if (text.length !in 16..208) invalid()
        val paddingStart = text.indexOf('=').let { if (it < 0) text.length else it }
        val remainder = paddingStart % 8
        val expectedPadding = when (remainder) { 0 -> 0; 2 -> 6; 4 -> 4; 5 -> 3; 7 -> 1; else -> invalid() }
        if (paddingStart < text.length &&
            (text.length % 8 != 0 || text.length - paddingStart != expectedPadding || text.substring(paddingStart).any { it != '=' })) invalid()
        val size = paddingStart * 5 / 8
        if (size !in 10..128) invalid()
        val bytes = ByteArray(size)
        try {
            var buffer = 0
            var bits = 0
            var position = 0
            for (index in 0 until paddingStart) {
                val c = text[index]
                val upper = if (c in 'a'..'z') c - 32 else c
                val value = ALPHABET.indexOf(upper)
                if (value < 0) invalid()
                buffer = (buffer shl 5) or value
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    bytes[position++] = (buffer ushr bits).toByte()
                    buffer = buffer and ((1 shl bits) - 1)
                }
            }
            if (buffer != 0) invalid()
            return OtpSecret.fromBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun encode(secret: OtpSecret): String {
        val bytes = secret.copyBytes()
        try {
            val result = StringBuilder((bytes.size * 8 + 4) / 5)
            var buffer = 0
            var bits = 0
            for (byte in bytes) {
                buffer = (buffer shl 8) or (byte.toInt() and 255)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    result.append(ALPHABET[(buffer ushr bits) and 31])
                }
                buffer = buffer and ((1 shl bits) - 1)
            }
            if (bits > 0) result.append(ALPHABET[(buffer shl (5 - bits)) and 31])
            return result.toString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun invalid(): Nothing = throw ImportException(ImportFailure.INVALID_SECRET)
}
