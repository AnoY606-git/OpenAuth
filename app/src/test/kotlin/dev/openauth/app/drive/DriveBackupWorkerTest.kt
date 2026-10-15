package dev.openauth.app.drive

import dev.openauth.app.backup.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Настоящие durable queue/destination; подмена только внешнего OAuth/HTTP. */
class DriveBackupWorkerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val protector = object : MetadataProtector {
        override fun seal(bytes: ByteArray) = bytes.map { (it.toInt() xor 91).toByte() }.toByteArray()
        override fun open(bytes: ByteArray) = seal(bytes)
    }
    private class Disk(private val file: File) : AtomicBytes {
        override fun read() = if (file.exists()) file.readBytes() else null
        override fun writeAtomically(bytes: ByteArray) { file.writeBytes(bytes) }
    }
    private inner class Fixture {
        val directory = temporary.newFolder()
        val queue = EncryptedSnapshotQueue(directory, protector, Disk(File(directory, "queue")))
        val store = DriveDestinationStore(Disk(File(directory, "destination")), protector)
        val events = DriveSyncStatus()
        val destination = DriveDestination("a@example.test", UUID.randomUUID(), queue.sourceId())
        var auth: suspend (String) -> DriveAccess = { DriveAccess.Granted(it, "token-a") }
        val uploaded = mutableListOf<Pair<String, UUID>>()
        val trimmed = mutableListOf<UUID>()
        var generates = 0
        var generateAction: () -> Unit = {}
        var uploadAction: suspend () -> Unit = {}
        var trimAction: () -> Unit = {}
        val transport = object : DriveRemote {
            override suspend fun generateFileId(token: String): String { generates++; generateAction(); return "remote-$generates" }
            override suspend fun upload(token: String, snapshot: PendingSnapshot): DriveSnapshot {
                assertEquals(snapshot.remoteFileId, EncryptedSnapshotQueue(directory, protector, Disk(File(directory, "queue"))).latest()?.remoteFileId)
                assertTrue(snapshot.file.isFile)
                uploaded.add(token to snapshot.configurationId); uploadAction()
                return DriveSnapshot(snapshot.remoteFileId!!, snapshot.snapshotId, snapshot.sourceId, snapshot.createdAtEpochSeconds, snapshot.revision, snapshot.file.length())
            }
            override suspend fun trimHistory(token: String, sourceId: UUID) { trimmed.add(sourceId); trimAction() }
        }
        init { store.write(destination); queue.setActiveConfiguration(destination.configurationId) }
        fun pending(revision: Long = 1) = queue.enqueue(ByteArray(176) { 42 }, revision, "generation", 100)
        fun switchAccount() { queue.setActiveConfiguration(null); store.write(destination.copy(accountName = "b@example.test", configurationId = UUID.randomUUID())); queue.setActiveConfiguration(store.read()!!.configurationId) }
        fun engine(provider: DriveTokenProvider = DriveTokenProvider { auth(it) }, remote: DriveRemote = transport) = DriveUploadEngine(queue, store, provider, remote, events, now = { 12345L })
    }

    @Test fun confirmedUploadTimeSurvivesRestartPendingAndFailureButNotAccountSwitch() = runBlocking {
        val f = Fixture(); f.pending(); f.engine().run(f.destination.configurationId)
        assertEquals(12345L, f.events.state.value.lastUploadedAtEpochSeconds)
        f.pending(2); f.uploadAction = { throw DriveException(DriveFailure.NETWORK) }
        f.engine().run(f.destination.configurationId)
        assertEquals(12345L, f.events.state.value.lastUploadedAtEpochSeconds)
        val fresh = DriveSyncStatus()
        DriveConnectionController(f.queue, DriveDestinationStore(Disk(File(f.directory, "destination")), protector), fresh, {}, {}, {}).refreshStatus()
        assertEquals(12345L, fresh.state.value.lastUploadedAtEpochSeconds)
        assertEquals(DriveSyncPhase.PENDING, fresh.state.value.phase)
        f.switchAccount(); val changed = DriveSyncStatus()
        DriveConnectionController(f.queue, f.store, changed, {}, {}, {}).refreshStatus()
        assertNull(changed.state.value.lastUploadedAtEpochSeconds)
    }
    @Test fun revokeStopsQueueBeforeExternalCallAndReportsFailureWithoutClearingCapturedAccount() = runBlocking {
        val f = Fixture(); f.pending(); var cancelled = false
        val controller = DriveConnectionController(f.queue, f.store, f.events, {}, { cancelled = true }, {})
        assertThrows(DriveConnectionException::class.java) { runBlocking {
            controller.revoke(f.destination) { account ->
                assertEquals(f.destination.accountName, account); assertTrue(cancelled); assertNull(f.queue.activeConfigurationId())
                throw DriveConnectionException(DriveConnectionFailure.AUTHORIZATION)
            }
        } }
        assertEquals(f.destination, f.store.read())
        assertEquals(DriveSyncPhase.FAILED, f.events.state.value.phase)
    }
    @Test fun revokeStaleSelectionNeverRevokesDifferentAccount() = runBlocking {
        val f = Fixture(); f.switchAccount()
        val controller = DriveConnectionController(f.queue, f.store, f.events, {}, {}, {})
        assertThrows(DriveConnectionException::class.java) { runBlocking { controller.revoke(f.destination) { fail("Stale account revoked") } } }
        assertEquals("b@example.test", f.store.read()!!.accountName)
    }

    @Test fun persistsIdBeforeUploadAndMarksVerifiedCiphertextUploaded() = runBlocking {
        val f = Fixture(); val pending = f.pending()
        assertEquals(DriveRunResult.SUCCESS, f.engine().run(f.destination.configurationId))
        assertTrue(f.queue.checkpoint()!!.uploaded)
        assertEquals(pending.snapshotId, f.queue.checkpoint()!!.snapshot.snapshotId)
        assertEquals(listOf(f.destination.sourceId), f.trimmed)
        assertEquals(DriveSyncPhase.UPLOADED, f.events.state.value.phase)
    }
    @Test fun networkRetryReusesDurableIdAfterNewEngine() = runBlocking {
        val f = Fixture(); f.pending(); f.uploadAction = { throw DriveException(DriveFailure.NETWORK) }
        assertEquals(DriveRunResult.RETRY, f.engine().run(f.destination.configurationId))
        assertEquals("remote-1", f.queue.latest()!!.remoteFileId)
        f.uploadAction = {}
        assertEquals(DriveRunResult.SUCCESS, f.engine().run(f.destination.configurationId))
        assertEquals(1, f.generates)
    }
    @Test fun consentPausesWithoutUploadOrRetryLoop() = runBlocking {
        val f = Fixture(); f.pending(); f.auth = { DriveAccess.NeedsConsent() }
        assertEquals(DriveRunResult.WAITING_FOR_USER, f.engine().run(f.destination.configurationId))
        assertTrue(f.uploaded.isEmpty()); assertNotNull(f.queue.latest())
        assertEquals(DriveSyncPhase.NEEDS_CONSENT, f.events.state.value.phase)
    }
    @Test fun mismatchedGrantedAccountNeverUploads() = runBlocking {
        val f = Fixture(); f.pending(); f.auth = { DriveAccess.Granted("b@example.test", "token-b") }
        assertEquals(DriveRunResult.WAITING_FOR_USER, f.engine().run(f.destination.configurationId))
        assertTrue(f.uploaded.isEmpty())
    }
    @Test fun switchDuringAuthorizationCannotPublishOrUploadToNewAccount() = runBlocking {
        val f = Fixture(); f.pending(); f.auth = { f.switchAccount(); DriveAccess.Granted(it, "token-a") }
        assertEquals(DriveRunResult.OBSOLETE, f.engine().run(f.destination.configurationId))
        assertTrue(f.uploaded.isEmpty()); assertTrue(f.trimmed.isEmpty())
        assertNotEquals(DriveSyncPhase.UPLOADED, f.events.state.value.phase)
    }
    @Test fun switchDuringIdGenerationStopsBeforeUpload() = runBlocking {
        val f = Fixture(); f.pending(); f.generateAction = { f.switchAccount() }
        assertEquals(DriveRunResult.OBSOLETE, f.engine().run(f.destination.configurationId))
        assertTrue(f.uploaded.isEmpty()); assertTrue(f.trimmed.isEmpty())
    }
    @Test fun switchDuringUploadDoesNotTrimOrPublishNewDestinationSuccess() = runBlocking {
        val f = Fixture(); f.pending(); f.uploadAction = { f.switchAccount() }
        assertEquals(DriveRunResult.OBSOLETE, f.engine().run(f.destination.configurationId))
        assertEquals(listOf("token-a" to f.destination.configurationId), f.uploaded)
        assertTrue(f.trimmed.isEmpty()); assertNull(f.queue.checkpoint())
        assertNotEquals(DriveSyncPhase.UPLOADED, f.events.state.value.phase)
    }
    @Test fun switchDuringTrimCannotPublishNewDestinationSuccess() = runBlocking {
        val f = Fixture(); f.pending(); f.trimAction = { f.switchAccount() }
        assertEquals(DriveRunResult.OBSOLETE, f.engine().run(f.destination.configurationId))
        assertNull(f.queue.checkpoint()); assertNotEquals(DriveSyncPhase.UPLOADED, f.events.state.value.phase)
    }
    @Test fun newestSnapshotArrivingDuringUploadIsDrained() = runBlocking {
        val f = Fixture(); val first = f.pending()
        f.uploadAction = { f.uploadAction = {}; f.pending(2) }
        assertEquals(DriveRunResult.SUCCESS, f.engine().run(f.destination.configurationId))
        assertEquals(2, f.uploaded.size)
        assertEquals(2L, f.queue.checkpoint()!!.snapshot.revision)
        assertNotEquals(first.snapshotId, f.queue.checkpoint()!!.snapshot.snapshotId)
        assertNull(f.queue.latest())
    }
    @Test fun disconnectInvalidatesBeforeClearingAndPreservesRemoteFiles() = runBlocking {
        val f = Fixture(); f.pending(); var cancelled = false
        val controller = DriveConnectionController(f.queue, f.store, f.events, { }, { cancelled = true }, {})
        controller.disconnect()
        assertNull(f.queue.activeConfigurationId()); assertNull(f.store.read()); assertTrue(cancelled)
        assertTrue(f.trimmed.isEmpty()); assertEquals(DriveSyncPhase.DISCONNECTED, f.events.state.value.phase)
    }
    @Test fun connectFilledVaultInvokesInitialSnapshotAndUsesFreshConfiguration() = runBlocking {
        val f = Fixture(); var snapshots = 0; var scheduled: UUID? = null
        val controller = DriveConnectionController(f.queue, f.store, f.events, { scheduled = it }, {}, { snapshots++; f.pending() })
        val next = controller.connect("a@example.test")
        assertNotEquals(f.destination.configurationId, next.configurationId)
        assertEquals(1, snapshots); assertEquals(next.configurationId, scheduled)
        assertEquals(next.configurationId, f.queue.latest()!!.configurationId)
    }
    @Test fun pendingUntilUnlockIsNotReportedAsUploaded() = runBlocking {
        val f = Fixture()
        val controller = DriveConnectionController(f.queue, f.store, f.events, {}, {}, { throw BackupFlowException(BackupFlowFailure.LOCKED_OR_CHANGED) })
        controller.connect("a@example.test")
        assertEquals(DriveSyncPhase.WAITING_FOR_UNLOCK, f.events.state.value.phase)
        assertNotNull(f.store.read()); assertNull(f.queue.checkpoint())
    }
    @Test fun malformedEncryptedDestinationFailsClosed() {
        val f = Fixture(); File(f.directory, "destination").writeBytes(byteArrayOf(1, 2, 3))
        try { f.store.read(); fail("Corrupt destination accepted") } catch (expected: DriveConnectionException) { assertEquals(DriveConnectionFailure.STORAGE, expected.failure) }
    }
    @Test fun renewingConsentKeepsPendingCiphertextAndConfiguration() = runBlocking {
        val f = Fixture(); val pending = f.pending(); var scheduled: UUID? = null
        val controller = DriveConnectionController(f.queue, f.store, f.events, { scheduled = it }, {}, { fail("A ready ciphertext must not need unlocking or a new snapshot") })
        controller.resumeUploads()
        assertEquals(pending.snapshotId, f.queue.latest()!!.snapshotId)
        assertEquals(f.destination.configurationId, scheduled)
    }

    @Test fun disconnectInvalidatesImmediatelyWhileUploadIsPaused() = runBlocking {
        val f = Fixture(); f.pending()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.uploadAction = { entered.complete(Unit); release.await() }
        val run = async { f.engine().run(f.destination.configurationId) }
        entered.await()
        val controller = DriveConnectionController(f.queue, f.store, f.events, {}, {}, {})
        try {
            withTimeout(1000) { controller.disconnect() }
            assertNull(f.queue.activeConfigurationId()); assertNull(f.store.read())
        } finally { release.complete(Unit) }
        assertEquals(DriveRunResult.OBSOLETE, run.await())
        assertTrue(f.trimmed.isEmpty())
    }

    @Test fun recreatedControllerShowsDurableUploadedCheckpoint() = runBlocking {
        val f = Fixture(); f.pending(); f.engine().run(f.destination.configurationId)
        val freshStatus = DriveSyncStatus()
        val controller = DriveConnectionController(f.queue, f.store, freshStatus, {}, {}, {})
        controller.refreshStatus()
        assertEquals(f.destination, controller.destination())
        assertEquals(DriveSyncPhase.UPLOADED, freshStatus.state.value.phase)
    }

    @Test fun destinationCorruptionAfterUploadIsFailureWithoutSuccessPublication() = runBlocking {
        val f = Fixture(); f.pending()
        f.uploadAction = { File(f.directory, "destination").writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(DriveRunResult.FAILURE, f.engine().run(f.destination.configurationId))
        assertNotNull(f.queue.latest()); assertTrue(f.trimmed.isEmpty())
        assertNotEquals(DriveSyncPhase.UPLOADED, f.events.state.value.phase)
    }

    @Test fun transientOAuthFailureRetriesTheSamePendingSnapshot() = runBlocking {
        val f = Fixture(); val pending = f.pending()
        f.auth = { DriveAccess.Failure(classifyDriveOAuthFailure(com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR)) }
        assertEquals(DriveRunResult.RETRY, f.engine().run(f.destination.configurationId))
        assertEquals(pending.snapshotId, f.queue.latest()!!.snapshotId)
        assertEquals(DriveSyncPhase.RETRYING, f.events.state.value.phase)
        assertTrue(f.uploaded.isEmpty())
        f.auth = { DriveAccess.Granted(it, "token-a") }
        assertEquals(DriveRunResult.SUCCESS, f.engine().run(f.destination.configurationId))
        assertEquals(pending.snapshotId, f.queue.checkpoint()!!.snapshot.snapshotId)
    }
    @Test fun rejectedCachedTokenIsClearedAndSameSnapshotRetriesWithFreshToken() = runBlocking {
        val f = Fixture(); val pending = f.pending(); var cached = "bad-token"; var invalidations = 0
        val provider = object : DriveTokenProvider {
            override suspend fun authorize(accountName: String) = DriveAccess.Granted(accountName, cached)
            override suspend fun invalidate(accountName: String, rejectedToken: String) {
                assertEquals(f.destination.accountName, accountName); assertEquals("bad-token", rejectedToken)
                invalidations++; cached = "fresh-token"
            }
        }
        f.uploadAction = { if (f.uploaded.last().first == "bad-token") throw DriveException(DriveFailure.AUTH_REQUIRED) }
        assertEquals(DriveRunResult.SUCCESS, f.engine(provider).run(f.destination.configurationId))
        assertEquals(1, invalidations); assertEquals(1, f.generates)
        assertEquals(listOf("bad-token", "fresh-token"), f.uploaded.map { it.first })
        assertEquals(pending.snapshotId, f.queue.checkpoint()!!.snapshot.snapshotId)
        assertEquals("remote-1", f.queue.checkpoint()!!.snapshot.remoteFileId)
    }
    @Test fun repeated401ClearsRejectedTokensButBoundsReauthorization() = runBlocking {
        val f = Fixture(); f.pending(); var authorizations = 0; val invalidated = mutableListOf<String>()
        val provider = object : DriveTokenProvider {
            override suspend fun authorize(accountName: String): DriveAccess { authorizations++; return DriveAccess.Granted(accountName, "token-$authorizations") }
            override suspend fun invalidate(accountName: String, rejectedToken: String) { invalidated += rejectedToken }
        }
        f.uploadAction = { throw DriveException(DriveFailure.AUTH_REQUIRED) }
        assertEquals(DriveRunResult.WAITING_FOR_USER, f.engine(provider).run(f.destination.configurationId))
        assertEquals(2, authorizations); assertEquals(listOf("token-1", "token-2"), invalidated)
        assertEquals(2, f.uploaded.size); assertNotNull(f.queue.latest())
    }
    @Test fun permissionDeniedDoesNotInvalidateTokenOrRepeatAuthorization() = runBlocking {
        val f = Fixture(); f.pending(); var authorizations = 0
        val provider = object : DriveTokenProvider {
            override suspend fun authorize(accountName: String): DriveAccess { authorizations++; return DriveAccess.Granted(accountName, "token-a") }
            override suspend fun invalidate(accountName: String, rejectedToken: String) { fail("403 is not a rejected cached token") }
        }
        f.uploadAction = { throw DriveException(DriveFailure.PERMISSION_DENIED) }
        assertEquals(DriveRunResult.WAITING_FOR_USER, f.engine(provider).run(f.destination.configurationId))
        assertEquals(1, authorizations)
    }
    @Test fun accountChangeDuringTokenInvalidationStopsRefreshAndPublication() = runBlocking {
        val f = Fixture(); f.pending(); var authorizations = 0
        val provider = object : DriveTokenProvider {
            override suspend fun authorize(accountName: String): DriveAccess { authorizations++; return DriveAccess.Granted(accountName, "token-a") }
            override suspend fun invalidate(accountName: String, rejectedToken: String) {
                assertEquals("a@example.test", accountName); assertEquals("token-a", rejectedToken); f.switchAccount()
            }
        }
        f.uploadAction = { throw DriveException(DriveFailure.AUTH_REQUIRED) }
        assertEquals(DriveRunResult.OBSOLETE, f.engine(provider).run(f.destination.configurationId))
        assertEquals(1, authorizations); assertEquals(1, f.uploaded.size); assertTrue(f.trimmed.isEmpty())
        assertNotEquals(DriveSyncPhase.UPLOADED, f.events.state.value.phase)
    }
    @Test fun listDeadlineDuringHistoryTrimReturnsRetryWithDurableCiphertext() = runBlocking {
        val f = Fixture(); val pending = f.pending()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeadersDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS).setBody("""{"files":[]}"""))
            val client = DriveRestClient(okhttp3.OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/"), listDeadlineMillis = 100)
            val remote = object : DriveRemote {
                override suspend fun generateFileId(token: String) = f.transport.generateFileId(token)
                override suspend fun upload(token: String, snapshot: PendingSnapshot) = f.transport.upload(token, snapshot)
                override suspend fun trimHistory(token: String, sourceId: UUID) = client.trimHistory(token, sourceId)
            }
            assertEquals(DriveRunResult.RETRY, f.engine(remote = remote).run(f.destination.configurationId))
            assertEquals(pending.snapshotId, f.queue.latest()!!.snapshotId)
            assertEquals("remote-1", f.queue.latest()!!.remoteFileId)
            assertEquals(DriveSyncPhase.RETRYING, f.events.state.value.phase)
        }
    }

}
