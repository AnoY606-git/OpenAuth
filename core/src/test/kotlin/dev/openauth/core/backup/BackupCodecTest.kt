package dev.openauth.core.backup

import dev.openauth.core.model.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val codec = BackupCodec(DeterministicRandom())
    private fun credential() = BackupCredential.password("  пароль Café!  ".toCharArray())
    private fun snapshot(): VaultSnapshot {
        val group = EntryGroup(UUID(1, 2), "Личные")
        return VaultSnapshot(revision = 42, groups = listOf(group), entries = listOf(
            OtpEntry(UUID(2, 3), "PrivateIssuer", "secret-account@example.test", OtpSecret.fromBytes(ByteArray(20) { it.toByte() }),
                OtpType.TOTP, HashAlgorithm.SHA256, 8, 60, 0, group.id, true, -3),
            OtpEntry(UUID(3, 4), "Bank", "me", OtpSecret.fromBytes(ByteArray(10) { 42 }),
                OtpType.HOTP, HashAlgorithm.SHA1, 6, 30, Long.MAX_VALUE, null, false, 7)))
    }
    private fun assertSnapshot(expected: VaultSnapshot, actual: VaultSnapshot) {
        assertEquals(expected.schemaVersion, actual.schemaVersion)
        assertEquals(expected.revision, actual.revision)
        assertEquals(expected.entries, actual.entries)
        assertEquals(expected.groups, actual.groups)
    }
    private fun sealed(): ByteArray = credential().use { password ->
        codec.createGeneration(password).use { codec.seal(snapshot(), it, UUID(8, 9), 123456789) }
    }

    @Test fun readsIndependentLibargon2AndOpenSslVector() {
        // Эталон рассчитан libargon2 1.3 + Python cryptography/OpenSSL, не кодеком проекта.
        val hex = "4f414241434b55500001010100000013000100000000000300000004000102030405060708090a0b0c0d0e0f" +
            "101112131415161718191a1b1c1d1e1f202122232425262728292a2b06a92c653daffe28fdce9b51fef9d703" +
            "36196df05fde32fcbcf17b2bc58ed306ceac0a4ed25e086c9695411ad7040212303132333435363738393a3b" +
            "3c3d3e3f000000000000007b404142434445464748494a4b00000039999bdd404e59ea629ba16545f20b7d79" +
            "43f77e7ec550794a042e218bb529daf7ba6d9dd8acd0ba2703ce5032bd2fb8a8a404da4211fdb954fc6121d2" +
            "de93a5b211fc943238f7a0bfea"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        BackupCredential.password("portable backup!".toCharArray()).use {
            assertSnapshot(VaultSnapshot(revision = 7, entries = emptyList(), groups = emptyList()), codec.open(bytes, it))
        }
    }

    @Test fun unicodeRoundTripHidesNamesAndNormalizesNfc() {
        val data = sealed()
        assertFalse(data.toString(Charsets.ISO_8859_1).contains("PrivateIssuer"))
        assertFalse(data.toString(Charsets.ISO_8859_1).contains("secret-account"))
        BackupCredential.password("  пароль Cafe\u0301!  ".toCharArray()).use {
            assertSnapshot(snapshot(), codec.open(data, it))
        }
    }

    @Test fun spacesAndIncorrectPasswordsAreSignificant() {
        val data = sealed()
        for (password in listOf("пароль Café!", "other password!")) {
            BackupCredential.password(password.toCharArray()).use {
                val error = assertThrows(BackupException::class.java) { codec.open(data, it) }
                assertEquals(BackupFailure.AUTHENTICATION_FAILED, error.reason)
                assertNull(error.cause)
                assertFalse(error.toString().contains(password))
            }
        }
    }

    @Test fun pinLeadingZerosSurviveAndInputIsOwned() {
        val input = "00001234".toCharArray()
        BackupCredential.pin(input).use { pin ->
            input.fill('9')
            codec.createGeneration(pin).use { generation ->
                val data = codec.seal(snapshot(), generation, UUID(1, 1), 0)
                BackupCredential.pin("00001234".toCharArray()).use { assertSnapshot(snapshot(), codec.open(data, it)) }
                BackupCredential.pin("12340000".toCharArray()).use {
                    assertThrows(BackupException::class.java) { codec.open(data, it) }
                }
            }
        }
    }

    @Test fun generationStateRoundTripOwnsCopiesAndClosePreventsUse() {
        credential().use { password ->
            val state = codec.createGeneration(password).use { it.exportLocalState() }
            val generation = BackupGeneration.importLocalState(state)
            state.fill(0)
            val secondCopy = generation.exportLocalState()
            secondCopy.fill(0)
            val data = codec.seal(snapshot(), generation, UUID(8, 9), 1)
            assertSnapshot(snapshot(), codec.open(data, password))
            generation.close()
            generation.close()
            assertEquals(BackupFailure.CLOSED, assertThrows(BackupException::class.java) { generation.exportLocalState() }.reason)
            assertThrows(BackupException::class.java) { codec.seal(snapshot(), generation, UUID(8, 9), 2) }
            assertThrows(BackupException::class.java) { BackupGeneration.importLocalState(ByteArray(1000)) }
        }
    }

    @Test fun everySealHasFreshNonceAndStableWrapper() {
        credential().use { password -> codec.createGeneration(password).use { generation ->
            val first = codec.seal(snapshot(), generation, UUID(8, 9), 1)
            val second = codec.seal(snapshot(), generation, UUID(8, 9), 1)
            assertArrayEquals(first.copyOfRange(0, 120), second.copyOfRange(0, 120))
            assertFalse(first.copyOfRange(144, 156).contentEquals(second.copyOfRange(144, 156)))
            assertFalse(first.contentEquals(second))
            assertSnapshot(snapshot(), codec.open(second, password))
        } }
    }

    @Test fun passwordRotationCreatesIndependentGeneration() {
        val first = sealed()
        BackupCredential.password("new password 123!".toCharArray()).use { password ->
            codec.createGeneration(password).use { generation ->
                val second = codec.seal(snapshot(), generation, UUID(8, 9), 2)
                assertFalse(first.copyOfRange(0, 120).contentEquals(second.copyOfRange(0, 120)))
                assertSnapshot(snapshot(), codec.open(second, password))
                credential().use { assertThrows(BackupException::class.java) { codec.open(second, it) } }
            }
        }
    }

    @Test fun everyHeaderFieldWrapperNonceAndTagIsAuthenticated() {
        val data = sealed()
        credential().use { password ->
            for (offset in listOf(0, 8, 10, 11, 12, 16, 20, 24, 28, 44, 60, 72, 103, 104, 119, 120, 136, 144, 156, 160, data.lastIndex)) {
                val changed = data.copyOf()
                changed[offset] = (changed[offset].toInt() xor 1).toByte()
                assertThrows("offset=$offset", BackupException::class.java) { codec.open(changed, password) }
            }
        }
    }

    @Test fun truncationTrailingDataAndLargeContainersAreRejected() {
        val data = sealed()
        credential().use { password ->
            for (size in listOf(0, 8, 72, 120, 159, 175, data.size - 1)) {
                assertThrows(BackupException::class.java) { codec.open(data.copyOf(size), password) }
            }
            assertThrows(BackupException::class.java) { codec.open(data + 0.toByte(), password) }
            assertEquals(BackupFailure.LIMIT_EXCEEDED, assertThrows(BackupException::class.java) {
                codec.open(ByteArray(16 * 1024 * 1024 + 1), password)
            }.reason)
        }
    }

    @Test fun forbiddenKdfAndLengthsFailBeforeCredentialUse() {
        val data = sealed()
        val closed = credential().also { it.close() }
        for ((offset, value) in listOf(16 to Int.MAX_VALUE, 20 to 4, 24 to 0, 156 to (8 * 1024 * 1024 + 1))) {
            val changed = data.copyOf()
            ByteBuffer.wrap(changed).putInt(offset, value)
            val failure = assertThrows(BackupException::class.java) { codec.open(changed, closed) }.reason
            assertTrue(failure == BackupFailure.INVALID_FORMAT || failure == BackupFailure.LIMIT_EXCEEDED)
        }
    }

    @Test fun jsonRoundTripAndInvalidModelsFailWithoutSecretDiagnostics() {
        val json = SnapshotJsonCodec.encode(snapshot())
        assertSnapshot(snapshot(), SnapshotJsonCodec.decode(json))
        val invalid = json.toString(Charsets.UTF_8).replace("\"digits\":8", "\"digits\":7")
        val error = assertThrows(BackupException::class.java) { SnapshotJsonCodec.decode(invalid.toByteArray()) }
        assertNull(error.cause)
        assertFalse(error.toString().contains("PrivateIssuer"))
        assertThrows(BackupException::class.java) { SnapshotJsonCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(BackupException::class.java) { SnapshotJsonCodec.decode(("[".repeat(10000) + "]".repeat(10000)).toByteArray()) }
    }

    @Test fun plaintextAndEntryLimitsAreEnforced() {
        assertEquals(BackupFailure.LIMIT_EXCEEDED, assertThrows(BackupException::class.java) {
            SnapshotJsonCodec.decode(ByteArray(8 * 1024 * 1024 + 1))
        }.reason)
        val escaped = snapshot().entries.first().copy(
            issuer = "\"".repeat(256), accountName = "\"".repeat(256), groupId = null,
        )
        val largeSnapshot = VaultSnapshot(revision = 1,
            entries = List(10_000) { escaped.copy(id = UUID(0, it.toLong())) }, groups = emptyList())
        assertEquals(BackupFailure.LIMIT_EXCEEDED, assertThrows(BackupException::class.java) {
            SnapshotJsonCodec.encode(largeSnapshot)
        }.reason)
        val entry = SnapshotJsonCodec.encode(snapshot()).toString(Charsets.UTF_8)
            .substringAfter("\"entries\":[").substringBefore("},{") + "}"
        val tooMany = "{\"schemaVersion\":1,\"revision\":1,\"groups\":[],\"entries\":[" + List(10001) { entry }.joinToString(",") + "]}"
        assertEquals(BackupFailure.LIMIT_EXCEEDED, assertThrows(BackupException::class.java) {
            SnapshotJsonCodec.decode(tooMany.toByteArray())
        }.reason)
    }

    private class DeterministicRandom : SecureRandom() {
        private val source = java.util.Random(77)
        override fun nextBytes(bytes: ByteArray) = source.nextBytes(bytes)
    }
}
