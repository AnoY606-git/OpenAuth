package dev.openauth.app.drive

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DriveRestClientTest {
    private val server = MockWebServer()
    private val token = "synthetic-access-token"
    private val source = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val snapshot = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val otherSource = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private lateinit var client: DriveRestClient
    private val files = mutableListOf<File>()

    @Before fun setUp() {
        server.start()
        client = DriveRestClient(
            OkHttpClient.Builder().callTimeout(600, TimeUnit.MILLISECONDS).build(),
            server.url("/drive/v3/"), server.url("/upload/drive/v3/"),
        )
    }
    @After fun tearDown() { server.shutdown(); files.forEach { it.delete() } }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun file(bytes: ByteArray = byteArrayOf(0, -1, 42, 7)): File =
        File.createTempFile("opaque-", ".oab").also { it.writeBytes(bytes); files += it }
    private fun metadata(id: String = "remote-one", snap: UUID = snapshot, src: UUID = source,
                         revision: Long = 7, created: String = "2026-10-15T10:00:00Z", size: Long = 4) =
        """{"id":"$id","name":"$snap.oab","mimeType":"application/octet-stream","parents":["appDataFolder"],"spaces":["appDataFolder"],"createdTime":"$created","size":"$size","appProperties":{"openAuthFormat":"1","snapshotId":"$snap","sourceId":"$src","revision":"$revision"}}"""
    private fun listing(vararg entries: String, next: String? = null) =
        """{"files":[${entries.joinToString(",")}]${next?.let { ",\"nextPageToken\":\"$it\"" } ?: ""}}"""
    private suspend fun failure(expected: DriveFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $expected") } catch (e: DriveException) {
            assertEquals(expected, e.failure)
            assertFalse(e.toString().contains(token))
            assertFalse(e.toString().contains("sensitive-server-body"))
            assertNull(e.cause)
            return
        }
        error("unreachable")
    }

    @Test fun listUsesAppdataAuthenticatedPaginationAndIgnoresUnknownFiles() = runBlocking {
        val older = UUID.fromString("44444444-4444-4444-8444-444444444444")
        server.enqueue(json(listing(metadata("old", older, created = "2026-10-14T10:00:00Z"), next = "page2")))
        server.enqueue(json(listing("""{"id":"foreign","name":"notes.txt"}""", metadata())))
        val result = client.listSnapshots(token)
        assertEquals(listOf("remote-one", "old"), result.map { it.fileId })
        assertEquals(1792058400L, result.first().createdAtEpochSeconds)
        val first = server.takeRequest()
        assertEquals("Bearer $token", first.getHeader("Authorization"))
        assertEquals("appDataFolder", first.requestUrl!!.queryParameter("spaces"))
        assertTrue(first.requestUrl!!.queryParameter("q")!!.contains("trashed = false"))
        assertEquals("page2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }

    @Test fun generatedIdIsExplicitlyRequestedForAppdata() = runBlocking {
        server.enqueue(json("""{"ids":["remote-one"],"space":"appDataFolder"}"""))
        assertEquals("remote-one", client.generateFileId(token))
        val request = server.takeRequest()
        assertEquals("/drive/v3/files/generateIds", request.requestUrl!!.encodedPath)
        assertEquals("appDataFolder", request.requestUrl!!.queryParameter("space"))
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    @Test fun uploadSendsOnlyOpaqueBytesAndAppdataMetadataWithPregeneratedId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        server.enqueue(json(metadata()))
        val payload = file()
        assertEquals("remote-one", client.upload(token, payload, snapshot, source, 7, "remote-one").fileId)
        server.takeRequest()
        server.takeRequest()
        val upload = server.takeRequest()
        assertEquals("POST", upload.method)
        assertEquals("/upload/drive/v3/files", upload.requestUrl!!.encodedPath)
        assertEquals("multipart", upload.requestUrl!!.queryParameter("uploadType"))
        assertTrue(upload.getHeader("Content-Type")!!.startsWith("multipart/related"))
        assertEquals("Bearer $token", upload.getHeader("Authorization"))
        val body = upload.body.readByteArray()
        val text = body.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("\"id\":\"remote-one\""))
        assertTrue(text.contains("\"parents\":[\"appDataFolder\"]"))
        assertTrue(text.contains("\"openAuthFormat\":\"1\""))
        assertTrue(text.contains("\"name\":\"$snapshot.oab\""))
        assertTrue(text.contains("\"sourceId\":\"$source\""))
        assertTrue(text.contains("\"revision\":\"7\""))
        assertTrue(text.contains(String(payload.readBytes(), Charsets.ISO_8859_1)))
        assertFalse(text.contains(token))
    }

    @Test fun alreadyUploadedSnapshotIsReconciledWithoutCreatingAnother() = runBlocking {
        server.enqueue(json(metadata()))
        assertEquals("remote-one", client.upload(token, file(), snapshot, source, 7, "remote-one").fileId)
        assertEquals(1, server.requestCount)
    }

    @Test fun lostCreateResponseReconcilesAcceptedUploadWithoutAnotherPost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json(metadata()))
        assertEquals("remote-one", client.upload(token, file(), snapshot, source, 7, "remote-one").fileId)
        val requests = (1..4).map { server.takeRequest() }
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun acceptedCreateWithCallTimeoutReconcilesKnownFileWithoutAnotherPost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        // Сервер принял POST, но полное тело ответа придёт позже callTimeout клиента.
        server.enqueue(json(metadata()).setBodyDelay(1500, TimeUnit.MILLISECONDS))
        server.enqueue(json(metadata()))
        assertEquals("remote-one", client.upload(token, file(), snapshot, source, 7, "remote-one").fileId)
        val requests = (1..4).map { server.takeRequest() }
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals("/drive/v3/files/remote-one", requests.last().requestUrl!!.encodedPath)
    }

    @Test fun unknownUploadCanBeRetriedByNewClientWithDurablyStoredId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        val payload = file()
        failure(DriveFailure.AMBIGUOUS_UPLOAD) { client.upload(token, payload, snapshot, source, 7, "remote-one") }
        val restartedClient = DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(json(metadata()))
        assertEquals("remote-one", restartedClient.upload(token, payload, snapshot, source, 7, "remote-one").fileId)
        val requests = (1..9).map { server.takeRequest() }
        assertEquals(0, requests.count { it.requestUrl!!.encodedPath.endsWith("generateIds") })
        assertEquals(2, requests.count { it.method == "POST" })
        requests.filter { it.method == "POST" }.forEach {
            assertTrue(it.body.readUtf8().contains("\"id\":\"remote-one\""))
        }
    }

    @Test fun conflictingExistingRemoteIdIsNeverOverwritten() = runBlocking {
        server.enqueue(json(metadata(src = otherSource)))
        failure(DriveFailure.CONFLICT) { client.upload(token, file(), snapshot, source, 7, "remote-one") }
        assertEquals(1, server.requestCount)
    }

    @Test fun conflictAfterCreateRequiresMatchingSnapshotMetadata() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(json(metadata(revision = 8)))
        failure(DriveFailure.CONFLICT) { client.upload(token, file(), snapshot, source, 7, "remote-one") }
        assertEquals(4, server.requestCount)
    }

    @Test fun largeCiphertextUsesResumableMetadataThenAuthenticatedPut() = runBlocking {
        val payload = file(ByteArray(6 * 1024 * 1024) { (it % 251).toByte() })
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        server.enqueue(MockResponse().setHeader("Location", server.url("/upload/drive/v3/files?uploadType=resumable&upload_id=synthetic")))
        server.enqueue(json(metadata(size = payload.length())))
        assertEquals("remote-one", client.upload(token, payload, snapshot, source, 7, "remote-one").fileId)
        server.takeRequest(); server.takeRequest()
        val init = server.takeRequest()
        assertEquals("POST", init.method)
        assertEquals("resumable", init.requestUrl!!.queryParameter("uploadType"))
        assertEquals("application/octet-stream", init.getHeader("X-Upload-Content-Type"))
        assertEquals("6291456", init.getHeader("X-Upload-Content-Length"))
        val metadata = init.body.readUtf8()
        assertTrue(metadata.contains("\"id\":\"remote-one\""))
        assertTrue(metadata.contains("\"parents\":[\"appDataFolder\"]"))
        assertTrue(metadata.contains("\"snapshotId\":\"$snapshot\""))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("synthetic", put.requestUrl!!.queryParameter("upload_id"))
        assertEquals("Bearer $token", put.getHeader("Authorization"))
        assertEquals("application/octet-stream", put.getHeader("Content-Type"))
        assertArrayEquals(payload.readBytes(), put.body.readByteArray())
    }

    @Test fun resumableSessionCannotRedirectTokenOrCiphertextToAnotherOriginOrPath() = runBlocking {
        val payload = file(ByteArray(5_000_001))
        for (location in listOf("https://example.com/upload/drive/v3/files?uploadType=resumable&upload_id=secret", server.url("/unrelated?uploadType=resumable&upload_id=secret").toString())) {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(json(listing()))
            server.enqueue(MockResponse().setHeader("Location", location))
            val before = server.requestCount
            failure(DriveFailure.INVALID_RESPONSE) { client.upload(token, payload, snapshot, source, 7, "remote-one") }
            assertEquals(before + 3, server.requestCount)
            server.takeRequest(); server.takeRequest()
            val init = server.takeRequest()
            assertEquals("resumable", init.requestUrl!!.queryParameter("uploadType"))
            assertEquals("5000001", init.getHeader("X-Upload-Content-Length"))
        }
    }

    @Test fun acceptedResumablePutWithLostResponseReconcilesKnownFileId() = runBlocking {
        val payload = file(ByteArray(5_000_001))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        server.enqueue(MockResponse().setHeader("Location", server.url("/upload/drive/v3/files?uploadType=resumable&upload_id=synthetic")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json(metadata(size = payload.length())))
        assertEquals("remote-one", client.upload(token, payload, snapshot, source, 7, "remote-one").fileId)
        val requests = (1..5).map { server.takeRequest() }
        assertEquals(1, requests.count { it.method == "PUT" })
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun incompleteResumablePutIsNotPublishedAsSuccess() = runBlocking {
        val payload = file(ByteArray(5_000_001))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        server.enqueue(MockResponse().setHeader("Location", server.url("/upload/drive/v3/files?uploadType=resumable&upload_id=synthetic")))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-1023"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(listing()))
        failure(DriveFailure.AMBIGUOUS_UPLOAD) { client.upload(token, payload, snapshot, source, 7, "remote-one") }
    }

    @Test fun downloadAuthenticatesAndPreservesOpaqueBytes() = runBlocking {
        val bytes = byteArrayOf(0, -1, 1, 2)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        assertArrayEquals(bytes, client.download(token, "remote-one"))
        val request = server.takeRequest()
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals("media", request.requestUrl!!.queryParameter("alt"))
        assertEquals("/drive/v3/files/remote-one", request.requestUrl!!.encodedPath)
    }

    @Test fun chunkedDownloadCannotExceedSixteenMiB() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(ByteArray(16 * 1024 * 1024 + 1)), 8192))
        failure(DriveFailure.LIMIT_EXCEEDED) { client.download(token, "remote-one") }
    }

    @Test fun excessiveContentLengthIsRejectedBeforeReadingBody() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Length", 16L * 1024 * 1024 + 1).setSocketPolicy(SocketPolicy.KEEP_OPEN))
        failure(DriveFailure.LIMIT_EXCEEDED) { client.download(token, "remote-one") }
    }

    @Test fun oversizedUploadDoesNotTouchNetwork() = runBlocking {
        val payload = file()
        RandomAccessFile(payload, "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
        failure(DriveFailure.LIMIT_EXCEEDED) { client.upload(token, payload, snapshot, source, 7, "remote-one") }
        assertEquals(0, server.requestCount)
    }

    @Test fun typedHttpFailuresNeverExposeTokenOrResponseBody() = runBlocking {
        val cases = listOf(
            Triple(401, "authError", DriveFailure.AUTH_REQUIRED),
            Triple(403, "storageQuotaExceeded", DriveFailure.QUOTA),
            Triple(403, "insufficientFilePermissions", DriveFailure.PERMISSION_DENIED),
            Triple(403, "userRateLimitExceeded", DriveFailure.RATE_LIMITED),
            Triple(429, "rateLimitExceeded", DriveFailure.RATE_LIMITED),
            Triple(503, "backendError", DriveFailure.RETRYABLE),
        )
        for ((status, reason, expected) in cases) {
            server.enqueue(json("""{"error":{"message":"sensitive-server-body $token","errors":[{"reason":"$reason"}]}}""").setResponseCode(status))
            failure(expected) { client.listSnapshots(token) }
        }
    }

    @Test fun malformedListIsNotReportedAsAnEmptySuccess() = runBlocking {
        server.enqueue(json("{broken"))
        failure(DriveFailure.INVALID_RESPONSE) { client.listSnapshots(token) }
    }

    @Test fun repeatingPaginationTokenStopsWithTypedFailure() = runBlocking {
        repeat(2) { server.enqueue(json(listing(next = "same-page"))) }
        failure(DriveFailure.INVALID_RESPONSE) { client.listSnapshots(token) }
        assertEquals(2, server.requestCount)
    }

    @Test fun oversizedMetadataResponseIsBounded() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody(" ".repeat(1024 * 1024 + 1), 8192))
        failure(DriveFailure.LIMIT_EXCEEDED) { client.listSnapshots(token) }
    }

    @Test fun duplicateSnapshotIdsAreNotPublishedAsNewerBackups() = runBlocking {
        server.enqueue(json(listing(metadata("original"), metadata("duplicate", created = "2026-10-15T11:00:00Z"))))
        assertEquals(listOf("original"), client.listSnapshots(token).map { it.fileId })
    }

    @Test fun historyTrimsOnlyRequestedSourceAndKeepsTenNewest() = runBlocking {
        val own = (1..12).map { index ->
            metadata("own-$index", UUID(0, index.toLong()), revision = index.toLong(), created = "2026-10-15T10:00:${index.toString().padStart(2, '0')}Z")
        }
        server.enqueue(json(listing(*(own + metadata("foreign-source", src = otherSource)).toTypedArray())))
        repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
        client.trimHistory(token, source)
        server.takeRequest()
        assertEquals(setOf("/drive/v3/files/own-1", "/drive/v3/files/own-2"),
            (1..2).map { server.takeRequest().requestUrl!!.encodedPath }.toSet())
    }

    @Test fun historyNeverDeletesTheLastSnapshotEvenWhenKeepIsZero() = runBlocking {
        server.enqueue(json(listing(metadata())))
        client.trimHistory(token, source, 0)
        assertEquals(1, server.requestCount)
    }

    @Test fun blankTokenCannotReachNetworkOrAnExceptionMessage() = runBlocking {
        failure(DriveFailure.AUTH_REQUIRED) { client.listSnapshots("") }
        assertEquals(0, server.requestCount)
    }

    @Test fun externalOrCleartextProductionEndpointsAreRejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DriveRestClient(OkHttpClient(), "https://example.com/drive/v3/".toHttpUrl())
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DriveRestClient(OkHttpClient(), "http://www.googleapis.com/drive/v3/".toHttpUrl())
        }
    }

    @Test fun productionAndLoopbackEndpointsCannotBeMixed() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DriveRestClient(OkHttpClient(), "https://www.googleapis.com/drive/v3/".toHttpUrl(), server.url("/upload/drive/v3/"))
        }
    }

    @Test fun untrustedRemoteFileIdCannotInjectAQueryOrPath() = runBlocking {
        failure(DriveFailure.INVALID_REQUEST) { client.download(token, "../files?alt=json") }
        assertEquals(0, server.requestCount)
    }

    @Test fun malformedRecognizedSnapshotIsRejectedRatherThanHidden() = runBlocking {
        server.enqueue(json(listing(metadata().replace("\"revision\":\"7\"", "\"revision\":\"-1\""))))
        failure(DriveFailure.INVALID_RESPONSE) { client.listSnapshots(token) }
    }

    @Test fun coroutineCancellationCancelsNetworkInsteadOfReturningDriveFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = async { client.download(token, "remote-one") }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        job.cancel()
        try { job.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
    }
}
