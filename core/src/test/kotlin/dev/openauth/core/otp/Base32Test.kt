package dev.openauth.core.otp

import dev.openauth.core.importing.ImportException
import dev.openauth.core.model.OtpSecret
import org.junit.Assert.*
import org.junit.Test

class Base32Test {
    @Test fun decodesStandardExampleAndEncodesWithoutPadding() {
        assertArrayEquals(byteArrayOf(72, 101, 108, 108, 111, 33, -34, -83, -66, -17), Base32.decode("JBSWY3DPEHPK3PXP").copyBytes())
        assertEquals("JBSWY3DPEHPK3PXP", Base32.encode(Base32.decode("jbswy3dpehpk3pxp")))
    }
    @Test fun acceptsCanonicalPaddingAndBothSecretSizeBoundaries() {
        val ten = OtpSecret.fromBytes(ByteArray(10))
        assertEquals("AAAAAAAAAAAAAAAA", Base32.encode(ten))
        val eleven = OtpSecret.fromBytes(ByteArray(11))
        assertEquals(eleven, Base32.decode("AAAAAAAAAAAAAAAAAA======"))
        val large = OtpSecret.fromBytes(ByteArray(128) { it.toByte() })
        assertEquals(large, Base32.decode(Base32.encode(large)))
    }
    @Test fun rejectsInvalidAlphabetPaddingNonZeroTailAndSizes() {
        for (value in listOf("", "A".repeat(15), "A".repeat(207), "JBSWY3DPEHPK3PX0", "JBSWY3DP EHPK3PXP", "AAAAAAAAAAAAAAAAAA=====", "AAAAAAAAAAAAAAAAAB", "AAAAAAAA=AAAAAAAA", "A".repeat(10000))) {
            assertThrows(value.take(24), ImportException::class.java) { Base32.decode(value) }
        }
    }
}
