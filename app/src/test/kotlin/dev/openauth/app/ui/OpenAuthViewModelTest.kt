package dev.openauth.app.ui

import dev.openauth.app.vault.*
import dev.openauth.core.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAuthViewModelTest {
    private val storage = object : VaultStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
    private val keys = object : VaultKeyProtector {
        override fun prepare(create: Boolean) = Unit
        override fun wrap(key: ByteArray) = key.copyOf()
        override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
    }
    private val repo = VaultRepository(storage, keys, Dispatchers.Unconfined)
    private val model = OpenAuthViewModel(repo)
    private fun snapshot() = (repo.state.value as VaultState.Unlocked).snapshot
    private fun draft(type: OtpType = OtpType.TOTP) = AccountDraft(
        issuer = "Example", accountName = "alice@example.org", secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", type = type)

    @Test fun manualAddUsesValidatedParametersAndProducesRfcCode() = runTest {
        repo.initialize()
        model.save(draft().copy(digits = 8))
        val entry = snapshot().entries.single()
        assertEquals("94287082", VisibleCodeGenerator().generate(listOf(entry), setOf(entry.id), 59)[entry.id])
    }
    @Test fun invalidManualInputDoesNotWriteVault() = runTest {
        repo.initialize()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { model.save(draft().copy(secret = "bad!")) } }
        assertEquals(0L, snapshot().revision)
    }
    @Test fun manualLabelsAreBoundedAfterTrimAndAllowColonAccountNames() = runTest {
        repo.initialize()
        model.save(draft().copy(issuer = "  " + "A".repeat(256) + "  ", accountName = "alice:work"))
        assertEquals(256, snapshot().entries.single().issuer.length)
        assertEquals("alice:work", snapshot().entries.single().accountName)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { model.save(draft().copy(accountName = "A".repeat(257))) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { model.save(draft().copy(issuer = "A".repeat(257))) } }
        assertEquals(1L, snapshot().revision)
    }
    @Test fun editingMetadataPreservesSecretAndHotpCounter() = runTest {
        repo.initialize(); model.save(draft(OtpType.HOTP).copy(counter = "4"))
        val entry = snapshot().entries.single()
        model.save(draft(OtpType.HOTP).copy(id = entry.id, issuer = "Renamed", secret = "", counter = "0"))
        assertEquals(entry.secret, snapshot().entries.single().secret)
        assertEquals(4L, snapshot().entries.single().nextCounter)
    }
    @Test fun groupAssignmentFavoriteAndSearchComposeTogether() = runTest {
        repo.initialize(); model.save(draft())
        val id = snapshot().entries.single().id
        val group = model.createGroup("Work")
        model.assignGroup(id, group); model.toggleFavorite(id)
        assertEquals(listOf(id), selectAccounts(snapshot(), "ALICE", true, group, AccountSort.ISSUER).map { it.id })
        assertTrue(selectAccounts(snapshot(), "missing", false, null, AccountSort.ISSUER).isEmpty())
    }
    @Test fun deleteRequiresMatchingConfirmationAndPreservesOtherAccounts() = runTest {
        repo.initialize(); model.save(draft()); model.save(draft().copy(accountName = "bob"))
        val id = snapshot().entries.first().id
        model.requestDelete(id)
        model.confirmDelete(UUID.randomUUID())
        assertEquals(2, snapshot().entries.size)
        model.confirmDelete(id)
        assertEquals("bob", snapshot().entries.single().accountName)
    }
    @Test fun visibleGenerationNeverConsumesHotpOrTouchesInvisibleRows() = runTest {
        repo.initialize(); model.save(draft()); model.save(draft(OtpType.HOTP))
        repo.mutate { current -> VaultSnapshot(revision = current.revision, entries =
            List(1000) { current.entries.first().copy(id = UUID.randomUUID()) } + current.entries.last(), groups = emptyList()) }
        val entries = snapshot().entries
        val calls = mutableListOf<UUID>()
        val generator = VisibleCodeGenerator { entry, _ -> calls += entry.id; "123456" }
        val visible = entries.take(5).map { it.id }.toSet() + entries.last().id
        repeat(3) { generator.generate(entries, visible, 60 + it.toLong()) }
        assertEquals(15, calls.size)
        assertTrue(calls.all { it in visible })
        assertEquals(0L, snapshot().entries.last().nextCounter)
    }
    @Test fun hotpIsIssuedOnlyOnExplicitAction() = runTest {
        repo.initialize(); model.save(draft(OtpType.HOTP))
        val id = snapshot().entries.single().id
        assertEquals("755224", model.issueHotp(id))
        assertEquals(1L, snapshot().entries.single().nextCounter)
    }
    @Test fun clockStopsWhenCollectorLeavesForegroundAndUsesAbsoluteTime() = runTest {
        var reads = 0
        val clock = OtpClock { reads++; 59L }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { clock.ticks().collect {} }
        assertEquals(1, reads)
        advanceTimeBy(2100); runCurrent()
        assertEquals(3, reads)
        job.cancel(); advanceTimeBy(3000)
        assertEquals(3, reads)
        assertEquals(59L, clock.ticks().first())
    }
}
