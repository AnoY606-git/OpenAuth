package dev.openauth.app.vault

import dev.openauth.core.model.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VaultRepositoryTest {
    private class MemoryStorage : VaultStorage {
        var bytes: ByteArray? = null
        var fail = false
        var onWrite: (() -> Unit)? = null
        override fun read() = bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) {
            onWrite?.invoke()
            if (fail) throw IOException("synthetic write failure")
            this.bytes = bytes.copyOf()
        }
    }
    private class TestProtector : VaultKeyProtector {
        var denied = false
        var onUnwrap: (() -> Unit)? = null
        override fun prepare(create: Boolean) = Unit
        override fun wrap(key: ByteArray): ByteArray = key.copyOf()
        override fun unwrap(wrapped: ByteArray): ByteArray {
            onUnwrap?.invoke()
            if (denied) throw VaultException(VaultFailure.KEY_UNAVAILABLE)
            return wrapped.copyOf()
        }
    }
    private val storage = MemoryStorage()
    private val protector = TestProtector()
    private val repo = VaultRepository(storage, protector, Dispatchers.Unconfined)
    private fun snapshot() = (repo.state.value as VaultState.Unlocked).snapshot
    private fun entry(counter: Long = 0) = OtpEntry(UUID.randomUUID(), "Test", "Synthetic",
        OtpSecret.fromBytes("12345678901234567890".toByteArray()), OtpType.HOTP,
        HashAlgorithm.SHA1, 6, 30, counter, null, false, 0)
    private suspend fun add(vararg entries: OtpEntry) = repo.mutate {
        VaultSnapshot(revision = it.revision, entries = it.entries + entries, groups = it.groups)
    }

    @Test fun failedWritePreservesSnapshotAndEncryptedFile() = runTest {
        repo.initialize()
        val original = storage.bytes!!.copyOf()
        storage.fail = true
        assertThrows(IOException::class.java) { runBlocking { add(entry()) } }
        assertEquals(0, snapshot().revision)
        assertTrue(snapshot().entries.isEmpty())
        assertArrayEquals(original, storage.bytes)
    }
    @Test fun lockDuringInitialWriteLeavesExistingVaultReadyForUnlock() = runTest {
        storage.onWrite = { repo.lock() }
        val failure = assertThrows(VaultException::class.java) { runBlocking { repo.initialize() } }
        assertEquals(VaultFailure.LOCKED, failure.reason)
        assertEquals(VaultState.Locked, repo.state.value)
        storage.onWrite = null
        repo.unlock()
        assertTrue(snapshot().entries.isEmpty())
    }
    @Test fun cancellationDuringInitialWriteDoesNotOpenSession() = runTest {
        val operation = launch(start = CoroutineStart.LAZY) { repo.initialize() }
        storage.onWrite = { operation.cancel() }
        operation.start()
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(VaultState.Locked, repo.state.value)
        storage.onWrite = null
        repo.unlock()
        assertTrue(snapshot().entries.isEmpty())
    }
    @Test fun concurrentMutationsSerializeFromLatestSnapshot() = runTest {
        repo.initialize()
        coroutineScope { repeat(40) { launch(Dispatchers.Default) { add(entry()) } } }
        assertEquals(40, snapshot().entries.size)
        assertEquals(40, snapshot().revision)
    }
    @Test fun corruptedFileIsNeverInitializedAsEmpty() = runTest {
        storage.bytes = byteArrayOf(1, 2, 3)
        assertThrows(VaultException::class.java) { runBlocking { repo.unlock() } }
        assertEquals(VaultState.Unavailable(VaultFailure.CORRUPT_STORAGE), repo.state.value)
        assertThrows(VaultException::class.java) { runBlocking { repo.initialize() } }
        assertArrayEquals(byteArrayOf(1, 2, 3), storage.bytes)
    }
    @Test fun hotpReservesAllAliasesAtMaximumBeforeReturningCode() = runTest {
        repo.initialize()
        val first = entry(1)
        val alias = entry(4)
        add(first, alias)
        val code = repo.issueHotp(first.id)
        assertEquals("338314", code)
        assertEquals(listOf(5L, 5L), snapshot().entries.map { it.nextCounter })
        repo.lock(); repo.unlock()
        assertEquals(listOf(5L, 5L), snapshot().entries.map { it.nextCounter })
    }
    @Test fun hotpWriteFailureReturnsNoCodeAndKeepsCounter() = runTest {
        repo.initialize(); val first = entry(); add(first)
        storage.fail = true
        assertThrows(IOException::class.java) { runBlocking { repo.issueHotp(first.id) } }
        assertEquals(0, snapshot().entries.single().nextCounter)
    }
    @Test fun hotpOverflowReturnsNoCode() = runTest {
        repo.initialize(); val first = entry(Long.MAX_VALUE); add(first)
        assertThrows(ArithmeticException::class.java) { runBlocking { repo.issueHotp(first.id) } }
        assertEquals(Long.MAX_VALUE, snapshot().entries.single().nextCounter)
    }
    @Test fun lockDuringWriteHidesStateAndCannotReopenSession() = runTest {
        repo.initialize()
        storage.onWrite = { repo.lock(); assertEquals(VaultState.Locked, repo.state.value) }
        assertThrows(VaultException::class.java) { runBlocking { add(entry()) } }
        assertEquals(VaultState.Locked, repo.state.value)
    }
    @Test fun missingKeyPreservesEncryptedFileAndShowsRecoveryState() = runTest {
        repo.initialize(); repo.lock()
        val original = storage.bytes!!.copyOf()
        protector.denied = true
        assertThrows(VaultException::class.java) { runBlocking { repo.unlock() } }
        assertEquals(VaultState.Unavailable(VaultFailure.KEY_UNAVAILABLE), repo.state.value)
        assertArrayEquals(original, storage.bytes)
    }
    @Test fun backupGenerationIsPrivateAtomicDefensiveAndSurvivesMutation() = runTest {
        repo.initialize()
        val blob = ByteArray(160) { it.toByte() }
        repo.saveBackupGenerationState(blob)
        blob.fill(0)
        assertEquals(0, snapshot().revision)
        val saved = repo.readBackupGenerationState()!!
        assertEquals(1.toByte(), saved[1])
        saved.fill(0)
        add(entry())
        repo.lock(); repo.unlock()
        assertEquals(1.toByte(), repo.readBackupGenerationState()!![1])
        storage.fail = true
        assertThrows(IOException::class.java) { runBlocking { repo.saveBackupGenerationState(null) } }
        assertNotNull(repo.readBackupGenerationState())
        repo.lock()
        assertThrows(VaultException::class.java) { runBlocking { repo.readBackupGenerationState() } }
    }
    @Test fun lockDuringUnwrapCannotPublishUnlockedSnapshot() = runTest {
        repo.initialize(); repo.lock()
        protector.onUnwrap = { repo.lock() }
        assertThrows(VaultException::class.java) { runBlocking { repo.unlock() } }
        assertEquals(VaultState.Locked, repo.state.value)
    }
    @Test fun mutationCannotRollBackHotpGeneratorThroughAlias() = runTest {
        repo.initialize(); val first = entry(9); add(first)
        val alias = entry(1); add(alias)
        assertEquals(listOf(9L, 9L), snapshot().entries.map { it.nextCounter })
        repo.mutate { VaultSnapshot(revision = 0, entries = it.entries.map { e -> e.copy(nextCounter = 0) }, groups = it.groups) }
        assertEquals(listOf(9L, 9L), snapshot().entries.map { it.nextCounter })
    }
    @Test fun hotpAliasWithDifferentPeriodCannotReissueConsumedCounter() = runTest {
        repo.initialize()
        val first = entry()
        add(first)
        assertEquals("755224", repo.issueHotp(first.id))
        val alias = entry().copy(periodSeconds = 60)
        add(alias)
        assertEquals("287082", repo.issueHotp(alias.id))
        assertEquals(listOf(2L, 2L), snapshot().entries.map { it.nextCounter })
        repo.lock(); repo.unlock()
        assertEquals(listOf(2L, 2L), snapshot().entries.map { it.nextCounter })
    }
    @Test fun changingOnlyHotpPeriodCannotLowerCounterFloor() = runTest {
        repo.initialize()
        val first = entry()
        add(first)
        assertEquals("755224", repo.issueHotp(first.id))
        repo.mutate { VaultSnapshot(revision = it.revision,
            entries = it.entries.map { e -> e.copy(periodSeconds = 60, nextCounter = 0) }, groups = it.groups) }
        assertEquals(60, snapshot().entries.single().periodSeconds)
        assertEquals(1L, snapshot().entries.single().nextCounter)
        assertEquals("287082", repo.issueHotp(first.id))
        assertEquals(2L, snapshot().entries.single().nextCounter)
    }
    @Test fun tamperedCiphertextFailsClosed() = runTest {
        repo.initialize(); add(entry()); repo.lock()
        storage.bytes!![storage.bytes!!.lastIndex] = (storage.bytes!!.last().toInt() xor 1).toByte()
        assertThrows(VaultException::class.java) { runBlocking { repo.unlock() } }
        assertEquals(VaultState.Unavailable(VaultFailure.CORRUPT_STORAGE), repo.state.value)
    }

    @Test fun hotpTypeRoundTripCannotResetPreviouslyIssuedCounter() = runTest {
        repo.initialize(); val first = entry(); add(first)
        assertEquals("755224", repo.issueHotp(first.id))
        repo.mutate { VaultSnapshot(revision = it.revision, entries = listOf(first.copy(type = OtpType.TOTP)), groups = emptyList()) }
        repo.lock(); repo.unlock()
        repo.mutate { VaultSnapshot(revision = it.revision, entries = listOf(first), groups = emptyList()) }
        assertEquals(1L, snapshot().entries.single().nextCounter)
        assertEquals("287082", repo.issueHotp(first.id))
    }

    @Test fun hotpDigitsRoundTripAndAliasesShareOneCounterFloor() = runTest {
        repo.initialize(); val first = entry(); add(first)
        assertEquals("755224", repo.issueHotp(first.id))
        repo.mutate { VaultSnapshot(revision = it.revision, entries = listOf(first.copy(digits = 8)), groups = emptyList()) }
        assertEquals(1L, snapshot().entries.single().nextCounter)
        val alias = entry().copy(digits = 6)
        add(alias)
        assertEquals("94287082", repo.issueHotp(first.id))
        assertEquals(listOf(2L, 2L), snapshot().entries.map { it.nextCounter })
        assertEquals("359152", repo.issueHotp(alias.id))
    }

    @Test fun deletingLastAliasAndReimportingAfterRestartCannotReuseCode() = runTest {
        repo.initialize(); val first = entry(); add(first)
        assertEquals("755224", repo.issueHotp(first.id))
        repo.mutate { VaultSnapshot(revision = it.revision, entries = emptyList(), groups = emptyList()) }
        assertTrue(snapshot().entries.isEmpty())
        assertEquals(1L, snapshot().hotpCounterFloors.single().nextCounter)
        repo.lock(); repo.unlock()
        add(first.copy(id = UUID.randomUUID()))
        assertEquals("287082", repo.issueHotp(snapshot().entries.single().id))
    }

    @Test fun failedCounterWriteDoesNotPublishHistoryOrReturnCode() = runTest {
        repo.initialize(); val first = entry(); add(first)
        val original = storage.bytes!!.copyOf()
        storage.fail = true
        assertThrows(IOException::class.java) { runBlocking { repo.issueHotp(first.id) } }
        assertTrue(snapshot().hotpCounterFloors.isEmpty())
        assertArrayEquals(original, storage.bytes)
        storage.fail = false
        assertEquals("755224", repo.issueHotp(first.id))
    }

    @Test fun legacySchemaOneSeedsHistoryBeforeFirstDeletion() = runTest {
        val key = VaultCipher.newKey()
        val json = dev.openauth.core.backup.SnapshotJsonCodec.encode(VaultSnapshot(1, 0, listOf(entry(9)), emptyList()))
        val plaintext = java.nio.ByteBuffer.allocate(12 + json.size).putInt(1).putInt(json.size).put(json).putInt(0).array()
        val header = java.nio.ByteBuffer.allocate(8 + key.size).putInt(0x4f415631).putInt(key.size).put(key).array()
        storage.bytes = header + VaultCipher.encrypt(key, plaintext, header)
        key.fill(0); json.fill(0); plaintext.fill(0)
        repo.unlock()
        assertEquals(9L, snapshot().hotpCounterFloors.single().nextCounter)
        val first = snapshot().entries.single()
        repo.mutate { VaultSnapshot(revision = it.revision, entries = emptyList(), groups = emptyList()) }
        repo.lock(); repo.unlock(); add(first.copy(nextCounter = 0))
        assertEquals(9L, snapshot().entries.single().nextCounter)
    }
}
