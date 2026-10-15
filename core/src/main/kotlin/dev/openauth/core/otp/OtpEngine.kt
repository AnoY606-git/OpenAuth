package dev.openauth.core.otp

import dev.openauth.core.model.HashAlgorithm
import dev.openauth.core.model.OtpEntry
import dev.openauth.core.model.OtpSecret
import dev.openauth.core.model.OtpType
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Чистое вычисление кода; резервированием HOTP-счётчика владеет хранилище. */
object OtpEngine {
    fun totp(entry: OtpEntry, epochSeconds: Long): String {
        require(entry.type == OtpType.TOTP) { "Ожидается запись TOTP" }
        require(epochSeconds >= 0) { "Время OTP не может быть отрицательным" }
        return generate(entry.secret, epochSeconds / entry.periodSeconds, entry.digits, entry.algorithm)
    }

    fun hotp(secret: OtpSecret, counter: Long, digits: Int): String {
        require(counter >= 0) { "Счётчик OTP не может быть отрицательным" }
        require(digits == 6 || digits == 8) { "OTP должен содержать 6 или 8 цифр" }
        return generate(secret, counter, digits, HashAlgorithm.SHA1)
    }

    /** На границе периода возвращает полный новый период, а не ноль. */
    fun remainingSeconds(periodSeconds: Int, epochSeconds: Long): Int {
        require(periodSeconds in 15..120) { "Период OTP должен быть от 15 до 120 секунд" }
        require(epochSeconds >= 0) { "Время OTP не может быть отрицательным" }
        return periodSeconds - (epochSeconds % periodSeconds).toInt()
    }

    private fun generate(secret: OtpSecret, counter: Long, digits: Int, algorithm: HashAlgorithm): String {
        val hmac = when (algorithm) {
            HashAlgorithm.SHA1 -> "HmacSHA1"
            HashAlgorithm.SHA256 -> "HmacSHA256"
            HashAlgorithm.SHA512 -> "HmacSHA512"
        }
        val key = secret.copyBytes()
        val message = ByteArray(8) { (counter ushr ((7 - it) * 8)).toByte() }
        val digest = try {
            Mac.getInstance(hmac).run {
                init(SecretKeySpec(key, hmac))
                doFinal(message)
            }
        } finally {
            key.fill(0)
        }
        try {
            val offset = digest.last().toInt() and 15
            val value = ((digest[offset].toInt() and 127) shl 24) or
                ((digest[offset + 1].toInt() and 255) shl 16) or
                ((digest[offset + 2].toInt() and 255) shl 8) or
                (digest[offset + 3].toInt() and 255)
            val modulus = if (digits == 6) 1_000_000 else 100_000_000
            return (value % modulus).toString().padStart(digits, '0')
        } finally {
            digest.fill(0)
        }
    }
}
