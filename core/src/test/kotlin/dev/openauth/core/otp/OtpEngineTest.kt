package dev.openauth.core.otp

import dev.openauth.core.model.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OtpEngineTest {
    private val sha1 = OtpSecret.fromBytes("12345678901234567890".toByteArray())

    @Test fun matchesAllRfc4226AppendixDVectors() {
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
        expected.forEachIndexed { counter, code -> assertEquals(code, OtpEngine.hotp(sha1, counter.toLong(), 6)) }
    }

    @Test fun matchesCompleteRfc6238AppendixB() {
        val times = listOf(59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L)
        val vectors = listOf(
            Triple(HashAlgorithm.SHA1, "12345678901234567890", listOf("94287082", "07081804", "14050471", "89005924", "69279037", "65353130")),
            Triple(HashAlgorithm.SHA256, "12345678901234567890123456789012", listOf("46119246", "68084774", "67062674", "91819424", "90698825", "77737706")),
            Triple(HashAlgorithm.SHA512, "1234567890123456789012345678901234567890123456789012345678901234", listOf("90693936", "25091201", "99943326", "93441116", "38618901", "47863826")),
        )
        for ((algorithm, key, codes) in vectors) {
            val entry = entry(secret = OtpSecret.fromBytes(key.toByteArray()), algorithm = algorithm, digits = 8)
            times.forEachIndexed { i, time -> assertEquals("$algorithm at $time", codes[i], OtpEngine.totp(entry, time)) }
        }
    }

    @Test fun changesAtExactPeriodBoundaryAndPreservesLeadingZeros() {
        assertEquals("755224", OtpEngine.totp(entry(), 29))
        assertEquals("287082", OtpEngine.totp(entry(), 30))
        assertEquals("07081804", OtpEngine.totp(entry(digits = 8), 1111111109))
        assertEquals(30, OtpEngine.remainingSeconds(30, 0))
        assertEquals(1, OtpEngine.remainingSeconds(30, 29))
        assertEquals(30, OtpEngine.remainingSeconds(30, 30))
        assertEquals(113, OtpEngine.remainingSeconds(120, Long.MAX_VALUE))
    }

    @Test fun supports64BitCounterWithoutIncrementOrTruncation() {
        assertEquals("181742", OtpEngine.hotp(sha1, Long.MAX_VALUE, 6))
        assertEquals("451934", OtpEngine.totp(entry(), Long.MAX_VALUE))
    }

    @Test fun rejectsNegativeTimeCounterAndUnsupportedParameters() {
        assertThrows(IllegalArgumentException::class.java) { OtpEngine.hotp(sha1, -1, 6) }
        assertThrows(IllegalArgumentException::class.java) { OtpEngine.hotp(sha1, 0, 7) }
        assertThrows(IllegalArgumentException::class.java) { OtpEngine.totp(entry(), -1) }
        assertThrows(IllegalArgumentException::class.java) { OtpEngine.totp(entry(type = OtpType.HOTP), 0) }
        for (period in listOf(0, 14, 121)) assertThrows(IllegalArgumentException::class.java) { OtpEngine.remainingSeconds(period, 1) }
        assertThrows(IllegalArgumentException::class.java) { OtpEngine.remainingSeconds(30, -1) }
    }

    private fun entry(secret: OtpSecret = sha1, algorithm: HashAlgorithm = HashAlgorithm.SHA1, digits: Int = 6, type: OtpType = OtpType.TOTP) =
        OtpEntry(UUID(0, 1), "Example", "test@example.invalid", secret, type, algorithm, digits, 30, 0, null, false, 0)
}
