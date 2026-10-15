package dev.openauth.app.drive

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DriveListDeadlineTest {
    @Test fun ownPaginationDeadlineIsRetryableInsteadOfCancellation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"files":[],"nextPageToken":"second"}"""))
            server.enqueue(MockResponse().setHeadersDelay(500, TimeUnit.MILLISECONDS).setBody("""{"files":[]}"""))
            val client = DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/"), listDeadlineMillis = 150)
            try { client.listSnapshots("token"); fail("Slow list accepted") }
            catch (failure: DriveException) { assertEquals(DriveFailure.RETRYABLE, failure.failure); assertNull(failure.cause) }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun parentCancellationIsNeverConvertedIntoRetryableFailure() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeadersDelay(500, TimeUnit.MILLISECONDS).setBody("""{"files":[]}"""))
            val client = DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/"), listDeadlineMillis = 2000)
            val job = async { client.listSnapshots("token") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            job.cancel()
            try { job.await(); fail("Parent cancellation lost") } catch (_: CancellationException) { }
        }
    }
    @Test fun shorterParentDeadlineRetainsItsTimeoutCancellation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeadersDelay(500, TimeUnit.MILLISECONDS).setBody("""{"files":[]}"""))
            val client = DriveRestClient(OkHttpClient(), server.url("/drive/v3/"), server.url("/upload/drive/v3/"), listDeadlineMillis = 2000)
            try { withTimeout(100) { client.listSnapshots("token") }; fail("Parent deadline ignored") }
            catch (_: TimeoutCancellationException) { }
        }
    }

}
