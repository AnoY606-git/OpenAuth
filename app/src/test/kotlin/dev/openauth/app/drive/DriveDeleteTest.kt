package dev.openauth.app.drive

import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DriveDeleteTest {
    private val source = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val snapshot = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private fun metadata(sourceId: UUID) = """{"id":"remote-one","name":"$snapshot.oab","mimeType":"application/octet-stream","parents":["appDataFolder"],"spaces":["appDataFolder"],"createdTime":"2026-10-15T10:00:00Z","size":"176","appProperties":{"openAuthFormat":"1","snapshotId":"$snapshot","sourceId":"$sourceId","revision":"1"}}"""
    @Test fun deletingOwnSourceChecksMetadataBeforeDelete() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/"))
            server.enqueue(MockResponse().setBody(metadata(source))); server.enqueue(MockResponse().setResponseCode(204))
            client.deleteSnapshot("test-token", "remote-one", source)
            assertEquals("GET", server.takeRequest().method)
            val deletion = server.takeRequest(); assertEquals("DELETE", deletion.method); assertEquals("Bearer test-token", deletion.getHeader("Authorization"))
        }
    }
    @Test fun deletingOtherPhoneIsRejectedBeforeDelete() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/"))
            server.enqueue(MockResponse().setBody(metadata(UUID.randomUUID())))
            try { client.deleteSnapshot("test-token", "remote-one", source); fail("Foreign source deleted") }
            catch (e: DriveException) { assertEquals(DriveFailure.CONFLICT, e.failure) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun alreadyMissingSnapshotIsIdempotent() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/"))
            server.enqueue(MockResponse().setResponseCode(404)); client.deleteSnapshot("test-token", "remote-one", source)
            assertEquals(1, server.requestCount)
        }
    }
}
