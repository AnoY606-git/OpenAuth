package dev.openauth.app.drive

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * REST-граница Drive: принимает только уже зашифрованные файлы и токен одного запроса.
 * Авторизация и привязка очереди к аккаунту принадлежат вызывающему коду.
 */
class DriveRestClient(
    http: OkHttpClient = secureDefault(),
    private val baseUrl: HttpUrl = GOOGLE_ENDPOINT,
    private val uploadBaseUrl: HttpUrl = GOOGLE_UPLOAD_ENDPOINT,
    private val listDeadlineMillis: Long = 120_000,
) {
    private val http = http.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(boundedTimeout(http.callTimeoutMillis, 45_000), TimeUnit.MILLISECONDS)
        .connectTimeout(boundedTimeout(http.connectTimeoutMillis, 15_000), TimeUnit.MILLISECONDS)
        .readTimeout(boundedTimeout(http.readTimeoutMillis, 30_000), TimeUnit.MILLISECONDS)
        .writeTimeout(boundedTimeout(http.writeTimeoutMillis, 30_000), TimeUnit.MILLISECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(listDeadlineMillis in 1..120_000) { "Unsupported Drive list deadline" }
        require(validEndpoint(baseUrl) && validEndpoint(uploadBaseUrl)) { "Unsupported Drive endpoint" }
        require(baseUrl.scheme == uploadBaseUrl.scheme && baseUrl.host == uploadBaseUrl.host && baseUrl.port == uploadBaseUrl.port) {
            "Inconsistent Drive endpoints"
        }
        require(baseUrl.encodedPath == "/drive/v3/" && uploadBaseUrl.encodedPath == "/upload/drive/v3/") {
            "Unsupported Drive endpoint path"
        }
    }

    /** ID необходимо атомарно сохранить в зашифрованной очереди ДО вызова upload. */
    suspend fun generateFileId(accessToken: String): String {
        val url = baseUrl.newBuilder().addPathSegments("files/generateIds")
            .addQueryParameter("count", "1").addQueryParameter("space", "appDataFolder")
            .addQueryParameter("type", "files").build()
        return request(authenticated(accessToken, url).build()) { response ->
            requireSuccess(response)
            val ids = decode(response)["ids"] as? JsonArray ?: invalidResponse()
            if (ids.size != 1) invalidResponse()
            primitive(ids.single()).also { if (!validFileId(it)) invalidResponse() }
        }
    }

    suspend fun listSnapshots(accessToken: String, sourceId: UUID? = null): List<DriveSnapshot> =
        withTimeoutOrNull(listDeadlineMillis) {
            val snapshots = mutableListOf<DriveSnapshot>()
            val seenPages = mutableSetOf<String>()
            var page: String? = null
            var pages = 0
            var seenFiles = 0
            do {
                if (++pages > MAX_PAGES) throw DriveException(DriveFailure.LIMIT_EXCEEDED)
                val query = buildString {
                    append("trashed = false and appProperties has { key='openAuthFormat' and value='1' }")
                    if (sourceId != null) append(" and appProperties has { key='sourceId' and value='$sourceId' }")
                }
                val url = baseUrl.newBuilder().addPathSegment("files")
                    .addQueryParameter("spaces", "appDataFolder").addQueryParameter("q", query)
                    .addQueryParameter("pageSize", "100")
                    .addQueryParameter("fields", "nextPageToken,incompleteSearch,files($METADATA_FIELDS)")
                    .apply { page?.let { addQueryParameter("pageToken", it) } }.build()
                val document = request(authenticated(accessToken, url).build()) {
                    requireSuccess(it); decode(it)
                }
                if ((document["incompleteSearch"] as? JsonPrimitive)?.booleanOrNull == true) invalidResponse()
                val entries = document["files"]?.let { it as? JsonArray ?: invalidResponse() } ?: JsonArray(emptyList())
                seenFiles += entries.size
                if (seenFiles > MAX_FILES) throw DriveException(DriveFailure.LIMIT_EXCEEDED)
                for (entry in entries) {
                    val parsed = snapshot(entry as? JsonObject ?: invalidResponse()) ?: continue
                    if (sourceId == null || parsed.sourceId == sourceId) snapshots += parsed
                }
                page = document["nextPageToken"]?.let { primitive(it) }
                if (page != null && (page.isBlank() || page.length > 4096 || !seenPages.add(page))) invalidResponse()
            } while (page != null)
            // При старых дубликатах сохраняем первоначальный снимок, не продвигаем его как новый.
            snapshots.sortedWith(compareBy<DriveSnapshot> { it.createdAtEpochSeconds }.thenBy { it.fileId })
                .distinctBy { it.sourceId to it.snapshotId }
                .sortedWith(compareByDescending<DriveSnapshot> { it.createdAtEpochSeconds }
                    .thenByDescending { it.revision }.thenBy { it.fileId })
        } ?: throw DriveException(DriveFailure.RETRYABLE)

    /**
     * remoteFileId выдан generateFileId и сохранён вызывающей очередью вместе с неизменяемым
     * ciphertext и назначением аккаунта. Все повторы используют ТОТ ЖЕ ID, включая перезапуск.
     * AMBIGUOUS_UPLOAD не означает отсутствие файла: очередь сохраняет задание для сверки.
     */
    suspend fun upload(
        accessToken: String, ciphertext: File, snapshotId: UUID, sourceId: UUID,
        revision: Long, remoteFileId: String,
    ): DriveSnapshot {
        if (!validFileId(remoteFileId) || revision < 0) throw DriveException(DriveFailure.INVALID_REQUEST)
        // Ограниченное чтение фиксирует неизменяемое тело до первого сетевого запроса.
        val bytes = withContext(Dispatchers.IO) {
            if (!ciphertext.isFile) throw DriveException(DriveFailure.INVALID_REQUEST)
            if (ciphertext.length() > MAX_CIPHERTEXT_BYTES) throw DriveException(DriveFailure.LIMIT_EXCEEDED)
            try {
                ciphertext.inputStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size().toLong() + count > MAX_CIPHERTEXT_BYTES) throw DriveException(DriveFailure.LIMIT_EXCEEDED)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (_: IOException) { throw DriveException(DriveFailure.NETWORK) }
        }
        fun verified(value: DriveSnapshot): DriveSnapshot {
            if (value.snapshotId != snapshotId || value.sourceId != sourceId ||
                value.revision != revision || value.sizeBytes != bytes.size.toLong()) {
                throw DriveException(DriveFailure.CONFLICT)
            }
            return value
        }
        suspend fun reconcile(): DriveSnapshot? {
            metadata(accessToken, remoteFileId)?.let { return verified(it) }
            return listSnapshots(accessToken, sourceId).firstOrNull { it.snapshotId == snapshotId }?.let(::verified)
        }
        reconcile()?.let { return it }
        val properties = buildJsonObject {
            put("openAuthFormat", "1"); put("snapshotId", snapshotId.toString())
            put("sourceId", sourceId.toString()); put("revision", revision.toString())
        }
        val metadata = buildJsonObject {
            put("id", remoteFileId); put("name", "$snapshotId.oab"); put("mimeType", MIME)
            putJsonArray("parents") { add(JsonPrimitive("appDataFolder")) }
            put("appProperties", properties)
        }
        fun createdSnapshot(response: Response): DriveSnapshot {
            requireSuccess(response)
            val result = snapshot(decode(response)) ?: invalidResponse()
            if (result.fileId != remoteFileId) throw DriveException(DriveFailure.CONFLICT)
            return verified(result)
        }
        try {
            if (bytes.size <= MAX_MULTIPART_BYTES) {
                val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                    .addPart(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    .addPart(bytes.toRequestBody(MIME.toMediaType())).build()
                val url = uploadBaseUrl.newBuilder().addPathSegment("files")
                    .addQueryParameter("uploadType", "multipart").addQueryParameter("fields", METADATA_FIELDS).build()
                return request(authenticated(accessToken, url).post(body).build(), ::createdSnapshot)
            }
            val url = uploadBaseUrl.newBuilder().addPathSegment("files")
                .addQueryParameter("uploadType", "resumable").addQueryParameter("fields", METADATA_FIELDS).build()
            val initRequest = authenticated(accessToken, url)
                .header("X-Upload-Content-Type", MIME).header("X-Upload-Content-Length", bytes.size.toString())
                .post(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
            val session = request(initRequest) {
                requireSuccess(it)
                val location = it.header("Location") ?: invalidResponse()
                if (location.length > 8192) invalidResponse()
                val parsed = location.toHttpUrlOrNull() ?: invalidResponse()
                // Location — capability URL; он остаётся только в памяти запроса, не попадает в ошибки.
                if (parsed.scheme != uploadBaseUrl.scheme || parsed.host != uploadBaseUrl.host ||
                    parsed.port != uploadBaseUrl.port || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
                    parsed.fragment != null || parsed.encodedPath != uploadBaseUrl.encodedPath + "files" ||
                    parsed.queryParameter("uploadType") != "resumable" || parsed.queryParameter("upload_id").isNullOrBlank()) {
                    invalidResponse()
                }
                parsed
            }
            return request(authenticated(accessToken, session).put(bytes.toRequestBody(MIME.toMediaType())).build()) {
                // Частичный PUT не означает сохранённый снимок. Следующий вызов начнёт новую
                // сессию с тем же долговечным file ID, предварительно сверив результат.
                if (it.code == 308) throw DriveException(DriveFailure.RETRYABLE)
                createdSnapshot(it)
            }
        } catch (e: DriveException) {
            if (e.failure !in setOf(DriveFailure.NETWORK, DriveFailure.RETRYABLE, DriveFailure.CONFLICT)) throw e
            reconcile()?.let { return it }
            throw DriveException(DriveFailure.AMBIGUOUS_UPLOAD)
        }
    }

    suspend fun download(accessToken: String, fileId: String): ByteArray {
        checkFileId(fileId)
        val url = baseUrl.newBuilder().addPathSegment("files").addPathSegment(fileId)
            .addQueryParameter("alt", "media").build()
        return request(authenticated(accessToken, url).build()) { requireSuccess(it); boundedBody(it, MAX_CIPHERTEXT_BYTES) }
    }

    /** Вызывается очередью только после подтверждения upload; хотя бы один снимок остаётся. */
    suspend fun trimHistory(accessToken: String, sourceId: UUID, keep: Int = 10) {
        for (snapshot in listSnapshots(accessToken, sourceId).drop(keep.coerceAtLeast(1))) {
            val url = baseUrl.newBuilder().addPathSegment("files").addPathSegment(snapshot.fileId).build()
            request(authenticated(accessToken, url).delete().build()) {
                if (it.code != 404) requireSuccess(it)
                Unit
            }
        }
    }

    /** Отдельное явное удаление: сначала проверяем принадлежность источнику. */
    suspend fun deleteSnapshot(accessToken: String, fileId: String, sourceId: UUID) {
        checkFileId(fileId)
        val snapshot = metadata(accessToken, fileId) ?: return
        if (snapshot.sourceId != sourceId) throw DriveException(DriveFailure.CONFLICT)
        val url = baseUrl.newBuilder().addPathSegment("files").addPathSegment(fileId).build()
        request(authenticated(accessToken, url).delete().build()) {
            if (it.code != 404) requireSuccess(it)
            Unit
        }
    }

    private suspend fun metadata(accessToken: String, id: String): DriveSnapshot? {
        val url = baseUrl.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("fields", METADATA_FIELDS).build()
        return request(authenticated(accessToken, url).build()) {
            if (it.code == 404) null else {
                requireSuccess(it)
                val value = snapshot(decode(it)) ?: throw DriveException(DriveFailure.CONFLICT)
                if (value.fileId != id) throw DriveException(DriveFailure.CONFLICT)
                value
            }
        }
    }

    private fun authenticated(token: String, url: HttpUrl): Request.Builder {
        if (token.isBlank()) throw DriveException(DriveFailure.AUTH_REQUIRED)
        if (token.length > 8192 || token.any { it.code !in 33..126 }) throw DriveException(DriveFailure.INVALID_REQUEST)
        return Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
    }

    private suspend fun <T> request(request: Request, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(DriveException(DriveFailure.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use(consume)
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) {
                    val safe = when (e) {
                        is DriveException, is CancellationException -> e
                        is IOException -> DriveException(DriveFailure.NETWORK)
                        else -> DriveException(DriveFailure.INVALID_RESPONSE)
                    }
                    if (continuation.isActive) continuation.resumeWithException(safe)
                }
            }
        })
    }

    private fun boundedBody(response: Response, maximum: Long): ByteArray {
        val body = response.body ?: invalidResponse()
        if (body.contentLength() > maximum) throw DriveException(DriveFailure.LIMIT_EXCEEDED)
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maximum) throw DriveException(DriveFailure.LIMIT_EXCEEDED)
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun decode(response: Response): JsonObject = try {
        json.parseToJsonElement(boundedBody(response, MAX_METADATA_BYTES).toString(Charsets.UTF_8)) as? JsonObject ?: invalidResponse()
    } catch (e: DriveException) { throw e } catch (_: IllegalArgumentException) { invalidResponse() }

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        val reasons = if (response.code == 403) {
            try {
                val document = json.parseToJsonElement(boundedBody(response, 65_536).toString(Charsets.UTF_8)).jsonObject
                (document["error"]?.jsonObject?.get("errors") as? JsonArray)?.mapNotNull {
                    (it as? JsonObject)?.get("reason")?.let { value -> (value as? JsonPrimitive)?.contentOrNull }
                }.orEmpty()
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        throw DriveException(when {
            response.code == 401 -> DriveFailure.AUTH_REQUIRED
            response.code == 429 || reasons.any { it == "rateLimitExceeded" || it == "userRateLimitExceeded" } -> DriveFailure.RATE_LIMITED
            reasons.any { it == "storageQuotaExceeded" || it == "activeItemCreationLimitExceeded" || it == "dailyLimitExceeded" } -> DriveFailure.QUOTA
            response.code == 403 -> DriveFailure.PERMISSION_DENIED
            response.code == 404 -> DriveFailure.NOT_FOUND
            response.code == 409 -> DriveFailure.CONFLICT
            response.code in 500..599 || response.code == 408 -> DriveFailure.RETRYABLE
            else -> DriveFailure.INVALID_RESPONSE
        })
    }

    private fun snapshot(document: JsonObject): DriveSnapshot? {
        val properties = document["appProperties"] as? JsonObject ?: return null
        if ((properties["openAuthFormat"] as? JsonPrimitive)?.contentOrNull != "1") return null
        try {
            val snapshotId = canonicalUuid(primitive(properties["snapshotId"]))
            val sourceId = canonicalUuid(primitive(properties["sourceId"]))
            val id = primitive(document["id"])
            val revision = primitive(properties["revision"]).toLong()
            val size = primitive(document["size"]).toLong()
            val created = Instant.parse(primitive(document["createdTime"])).epochSecond
            val spaces = document["spaces"] as? JsonArray ?: invalidResponse()
            if (!validFileId(id) || revision < 0 || size !in 0..MAX_CIPHERTEXT_BYTES || created < 0 ||
                primitive(document["name"]) != "$snapshotId.oab" || primitive(document["mimeType"]) != MIME ||
                spaces.none { (it as? JsonPrimitive)?.contentOrNull == "appDataFolder" } ||
                (document["trashed"] as? JsonPrimitive)?.booleanOrNull == true) invalidResponse()
            return DriveSnapshot(id, snapshotId, sourceId, created, revision, size)
        } catch (e: DriveException) { throw e } catch (_: Exception) { invalidResponse() }
    }

    private fun canonicalUuid(value: String): UUID = UUID.fromString(value).also { if (it.toString() != value) invalidResponse() }
    private fun primitive(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidResponse()
    private fun checkFileId(id: String) { if (!validFileId(id)) throw DriveException(DriveFailure.INVALID_REQUEST) }
    private fun invalidResponse(): Nothing = throw DriveException(DriveFailure.INVALID_RESPONSE)

    companion object {
        private const val MIME = "application/octet-stream"
        private const val MAX_CIPHERTEXT_BYTES = 16L * 1024 * 1024
        private const val MAX_MULTIPART_BYTES = 5_000_000
        private const val MAX_METADATA_BYTES = 1024L * 1024
        private const val MAX_PAGES = 100
        private const val MAX_FILES = 10_000
        private const val METADATA_FIELDS = "id,name,mimeType,spaces,createdTime,size,appProperties,trashed"
        private val GOOGLE_ENDPOINT = "https://www.googleapis.com/drive/v3/".toHttpUrl()
        private val GOOGLE_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/".toHttpUrl()
        private fun validFileId(id: String) = id.length in 1..256 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
        private fun boundedTimeout(value: Int, maximum: Int) = if (value == 0) maximum.toLong() else minOf(value, maximum).toLong()
        private fun validEndpoint(url: HttpUrl): Boolean =
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
                ((url.scheme == "https" && url.host == "www.googleapis.com" && url.port == 443) ||
                    (url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1"))) &&
                url.encodedPath.endsWith("/")
        private fun secureDefault() = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    }
}
