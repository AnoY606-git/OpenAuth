package dev.openauth.app.drive

import dev.openauth.app.backup.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveRestoreBrowserTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Bytes : AtomicBytes { var value: ByteArray? = null; override fun read() = value; override fun writeAtomically(bytes: ByteArray) { value = bytes.copyOf() } }
    private val protector = object : MetadataProtector { override fun seal(bytes: ByteArray) = bytes.copyOf(); override fun open(bytes: ByteArray) = bytes.copyOf() }
    @Test fun accountSwitchDuringDownloadDiscardsCiphertext() = runBlocking {
        val queue = EncryptedSnapshotQueue(temporary.newFolder(), protector, Bytes())
        val store = DriveDestinationStore(Bytes(), protector)
        val destination = DriveDestination("a@example.test", UUID.randomUUID(), queue.sourceId())
        store.write(destination); queue.setActiveConfiguration(destination.configurationId)
        val snapshot = DriveSnapshot("remote-one", UUID.randomUUID(), UUID.randomUUID(), 100, 1, 176)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                queue.setActiveConfiguration(null); store.write(destination.copy(accountName = "b@example.test", configurationId = UUID.randomUUID()))
                return MockResponse().setBody("opaque ciphertext")
            } }
            server.start()
            val browser = DriveRestoreBrowser(queue, store, DriveTokenProvider { DriveAccess.Granted(it, "token-a") }, DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/")))
            try { browser.download(DriveHistory(destination, listOf(snapshot)), snapshot); fail("Old-account ciphertext published") }
            catch (failure: DriveConnectionException) { assertEquals(DriveConnectionFailure.DESTINATION_CHANGED, failure.failure) }
            assertEquals("Bearer token-a", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun unlistedSnapshotNeverTriggersDownload() = runBlocking {
        val queue = EncryptedSnapshotQueue(temporary.newFolder(), protector, Bytes())
        val store = DriveDestinationStore(Bytes(), protector)
        val destination = DriveDestination("a@example.test", UUID.randomUUID(), queue.sourceId())
        store.write(destination); queue.setActiveConfiguration(destination.configurationId)
        MockWebServer().use { server ->
            server.start()
            val browser = DriveRestoreBrowser(queue, store, DriveTokenProvider { DriveAccess.Granted(it, "token-a") }, DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/")))
            try { browser.download(DriveHistory(destination, emptyList()), DriveSnapshot("other", UUID.randomUUID(), destination.sourceId, 100, 1, 176)); fail("Unlisted download accepted") }
            catch (failure: DriveException) { assertEquals(DriveFailure.INVALID_REQUEST, failure.failure) }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun confirmedDeletionDisablesUploadsBeforeAnyNetworkAndOnlyDeletesOwnSource() = runBlocking {
        val queue = EncryptedSnapshotQueue(temporary.newFolder(), protector, Bytes())
        val store = DriveDestinationStore(Bytes(), protector)
        val destination = DriveDestination("a@example.test", UUID.randomUUID(), queue.sourceId())
        store.write(destination); queue.setActiveConfiguration(destination.configurationId)
        queue.enqueue(ByteArray(176), 1, "generation", 100)
        var cancelled = false
        val snapshot = UUID.randomUUID()
        val metadata = """{"id":"remote-one","name":"$snapshot.oab","mimeType":"application/octet-stream","parents":["appDataFolder"],"spaces":["appDataFolder"],"createdTime":"2026-10-15T10:00:00Z","size":"176","appProperties":{"openAuthFormat":"1","snapshotId":"$snapshot","sourceId":"${destination.sourceId}","revision":"1"}}"""
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"files":[$metadata]}"""))
            server.enqueue(MockResponse().setBody(metadata)); server.enqueue(MockResponse().setResponseCode(204))
            val browser = DriveRestoreBrowser(queue, store, DriveTokenProvider {
                assertTrue(cancelled); assertNull(queue.activeConfigurationId()); assertNull(store.read())
                DriveAccess.Granted(it, "token-a")
            }, DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/")), cancelUploads = { cancelled = true })
            browser.deleteHistory(destination)
            assertNull(queue.activeConfigurationId()); assertNull(store.read())
            val listing = server.takeRequest()
            assertTrue(listing.requestUrl!!.queryParameter("q")!!.contains(destination.sourceId.toString()))
            assertEquals("GET", server.takeRequest().method); assertEquals("DELETE", server.takeRequest().method)
        }
    }

    @Test fun rejectedBrowserTokenIsInvalidatedBeforeExplicitRetry() = runBlocking {
        val queue = EncryptedSnapshotQueue(temporary.newFolder(), protector, Bytes())
        val store = DriveDestinationStore(Bytes(), protector)
        val destination = DriveDestination("a@example.test", UUID.randomUUID(), queue.sourceId())
        store.write(destination); queue.setActiveConfiguration(destination.configurationId)
        var token = "bad-token"
        val provider = object : DriveTokenProvider {
            override suspend fun authorize(accountName: String) = DriveAccess.Granted(accountName, token)
            override suspend fun invalidate(accountName: String, rejectedToken: String) {
                assertEquals(destination.accountName, accountName); assertEquals("bad-token", rejectedToken); token = "fresh-token"
            }
        }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setBody("""{"files":[]}"""))
            val browser = DriveRestoreBrowser(queue, store, provider, DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/")))
            try { browser.list(); fail("Rejected token accepted") } catch (failure: DriveException) { assertEquals(DriveFailure.AUTH_REQUIRED, failure.failure) }
            assertTrue(browser.list().snapshots.isEmpty())
            assertEquals("Bearer bad-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
        }
    }

}
