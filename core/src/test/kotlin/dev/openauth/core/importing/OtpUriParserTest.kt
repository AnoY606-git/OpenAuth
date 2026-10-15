package dev.openauth.core.importing

import dev.openauth.core.model.*
import dev.openauth.core.otp.Base32
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OtpUriParserTest {
    private val id = UUID(0, 7)
    private val base = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP"

    @Test fun parsesStandardExampleWithExplicitIdentityAndDefaults() {
        val entry = OtpUriParser.parse(base + "&issuer=Example", id)
        assertEquals(id, entry.id)
        assertEquals("Example", entry.issuer)
        assertEquals("alice", entry.accountName)
        assertEquals(Base32.decode("JBSWY3DPEHPK3PXP"), entry.secret)
        assertEquals(OtpType.TOTP, entry.type)
        assertEquals(HashAlgorithm.SHA1, entry.algorithm)
        assertEquals(6, entry.digits)
        assertEquals(30, entry.periodSeconds)
        assertEquals(0, entry.nextCounter)
        assertNull(entry.groupId)
        assertFalse(entry.favorite)
    }
    @Test fun percentDecodesUtf8LabelsAndRetainsLiteralPlus() {
        val entry = OtpUriParser.parse("otpauth://totp/%D0%91%D0%B0%D0%BD%D0%BA%3Aalice%2Btag%40example.invalid?secret=JBSWY3DPEHPK3PXP&issuer=%D0%91%D0%B0%D0%BD%D0%BA", id)
        assertEquals("Банк", entry.issuer)
        assertEquals("alice+tag@example.invalid", entry.accountName)
        assertEquals("alice+tag", OtpUriParser.parse("otpauth://totp/alice+tag?secret=JBSWY3DPEHPK3PXP", id).accountName)
    }
    @Test fun acceptsExplicitTotpOptionsAnd64BitHotpCounter() {
        val totp = OtpUriParser.parse(base + "&algorithm=SHA512&digits=8&period=120", id)
        assertEquals(HashAlgorithm.SHA512, totp.algorithm)
        assertEquals(8, totp.digits)
        assertEquals(120, totp.periodSeconds)
        val hotp = OtpUriParser.parse(base.replace("totp", "hotp") + "&counter=9223372036854775807", id)
        assertEquals(OtpType.HOTP, hotp.type)
        assertEquals(Long.MAX_VALUE, hotp.nextCounter)
    }
    @Test fun rejectsMissingDuplicateConflictingAndUnsupportedParameters() {
        val invalid = listOf(
            "otpauth://totp/alice", base + "&issuer=Other", base + "&secret=AAAAAAAAAAAAAAAA",
            base + "&%73ecret=AAAAAAAAAAAAAAAA", base + "&algorithm=MD5", base + "&digits=7", base + "&period=14",
            base.replace("totp", "hotp"), base.replace("totp", "hotp") + "&counter=-1",
            base.replace("totp", "hotp") + "&counter=9223372036854775808", base.replace("totp", "hotp") + "&counter=abc",
            base.replace("totp", "hotp") + "&counter=0&algorithm=SHA256", base + "&counter=3", base + "#fragment",
            base.replace("Example:alice", ""), base.replace("Example:alice", "%FF"), base + "&issuer=%xx",
            base.replace("otpauth://", "https://"), base.replace("totp/", "totp:80/"), base + "&x=" + "я".repeat(4096),
        )
        for (uri in invalid) {
            val exception = assertThrows(ImportException::class.java) { OtpUriParser.parse(uri, id) }
            assertFalse(exception.toString().contains("JBSWY3DPEHPK3PXP"))
            assertNull(exception.cause)
        }
    }
    @Test fun rejectsColonInsideAccountOrIssuerComponents() {
        val invalid = listOf(
            base.replace("Example:alice", "Example:alice:extra"),
            base.replace("Example:alice", "Example%3Aalice%3Aextra"),
            base.replace("Example:alice", "alice") + "&issuer=Example%3ADivision",
        )
        for (uri in invalid) {
            assertThrows(ImportException::class.java) { OtpUriParser.parse(uri, id) }
        }
    }
    @Test fun returnsMachineReadableFailureAndDoesNotExposeMalformedUri() {
        val exception = assertThrows(ImportException::class.java) { OtpUriParser.parse("otpauth://totp/alice?secret=SECRET%xx", id) }
        assertEquals(ImportFailure.INVALID_URI, exception.reason)
        assertFalse(exception.toString().contains("SECRET"))
    }
}
