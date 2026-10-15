package dev.openauth.app.backup

import dev.openauth.app.vault.*
import dev.openauth.core.backup.*
import dev.openauth.core.model.*
import dev.openauth.core.restore.RestoreMode
import dev.openauth.core.otp.OtpEngine
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Storage : VaultStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
    private class Keys : VaultKeyProtector {
        override fun prepare(create: Boolean) = Unit
        override fun wrap(key: ByteArray) = key.copyOf()
        override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
    }
    private class Protector : MetadataProtector {
        var fail = false
        override fun seal(bytes: ByteArray): ByteArray { if (fail) throw IOException(); return bytes.copyOf() }
        override fun open(bytes: ByteArray) = bytes.copyOf()
    }
    private fun credential() = BackupCredential.password("synthetic strong password".toCharArray())
    private fun repository(storage: Storage = Storage()) = VaultRepository(storage, Keys(), Dispatchers.Unconfined)
    private fun entry() = OtpEntry(UUID.randomUUID(), "Test", "Synthetic", OtpSecret.fromBytes("12345678901234567890".toByteArray()), OtpType.HOTP, HashAlgorithm.SHA1, 6, 30, 5, null, false, 0)
    private fun snapshot(repo: VaultRepository) = (repo.state.value as VaultState.Unlocked).snapshot
    private fun queue(protector: Protector = Protector()): EncryptedSnapshotQueue {
        val dir = temporary.newFolder(); return EncryptedSnapshotQueue(dir, protector, TestAtomicBytes(File(dir, "metadata")))
    }
    private fun coordinator(repo: VaultRepository, queue: EncryptedSnapshotQueue) = BackupCoordinator(repo, queue, dispatcher = Dispatchers.Unconfined)

    @Test fun generationWriteRejectsPreviousUnlockedSession() = runBlocking {
        val repo = repository(); repo.initialize(); val base = snapshot(repo)
        repo.lock(); repo.unlock()
        assertThrows(VaultException::class.java) { runBlocking {
            repo.saveBackupGenerationState(ByteArray(160), expectedSnapshot = base)
        } }
        assertNull(repo.readBackupGenerationState())
    }
    @Test fun portableFileRestoresWithNewLocalKeyAndSameOtp() = runBlocking {
        val source = repository(); source.initialize()
        source.mutate { VaultSnapshot(revision = it.revision, entries = listOf(entry()), groups = emptyList()) }
        val writer = coordinator(source, queue()); writer.configure(credential())
        val encrypted = writer.exportSnapshot()
        val target = repository(); target.initialize()
        val reader = coordinator(target, queue())
        val preview = reader.previewRestore(encrypted, credential(), RestoreMode.REPLACE)
        assertTrue(snapshot(target).entries.isEmpty())
        reader.commitRestore(preview)
        val a = snapshot(source).entries.single(); val b = snapshot(target).entries.single()
        assertEquals(OtpEngine.hotp(a.secret, a.nextCounter, a.digits), OtpEngine.hotp(b.secret, b.nextCounter, b.digits))
        assertNull(target.readBackupGenerationState())
    }
    @Test fun wrongPasswordTamperingAndStalePreviewNeverWrite() = runBlocking {
        val repo = repository(); repo.initialize(); val service = coordinator(repo, queue()); service.configure(credential())
        val encrypted = service.exportSnapshot(); val before = snapshot(repo)
        assertThrows(BackupException::class.java) { runBlocking { service.previewRestore(encrypted, BackupCredential.password("another incorrect password".toCharArray()), RestoreMode.REPLACE) } }
        val corrupted = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(BackupException::class.java) { runBlocking { service.previewRestore(corrupted, credential(), RestoreMode.REPLACE) } }
        assertSame(before, snapshot(repo))
        val plan = service.previewRestore(encrypted, credential(), RestoreMode.REPLACE)
        repo.mutate { VaultSnapshot(revision = it.revision, entries = listOf(entry()), groups = emptyList()) }
        assertThrows(BackupFlowException::class.java) { runBlocking { service.commitRestore(plan) } }
        assertEquals(1, snapshot(repo).entries.size)
    }
    @Test fun lockAndUnlockInvalidatePreviewEvenAtSameRevision() = runBlocking {
        val repo = repository(); repo.initialize(); val service = coordinator(repo, queue()); service.configure(credential())
        val plan = service.previewRestore(service.exportSnapshot(), credential(), RestoreMode.REPLACE)
        repo.lock(); repo.unlock()
        assertThrows(BackupFlowException::class.java) { runBlocking { service.commitRestore(plan) } }
        assertEquals(0L, snapshot(repo).revision)
    }
    @Test fun committedRevisionRecoversAfterQueueFailureAndRotationRequeues() = runBlocking {
        val repo = repository(); repo.initialize(); val protector = Protector(); val queue = queue(protector)
        queue.setActiveConfiguration(UUID.randomUUID())
        val service = coordinator(repo, queue); service.configure(credential())
        val first = queue.latest()!!
        repo.mutate { VaultSnapshot(revision = it.revision, entries = listOf(entry()), groups = emptyList()) }
        protector.fail = true
        assertThrows(Exception::class.java) { runBlocking { service.recoverPending() } }
        assertEquals(1L, snapshot(repo).revision)
        protector.fail = false; repo.lock(); repo.unlock(); service.recoverPending()
        val recovered = queue.latest()!!
        assertEquals(1L, recovered.revision); assertNotEquals(first.snapshotId, recovered.snapshotId)
        service.rotate(credential())
        assertNotEquals(recovered.snapshotId, queue.latest()!!.snapshotId)
        val stable = queue.latest()!!.snapshotId; service.recoverPending(); assertEquals(stable, queue.latest()!!.snapshotId)
    }
    @Test fun durableRemoteIdAndClaimSurviveReplacementWithoutRedirecting() {
        val directory = temporary.newFolder(); val protector = Protector(); val queue = EncryptedSnapshotQueue(directory, protector, TestAtomicBytes(File(directory, "metadata")))
        val configuration = UUID.randomUUID(); queue.setActiveConfiguration(configuration)
        val first = queue.enqueue(ByteArray(200), 1, "generation", 1)
        val claim = queue.claim(configuration)!!
        queue.assignRemoteFileId(claim, "generated_remote_id")
        val restarted = EncryptedSnapshotQueue(directory, protector, TestAtomicBytes(File(directory, "metadata")))
        assertEquals("generated_remote_id", restarted.latest()!!.remoteFileId)
        assertEquals(queue.sourceId(), restarted.sourceId())
        queue.enqueue(ByteArray(201), 2, "generation", 2)
        assertTrue(first.file.exists())
        queue.setActiveConfiguration(UUID.randomUUID())
        assertThrows(BackupFlowException::class.java) { queue.markUploaded(claim) }
        assertTrue(first.file.exists()); claim.close(); assertFalse(first.file.exists())
    }
    @Test fun restartRemovesAbandonedClaimCiphertext() {
        val directory = temporary.newFolder(); val protector = Protector()
        val bytes = TestAtomicBytes(File(directory, "metadata"))
        val queue = EncryptedSnapshotQueue(directory, protector, bytes)
        val config = UUID.randomUUID(); queue.setActiveConfiguration(config)
        val old = queue.enqueue(ByteArray(200), 1, "generation", 1)
        queue.claim(config)!! // Процесс умер, finally не исполнился.
        queue.enqueue(ByteArray(201), 2, "generation", 2)
        val restarted = EncryptedSnapshotQueue(directory, protector, bytes)
        assertEquals(2L, restarted.latest()!!.revision)
        assertFalse(old.file.exists())
    }
    @Test fun missingPendingCiphertextIsRegeneratedOnUnlock() = runBlocking {
        val repo = repository(); repo.initialize(); val queue = queue()
        queue.setActiveConfiguration(UUID.randomUUID())
        val service = coordinator(repo, queue); service.configure(credential())
        val old = queue.latest()!!; assertTrue(old.file.delete())
        repo.lock(); repo.unlock(); service.recoverPending()
        assertNotEquals(old.snapshotId, queue.latest()!!.snapshotId)
        assertTrue(queue.latest()!!.file.isFile)
    }
    @Test fun uploadedCheckpointSurvivesRestartAndPreventsDuplicateSnapshot() = runBlocking {
        val directory = temporary.newFolder(); val protector = Protector()
        val bytes = TestAtomicBytes(File(directory, "metadata"))
        val queue = EncryptedSnapshotQueue(directory, protector, bytes)
        val config = UUID.randomUUID(); queue.setActiveConfiguration(config)
        val repo = repository(); repo.initialize(); val service = coordinator(repo, queue)
        service.configure(credential())
        val id = queue.latest()!!.snapshotId
        queue.claim(config)!!.use { claim ->
            queue.assignRemoteFileId(claim, "persisted_remote_id"); queue.markUploaded(claim)
        }
        val restarted = EncryptedSnapshotQueue(directory, protector, bytes)
        assertNull(restarted.latest())
        assertTrue(restarted.checkpoint()!!.uploaded)
        coordinator(repo, restarted).recoverPending()
        assertNull(restarted.latest())
        assertEquals(id, restarted.checkpoint()!!.snapshot.snapshotId)
    }
    @Test fun automaticRecoveryDoesNotCreateAnythingWithoutExplicitDestination() = runBlocking {
        val repo = repository(); repo.initialize(); val queue = queue(); val service = coordinator(repo, queue)
        service.configure(credential()); service.recoverPending(); assertNull(queue.latest())
    }
}

internal class TestAtomicBytes(private val file: File) : AtomicBytes {
    override fun read(): ByteArray? = if (file.exists()) file.readBytes() else null
    override fun writeAtomically(bytes: ByteArray) { file.parentFile!!.mkdirs(); file.writeBytes(bytes) }
}
