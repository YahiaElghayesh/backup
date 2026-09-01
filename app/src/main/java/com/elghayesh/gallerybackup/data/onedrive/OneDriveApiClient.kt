package com.elghayesh.gallerybackup.data.onedrive

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

class OneDriveApiException(message: String, val httpCode: Int = -1) : Exception(message)

/**
 * Minimal REST client for the Microsoft Graph endpoints this app needs. Unlike Drive, Graph
 * addresses items by path directly (`/me/drive/root:/a/b/c:`), so there is no need to resolve
 * or cache folder ids the way [com.elghayesh.gallerybackup.data.drive.DriveApiClient] does --
 * every call just names where the file lives.
 */
class OneDriveApiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    /** Creates every missing segment of [folderPath] (e.g. "GalleryBackup/DCIM/Camera"), top-down. */
    suspend fun ensureFolderPath(accessToken: String, folderPath: String) {
        var built = ""
        for (segment in folderPath.split("/").filter { it.isNotBlank() }) {
            createFolderIfMissing(accessToken, built, segment)
            built = if (built.isEmpty()) segment else "$built/$segment"
        }
    }

    private suspend fun createFolderIfMissing(accessToken: String, parentPath: String, name: String) =
        withContext(Dispatchers.IO) {
            val url = "$GRAPH_ROOT${encodedItemSuffix(parentPath)}/children"
            val body = JSONObject().apply {
                put("name", name)
                put("folder", JSONObject())
                put("@microsoft.graph.conflictBehavior", "fail")
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                // 409 Conflict just means the folder is already there -- exactly what we want.
                if (!response.isSuccessful && response.code != 409) {
                    throw OneDriveApiException("Create folder failed: ${response.body?.string().orEmpty()}", response.code)
                }
            }
        }

    /** Uploads the content behind [sourceUri] to [folderPath]/[name], overwriting any existing file there. */
    suspend fun uploadFile(
        accessToken: String,
        folderPath: String,
        contentResolver: ContentResolver,
        sourceUri: Uri,
        totalSize: Long,
        name: String,
    ) = withContext(Dispatchers.IO) {
        val itemPath = if (folderPath.isEmpty()) name else "$folderPath/$name"
        if (totalSize <= SIMPLE_UPLOAD_LIMIT) {
            uploadSmall(accessToken, itemPath, contentResolver, sourceUri, totalSize)
        } else {
            uploadLarge(accessToken, itemPath, contentResolver, sourceUri, totalSize)
        }
    }

    private fun uploadSmall(
        accessToken: String,
        itemPath: String,
        contentResolver: ContentResolver,
        sourceUri: Uri,
        totalSize: Long,
    ) {
        val bytes = ByteArray(totalSize.toInt())
        contentResolver.openInputStream(sourceUri)?.use { readFully(it, bytes) }
            ?: throw OneDriveApiException("Could not open $sourceUri for reading.")
        val url = "$GRAPH_ROOT${encodedItemSuffix(itemPath)}/content"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw OneDriveApiException("Upload failed: ${response.body?.string().orEmpty()}", response.code)
            }
        }
    }

    private suspend fun uploadLarge(
        accessToken: String,
        itemPath: String,
        contentResolver: ContentResolver,
        sourceUri: Uri,
        totalSize: Long,
    ) {
        val uploadUrl = createUploadSession(accessToken, itemPath)
        var offset = 0L
        val input = contentResolver.openInputStream(sourceUri)
            ?: throw OneDriveApiException("Could not open $sourceUri for reading.")
        input.use { stream ->
            while (offset < totalSize) {
                val remaining = totalSize - offset
                val length = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
                val buffer = ByteArray(length)
                readFully(stream, buffer)
                val end = offset + length
                putChunkWithRetry(uploadUrl, buffer, offset, end, totalSize)
                offset = end
            }
        }
    }

    private fun createUploadSession(accessToken: String, itemPath: String): String {
        val url = "$GRAPH_ROOT${encodedItemSuffix(itemPath)}/createUploadSession"
        val body = JSONObject().apply {
            put(
                "item",
                JSONObject().apply { put("@microsoft.graph.conflictBehavior", "replace") },
            )
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OneDriveApiException("Could not start upload session: $responseBody", response.code)
            }
            return JSONObject(responseBody).getString("uploadUrl")
        }
    }

    /** The upload-session URL is itself a one-time signed URL -- no Authorization header on these PUTs. */
    private suspend fun putChunkWithRetry(uploadUrl: String, buffer: ByteArray, offset: Long, end: Long, totalSize: Long) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 4) {
            try {
                val request = Request.Builder()
                    .url(uploadUrl)
                    .header("Content-Range", "bytes $offset-${end - 1}/$totalSize")
                    .put(buffer.toRequestBody(null, 0, buffer.size))
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw OneDriveApiException(
                            "Upload chunk failed: ${response.body?.string().orEmpty()}",
                            response.code,
                        )
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
                attempt++
                delay(1000L * attempt)
            }
        }
        throw lastError ?: OneDriveApiException("Upload chunk failed after retries.")
    }

    /** Builds the `:/a/b/c:` path-addressing suffix Graph expects, with each segment percent-encoded. */
    private fun encodedItemSuffix(path: String): String {
        if (path.isEmpty()) return ""
        val encoded = path.split("/").filter { it.isNotBlank() }.joinToString("/") { Uri.encode(it) }
        return ":/$encoded:"
    }

    /** [InputStream.read] can return short reads; this keeps reading until [buffer] is full or the stream ends. */
    private fun readFully(stream: InputStream, buffer: ByteArray) {
        var filled = 0
        while (filled < buffer.size) {
            val read = stream.read(buffer, filled, buffer.size - filled)
            if (read == -1) {
                throw OneDriveApiException("Media file ended earlier than expected while uploading.")
            }
            filled += read
        }
    }

    companion object {
        private const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0/me/drive/root"
        private const val SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024
        private const val CHUNK_SIZE = 327_680 * 24 // ~7.5MB, a multiple of Graph's required 320KiB
    }
}
