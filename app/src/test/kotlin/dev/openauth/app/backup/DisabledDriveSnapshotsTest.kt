package dev.openauth.app.backup

import dev.openauth.app.vault.VaultKeyProtector
import dev.openauth.app.vault.VaultRepository
import dev.openauth.app.vault.VaultState
import dev.openauth.app.vault.VaultStorage
import dev.openauth.core.backup.BackupCodec
import dev.openauth.core.backup.BackupCredential
import dev.openauth.core.model.VaultSnapshot
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DisabledDriveSnapshotsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun disabledAutomaticSnapshotsKeepExistingCiphertextWhileManualFileExportUsesCurrentVault() = runBlocking {
        val storage = object : VaultStorage {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun writeAtomically(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        }
        val keys = object : VaultKeyProtector {
            override fun prepare(create: Boolean) = Unit
            override fun wrap(key: ByteArray) = key.copyOf()
            override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
        }
        val protector = object : MetadataProtector {
            override fun seal(bytes: ByteArray) = bytes.copyOf()
            override fun open(bytes: ByteArray) = bytes.copyOf()
        }
        val directory = temporary.newFolder()
        val queue = EncryptedSnapshotQueue(directory, protector, TestAtomicBytes(java.io.File(directory, "metadata")))
        val configuration = UUID.randomUUID()
        queue.setActiveConfiguration(configuration)
        val vault = VaultRepository(storage, keys, Dispatchers.Unconfined)
        vault.initialize()
        fun credential() = BackupCredential.password("synthetic portable file password".toCharArray())
        val priorVersion = BackupCoordinator(vault, queue, dispatcher = Dispatchers.Unconfined)
        priorVersion.configure(credential())
        val pending = queue.latest()!!
        val originalCiphertext = pending.file.readBytes()

        val currentVersion = BackupCoordinator(vault, queue, dispatcher = Dispatchers.Unconfined,
            automaticSnapshotsEnabled = false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            currentVersion.startObserving(scope)
            vault.lock(); vault.unlock()
            vault.mutate { VaultSnapshot(revision = it.revision, entries = it.entries, groups = it.groups) }
            currentVersion.recoverPending()
            currentVersion.rotate(credential())

            assertEquals(configuration, queue.activeConfigurationId())
            assertEquals(pending.snapshotId, queue.latest()!!.snapshotId)
            assertArrayEquals(originalCiphertext, pending.file.readBytes())
            val exported = currentVersion.exportSnapshot()
            val opened = credential().use { BackupCodec().open(exported, it) }
            assertEquals((vault.state.value as VaultState.Unlocked).snapshot.revision, opened.revision)
            assertTrue(opened.revision > pending.revision)
            assertEquals(pending.snapshotId, queue.latest()!!.snapshotId)
        } finally { scope.cancel() }
    }
}
