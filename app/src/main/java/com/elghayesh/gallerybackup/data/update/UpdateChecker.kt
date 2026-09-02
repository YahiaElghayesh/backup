package com.elghayesh.gallerybackup.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException

/** A release the in-app updater found: the CI build number it carries and where to download its APK. */
data class ReleaseInfo(val versionCode: Int, val tagName: String, val downloadUrl: String)

/**
 * There's no Play Store for this app, so it updates itself. Source lives in a private repo, but
 * releases publish to "app-releases" -- a small public repo (see the build workflow) shared
 * across several projects, holding nothing but built APKs. Because it's shared, this can't just
 * read /releases/latest (that would return whichever project published most recently, not
 * MediaHub's); instead it lists releases and picks the highest build number tagged
 * "mediahub-<n>" (the same number baked into this app's own versionCode -- see
 * app/build.gradle.kts), downloads its APK if newer than what's installed, and hands it to the
 * system package installer. The whole check is a plain unauthenticated read of a public repo --
 * no credential of any kind lives in this app.
 */
class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient()

    /** Null if already up to date, the check failed (offline, etc.), or no release could be parsed. */
    suspend fun checkForUpdate(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/YahiaElghayesh/app-releases/releases?per_page=50")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (e: IOException) {
            null
        } ?: return@withContext null

        val releases = try { JSONArray(body) } catch (e: Exception) { return@withContext null }
        var best: ReleaseInfo? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val tag = release.optString("tag_name")
            if (!tag.startsWith("mediahub-")) continue
            val versionCode = tag.removePrefix("mediahub-").toIntOrNull() ?: continue
            if (best != null && versionCode <= best.versionCode) continue

            val assets = release.optJSONArray("assets") ?: continue
            var downloadUrl: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("name") == "mediahub.apk") {
                    downloadUrl = asset.optString("browser_download_url")
                    break
                }
            }
            downloadUrl?.let { best = ReleaseInfo(versionCode, tag, it) }
        }
        best?.takeIf { it.versionCode > installedVersionCode() }
    }

    private fun installedVersionCode(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    /** Downloads [url] to app-private storage, reporting 0f..1f progress as it goes. */
    suspend fun downloadApk(url: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty update download")
            val total = body.contentLength()
            val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val file = File(dir, "mediahub-update.apk")
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var readBytes = 0L
                    var n: Int
                    while (input.read(buffer).also { n = it } >= 0) {
                        output.write(buffer, 0, n)
                        readBytes += n
                        if (total > 0) onProgress(readBytes.toFloat() / total)
                    }
                }
            }
            file
        }
    }

    /** Launches the system package installer on [file]. Requires [canInstallUnknownApps] to be true. */
    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canInstallUnknownApps(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user grants this app permission to install APKs it downloads itself. */
    fun unknownAppsSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
}
