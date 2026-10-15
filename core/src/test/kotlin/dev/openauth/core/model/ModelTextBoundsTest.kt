package dev.openauth.core.model

import dev.openauth.core.backup.BackupException
import dev.openauth.core.backup.BackupFailure
import dev.openauth.core.backup.SnapshotJsonCodec
import dev.openauth.core.importing.GoogleMigrationDecoder
import dev.openauth.core.importing.ImportException
import dev.openauth.core.importing.ImportFailure
import dev.openauth.core.importing.OtpUriParser
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelTextBoundsTest {
    @Test fun acceptsIssuerAccountAndGroupAt256CodeUnits() {
        val text = "я".repeat(256)
        val entry = entry(issuer = text, accountName = text)
        assertEquals(text, entry.issuer)
        assertEquals(text, entry.accountName)
        assertEquals(text, EntryGroup(ID, text).name)
    }

    @Test fun rejectsIssuerAbove256CodeUnits() {
        assertThrows(IllegalArgumentException::class.java) { entry(issuer = "я".repeat(257)) }
    }

    @Test fun rejectsAccountAbove256CodeUnits() {
        assertThrows(IllegalArgumentException::class.java) { entry(accountName = "я".repeat(257)) }
    }

    @Test fun rejectsGroupAbove256CodeUnits() {
        assertThrows(IllegalArgumentException::class.java) { EntryGroup(ID, "я".repeat(257)) }
    }

    @Test fun permitsEmptyIssuerAndPreservesManualMetadata() {
        val entry = entry(issuer = "", accountName = "  user:division  ")
        assertEquals("", entry.issuer)
        assertEquals("  user:division  ", entry.accountName)
        assertEquals("  Example:Division  ", entry(issuer = "  Example:Division  ").issuer)
        assertEquals("  Личные  ", EntryGroup(ID, "  Личные  ").name)
    }

    @Test fun rejectsBlankAccountNames() {
        for (text in listOf("", " ", "\u2003")) {
            assertThrows(IllegalArgumentException::class.java) { entry(accountName = text) }
        }
    }

    @Test fun rejectsBlankGroupNames() {
        for (text in listOf("", " ", "\u2003")) {
            assertThrows(IllegalArgumentException::class.java) { EntryGroup(ID, text) }
        }
    }

    @Test fun rejectsIsoControlsInIssuerAccountAndGroup() {
        for (control in listOf('\u0000', '\u001f', '\u007f', '\u009f')) {
            val text = "private${control}metadata"
            assertThrows(IllegalArgumentException::class.java) { entry(issuer = text) }
            assertThrows(IllegalArgumentException::class.java) { entry(accountName = text) }
            assertThrows(IllegalArgumentException::class.java) { EntryGroup(ID, text) }
        }
    }

    @Test fun dataClassCopiesCannotBypassTextValidation() {
        val entry = entry()
        val group = EntryGroup(ID, "Личные")
        assertThrows(IllegalArgumentException::class.java) { entry.copy(issuer = "x".repeat(257)) }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(accountName = " ") }
        assertThrows(IllegalArgumentException::class.java) { group.copy(name = "\u0000") }
    }

    @Test fun snapshotDecodeRejectsMalformedMetadataWithSanitizedTypedFailure() {
        val invalid = listOf(
            json(issuer = "private".repeat(37)), json(account = "private".repeat(37)),
            json(group = "private".repeat(37)), json(account = ""), json(group = " "),
            json(issuer = "private\u0000issuer"), json(account = "private\u007faccount"),
            json(group = "private\u009fgroup"),
        )
        for (bytes in invalid) {
            val error = assertThrows(BackupException::class.java) { SnapshotJsonCodec.decode(bytes) }
            assertEquals(BackupFailure.INVALID_FORMAT, error.reason)
            assertFalse(error.toString().contains("private"))
            assertNull(error.cause)
        }
    }

    @Test fun snapshotDecodeAcceptsBoundaryMetadataAndEmptyIssuer() {
        val text = "я".repeat(256)
        val snapshot = SnapshotJsonCodec.decode(json(issuer = "", account = text, group = text))
        assertEquals("", snapshot.entries.single().issuer)
        assertEquals(text, snapshot.entries.single().accountName)
        assertEquals(text, snapshot.groups.single().name)
    }

    @Test fun otpUriMetadataFailureRemainsTypedAndSanitized() {
        val uris = listOf(
            "otpauth://totp/" + "private".repeat(37) + "?secret=JBSWY3DPEHPK3PXP",
            "otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&issuer=" + "private".repeat(37),
            "otpauth://totp/%20%20?secret=JBSWY3DPEHPK3PXP",
        )
        for (uri in uris) {
            val error = assertThrows(ImportException::class.java) { OtpUriParser.parse(uri, ID) }
            assertEquals(ImportFailure.INVALID_URI, error.reason)
            assertFalse(error.toString().contains("private"))
            assertFalse(error.toString().contains("JBSWY3DPEHPK3PXP"))
            assertNull(error.cause)
        }
    }

    @Test fun migrationMetadataFailureRemainsTypedAndSanitized() {
        for ((issuer, name) in listOf("private".repeat(37) to "alice", "Example" to "private".repeat(37), "Example" to " ")) {
            val error = assertThrows(ImportException::class.java) {
                val part = GoogleMigrationDecoder.parse(migrationUri(issuer, name))
                GoogleMigrationDecoder.assemble(listOf(part)) { ID }
            }
            assertEquals(ImportFailure.INVALID_MIGRATION, error.reason)
            assertFalse(error.toString().contains("private"))
            assertNull(error.cause)
        }
    }

    private fun entry(issuer: String = "Example", accountName: String = "alice") = OtpEntry(
        ID, issuer, accountName, OtpSecret.fromBytes(ByteArray(10)), OtpType.TOTP,
        HashAlgorithm.SHA1, 6, 30, 0, null, false, 0,
    )

    private fun json(issuer: String = "Example", account: String = "alice", group: String = "Личные") =
        """{"schemaVersion":1,"revision":0,"entries":[{"id":"00000000-0000-0000-0000-000000000001","issuer":${JsonPrimitive(issuer)},"accountName":${JsonPrimitive(account)},"secret":"MDEyMzQ1Njc4OQ==","type":"TOTP","algorithm":"SHA1","digits":6,"periodSeconds":30,"nextCounter":0,"groupId":null,"favorite":false,"sortIndex":0}],"groups":[{"id":"00000000-0000-0000-0000-000000000002","name":${JsonPrimitive(group)}}]}""".toByteArray()

    private fun migrationUri(issuer: String, name: String): String {
        val account = field(1, "0123456789".toByteArray()) + field(2, name.toByteArray()) +
            field(3, issuer.toByteArray()) + byteArrayOf(32, 1, 40, 1, 48, 2)
        val payload = field(1, account) + byteArrayOf(16, 1, 24, 1)
        return "otpauth-migration://offline?data=" + URLEncoder.encode(Base64.getEncoder().encodeToString(payload), "UTF-8")
    }

    private fun field(number: Int, bytes: ByteArray): ByteArray =
        byteArrayOf((number * 8 + 2).toByte()) + varint(bytes.size) + bytes

    private fun varint(value: Int): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        while (remaining >= 128) {
            bytes += ((remaining and 127) or 128).toByte()
            remaining = remaining ushr 7
        }
        bytes += remaining.toByte()
        return bytes.toByteArray()
    }

    companion object { private val ID = UUID(0, 1) }
}
