package com.elghayesh.gallerybackup.data.drive

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class DriveFile(val id: String, val name: String)

class DriveApiException(message: String, val httpCode: Int = -1) : Exception(message)

/**
 * Minimal, dependency-light client for the parts of the Drive v3 REST API this app
 * needs: create/find folders, and upload file content via Drive's resumable upload
 * protocol (chunked, so a flaky mobile connection can retry a chunk instead of
 * restarting a multi-gigabyte video from zero).
 *
 * Every request is scoped to files/folders this app itself created, matching the
 * `drive.file` OAuth scope requested in [DriveAuthManager].
 */
class DriveApiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    /** Looks up a direct child of [parentId] by exact name, optionally restricted to folders. */
    suspend fun findChild(accessToken: String, parentId: String, name: String, folderOnly: Boolean): DriveFile? =
        withContext(Dispatchers.IO) {
            val escapedName = name.replace("\\", "\\\\").replace("'", "\\'")
            val mimeClause = if (folderOnly) " and mimeType = 'application/vnd.google-apps.folder'" else ""
            val query = "name = '$escapedName' and '$parentId' in parents and trashed = false$mimeClause"
            val url = "https://www.googleapis.com/drive/v3/files".toHttpUrlBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "files(id,name)")
                .addQueryParameter("spaces", "drive")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw DriveApiException("Lookup failed: $body", response.code)
                }
                val files = JSONObject(body).optJSONArray("files")
                if (files == null || files.length() == 0) {
                    null
                } else {
                    val first = files.getJSONObject(0)
                    DriveFile(first.getString("id"), first.getString("name"))
                }
            }
        }

    suspend fun createFolder(accessToken: String, parentId: String, name: String): DriveFile =
        withContext(Dispatchers.IO) {
            val metadata = JSONObject().apply {
                put("name", name)
                put("mimeType", "application/vnd.google-apps.folder")
                put("parents", org.json.JSONArray().put(parentId))
            }
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?fields=id,name")
                .header("Authorization", "Bearer $accessToken")
                .post(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw DriveApiException("Create folder failed: $body", response.code)
                }
                val json = JSONObject(body)
                DriveFile(json.getString("id"), json.getString("name"))
            }
        }

    /** Finds [name] under [parentId], creating it if it doesn't exist yet. */
    suspend fun ensureFolder(accessToken: String, parentId: String, name: String): DriveFile =
        findChild(accessToken, parentId, name, folderOnly = true)
            ?: createFolder(accessToken, parentId, name)

    /**
     * Uploads the content behind [sourceUri] (a MediaStore content:// URI -- device media is
     * not reliably reachable as a raw filesystem [File] under scoped storage, so this always
     * streams through [ContentResolver]), creating a new Drive file under [parentId] (when
     * [existingFileId] is null) or replacing the content of an existing one. Returns the
     * resulting Drive file id. Uses Drive's chunked resumable upload protocol so a transient
     * network failure only costs the current chunk, not the whole file.
     */
    suspend fun uploadFile(
        accessToken: String,
        parentId: String,
        contentResolver: ContentResolver,
        sourceUri: Uri,
        totalSize: Long,
        name: String,
        mimeType: String,
        existingFileId: String?,
    ): String = withContext(Dispatchers.IO) {
        val sessionUri = initiateResumableSession(accessToken, parentId, totalSize, name, mimeType, existingFileId)
        uploadInChunks(accessToken, sessionUri, contentResolver, sourceUri, totalSize)
    }

    private fun initiateResumableSession(
        accessToken: String,
        parentId: String,
        totalSize: Long,
        name: String,
        mimeType: String,
        existingFileId: String?,
    ): String {
        val metadata = JSONObject().apply {
            put("name", name)
            if (existingFileId == null) {
                put("parents", org.json.JSONArray().put(parentId))
            }
        }
        val isUpdate = existingFileId != null
        val url = if (isUpdate) {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=resumable"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
        }
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", totalSize.toString())
        val body = metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = if (isUpdate) builder.patch(body).build() else builder.post(body).build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw DriveApiException("Could not start upload session: $errorBody", response.code)
            }
            return response.header("Location")
                ?: throw DriveApiException("Drive did not return an upload session URL.")
        }
    }

    private suspend fun uploadInChunks(
        accessToken: String,
        sessionUri: String,
        contentResolver: ContentResolver,
        sourceUri: Uri,
        totalSize: Long,
    ): String {
        val chunkSize = 8 * 1024 * 1024 // 8MB, a multiple of the 256KB Drive requires
        var offset = 0L

        val input = contentResolver.openInputStream(sourceUri)
            ?: throw DriveApiException("Could not open $sourceUri for reading.")
        input.use { stream ->
            while (offset < totalSize) {
                val remaining = totalSize - offset
                val length = minOf(chunkSize.toLong(), remaining).toInt()
                val buffer = ByteArray(length)
                readFully(stream, buffer)

                val end = offset + length
                val fileId = putChunkWithRetry(accessToken, sessionUri, buffer, offset, end, totalSize)
                if (fileId != null) return fileId
                offset = end
            }
        }
        throw DriveApiException("Upload finished sending bytes but Drive never confirmed the file.")
    }

    /** Returns the new file's id once Drive confirms the upload is complete, or null if more chunks are needed. */
    private suspend fun putChunkWithRetry(
        accessToken: String,
        sessionUri: String,
        buffer: ByteArray,
        offset: Long,
        end: Long,
        totalSize: Long,
    ): String? {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 4) {
            try {
                val requestBody = buffer.toRequestBody(null, 0, buffer.size)
                val request = Request.Builder()
                    .url(sessionUri)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Range", "bytes $offset-${end - 1}/$totalSize")
                    .put(requestBody)
                    .build()

                http.newCall(request).execute().use { response ->
                    return when (response.code) {
                        200, 201 -> {
                            val body = response.body?.string().orEmpty()
                            JSONObject(body).getString("id")
                        }
                        308 -> null // chunk accepted, Drive wants the rest
                        else -> throw DriveApiException(
                            "Upload chunk failed: ${response.body?.string().orEmpty()}",
                            response.code,
                        )
                    }
                }
            } catch (e: Exception) {
                lastError = e
                attempt++
                delay(1000L * attempt)
            }
        }
        throw lastError ?: DriveApiException("Upload chunk failed after retries.")
    }
}

private fun String.toHttpUrlBuilder() = this.toHttpUrl().newBuilder()

/** [InputStream.read] can return short reads; this keeps reading until [buffer] is full or the stream ends. */
private fun readFully(stream: InputStream, buffer: ByteArray) {
    var filled = 0
    while (filled < buffer.size) {
        val read = stream.read(buffer, filled, buffer.size - filled)
        if (read == -1) {
            throw DriveApiException("Media file ended earlier than expected while uploading.")
        }
        filled += read
    }
}
