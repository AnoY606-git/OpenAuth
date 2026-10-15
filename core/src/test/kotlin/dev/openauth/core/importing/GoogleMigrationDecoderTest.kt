package dev.openauth.core.importing

import dev.openauth.core.model.*
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class GoogleMigrationDecoderTest {
    @Test fun importsCompleteBatchInIndexOrderWithSuppliedIds() {
        val parts = listOf(part(1), part(0))
        var next = 10L
        val entries = GoogleMigrationDecoder.assemble(parts) { UUID(0, next++) }
        assertEquals(listOf("alice@example.invalid", "bob@example.invalid"), entries.map { it.accountName })
        assertEquals(listOf(UUID(0, 10), UUID(0, 11)), entries.map { it.id })
        assertEquals("Example", entries[0].issuer)
        assertArrayEquals("01234567890123456789".toByteArray(), entries[0].secret.copyBytes())
        assertEquals(OtpType.TOTP, entries[0].type)
        assertEquals(OtpType.HOTP, entries[1].type)
        assertEquals(Long.MAX_VALUE, entries[1].nextCounter)
        assertEquals(HashAlgorithm.SHA1, entries[0].algorithm)
        assertEquals(6, entries[0].digits)
        assertEquals(30, entries[0].periodSeconds)
    }
    @Test fun incompleteDuplicateAndMixedBatchesYieldNoResultsOrIds() {
        val bad = listOf(emptyList(), listOf(part(0)), listOf(part(0), part(0)), listOf(part(0), parse(payload(index = 1, batchId = 43))), listOf(part(0), parse(payload(index = 1, size = 3))))
        for (parts in bad) {
            var calls = 0
            assertThrows(ImportException::class.java) { GoogleMigrationDecoder.assemble(parts) { calls++; UUID(0, 1) } }
            assertEquals(0, calls)
        }
    }
    @Test fun skipsUnknownVarintFixedAndLengthDelimitedFieldsAtBothLevels() {
        val unknown = bytes(80, 123, 89, 1, 2, 3, 4, 5, 6, 7, 8, 98, 3, 10, 20, 30, 109, 1, 2, 3, 4)
        val entry = GoogleMigrationDecoder.assemble(listOf(parse(payload(size = 1, account = account() + unknown) + unknown))) { UUID(0, 1) }.single()
        assertEquals("synthetic@example.invalid", entry.accountName)
    }
    @Test fun mapsExplicitSha256Sha512AndEightDigitOptions() {
        for ((number, algorithm) in listOf(2 to HashAlgorithm.SHA256, 3 to HashAlgorithm.SHA512)) {
            val entry = GoogleMigrationDecoder.assemble(listOf(parse(payload(size = 1, account = account(algorithm = number, digits = 2))))) { UUID(0, 1) }.single()
            assertEquals(algorithm, entry.algorithm)
            assertEquals(8, entry.digits)
        }
    }
    @Test fun rejectsMalformedProtobufEnumsMetadataAndOversizedInput() {
        val invalid = listOf(
            bytes(10, 127, 1), bytes(0), bytes(8, 1), bytes(128, 128, 128, 128, 128, 128, 128, 128, 128, 2),
            payload(version = 2), payload(size = 0), payload(size = 10001), payload(index = 2),
            payload(account = account(algorithm = 4)), payload(account = account(algorithm = 0)),
            payload(account = account(digits = 0)), payload(account = account(type = 0)), payload(account = account(type = 1, algorithm = 2)),
            payload(account = account(secret = ByteArray(9))), payload(account = account(secret = ByteArray(129))),
            payload(account = account(counter = -1)), payload() + bytes(120, 128), payload() + bytes(122, 127),
            payload() + bytes(123), payload(account = account() + bytes(18, 1, 255)),
        )
        for (bytes in invalid) {
            val exception = assertThrows(ImportException::class.java) { parse(bytes) }
            assertNull(exception.cause)
        }
        assertThrows(ImportException::class.java) { GoogleMigrationDecoder.parse("otpauth-migration://offline?data=" + "A".repeat(8192)) }
    }
    @Test fun rejectsMalformedMigrationUriWithoutSecretInException() {
        val fixture = resource(0)
        for (uri in listOf(fixture + "&data=SECRET", fixture + "#SECRET", "otpauth-migration://offline?data=SECRET%xx", "otpauth-migration://online?data=SECRET", "otpauth-migration://offline/path?data=SECRET")) {
            val exception = assertThrows(ImportException::class.java) { GoogleMigrationDecoder.parse(uri) }
            assertFalse(exception.toString().contains("SECRET"))
            assertNull(exception.cause)
        }
    }
    @Test fun acceptsSigned32BitBatchIdAndZeroIndexOmittedByProto3() {
        val bytes = length(1, account()) + number(2, 1) + number(3, 1) + number(5, -1)
        val entries = GoogleMigrationDecoder.assemble(listOf(parse(bytes))) { UUID(0, 1) }
        assertEquals(1, entries.size)
    }
    @Test fun refusesDuplicateGeneratedIds() {
        assertThrows(ImportException::class.java) { GoogleMigrationDecoder.assemble(listOf(part(0), part(1))) { UUID(0, 1) } }
    }

    private fun resource(index: Int) = requireNotNull(javaClass.getResource("/migration/batch-$index.uri")).readText().trim()
    private fun part(index: Int) = GoogleMigrationDecoder.parse(resource(index))
    private fun parse(data: ByteArray) = GoogleMigrationDecoder.parse("otpauth-migration://offline?data=" + URLEncoder.encode(Base64.getEncoder().encodeToString(data), "UTF-8"))
    private fun payload(index: Int = 0, size: Int = 2, batchId: Int = 42, version: Int = 1, account: ByteArray = account()) =
        length(1, account) + number(2, version.toLong()) + number(3, size.toLong()) + number(4, index.toLong()) + number(5, batchId.toLong())
    private fun account(algorithm: Int = 1, digits: Int = 1, type: Int = 2, secret: ByteArray = "01234567890123456789".toByteArray(), counter: Long = 0) =
        length(1, secret) + length(2, "synthetic@example.invalid".toByteArray()) + length(3, "Example".toByteArray()) + number(4, algorithm.toLong()) + number(5, digits.toLong()) + number(6, type.toLong()) + number(7, counter)
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun number(field: Int, value: Long) = varint((field * 8).toLong()) + varint(value)
    private fun length(field: Int, data: ByteArray) = varint((field * 8 + 2).toLong()) + varint(data.size.toLong()) + data
    private fun varint(value: Long): ByteArray {
        var n = value
        val out = mutableListOf<Byte>()
        while (n and -128L != 0L) { out += ((n and 127) or 128).toByte(); n = n ushr 7 }
        out += n.toByte()
        return out.toByteArray()
    }
}
