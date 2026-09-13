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
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A release the in-app updater found: the CI build number it carries and where to download its APK. */
data class ReleaseInfo(val versionCode: Int, val tagName: String, val downloadUrl: String)

/**
 * Unlike a nullable [ReleaseInfo], this tells apart a check that ran successfully and found
 * nothing newer ([UpToDate]) from one that never actually completed ([Failed]) -- e.g. no network,
 * GitHub returned an error or got rate-limited, or its response couldn't be parsed. Collapsing
 * those into one "no update" result (as this used to) meant a silent failure looked exactly like
 * being genuinely up to date, with no way to tell which one actually happened.
 */
sealed class UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

/**
 * There's no Play Store for this app, so it updates itself. Source lives in a private repo, but
 * releases publish to "app-releases" -- a small public repo (see the build workflow) shared
 * across several projects, holding nothing but built APKs, tagged "mediahub-<n>" where <n> is
 * the same build number baked into this app's own versionCode (see app/build.gradle.kts).
 *
 * This used to fetch that repo's general release LIST (newest 50 by GitHub's own ordering) and
 * pick the highest "mediahub-<n>" found in it. That's what caused every "check for updates" to
 * report up to date even once real newer builds existed: this repo is shared with other
 * projects that publish far more often, and (separately) every MediaHub release's `created_at`
 * comes back identical regardless of when it was actually built -- together, that pushed
 * MediaHub's own newest releases completely off a single page of that list. Confirmed live: a
 * plain per_page=50 fetch of that list didn't contain any MediaHub release newer than build 99,
 * even with build 105 long since published. Every "up to date" past that point was simply this
 * app never being able to see its own latest release, not actually being current.
 *
 * Instead of depending on that list's ordering at all, this now looks up each candidate tag
 * directly -- "mediahub-<n>" is a deterministic name this app already controls -- walking forward
 * from the installed build number until a run of misses (an occasional skipped number is just a
 * CI run that failed to publish) or a request cap makes it stop. A direct tag lookup is
 * unaffected by how many other releases exist in the shared repo or how they're sorted.
 */
class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient()

    /** How many tag misses in a row end the forward scan -- tolerates a handful of consecutive
     * failed CI runs (which publish no release for that build number) without giving up on a
     * real update just past them. */
    private val maxConsecutiveMisses = 5

    /** Hard cap on tag lookups per check, regardless of misses -- bounds worst-case request count
     * (and therefore how much of GitHub's 60/hour unauthenticated rate limit one check can spend)
     * if the installed build is very far behind the latest. Whatever was found before hitting
     * this cap is still reported -- it just might not be the true latest in that rare case. */
    private val maxProbes = 30

    private sealed class TagLookup {
        data class Found(val release: ReleaseInfo) : TagLookup()
        data object NotFound : TagLookup()
        data class Failed(val reason: String) : TagLookup()
    }

    /** Runs the check and reports exactly what happened -- see [UpdateCheckResult]. */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        var best: ReleaseInfo? = null
        var versionCode = installedVersionCode() + 1
        var consecutiveMisses = 0
        var probes = 0

        while (consecutiveMisses < maxConsecutiveMisses && probes < maxProbes) {
            when (val result = fetchReleaseByTag(versionCode)) {
                is TagLookup.Found -> {
                    best = result.release
                    consecutiveMisses = 0
                }
                TagLookup.NotFound -> consecutiveMisses++
                is TagLookup.Failed -> return@withContext UpdateCheckResult.Failed(result.reason)
            }
            versionCode++
            probes++
        }

        best?.let { UpdateCheckResult.Available(it) } ?: UpdateCheckResult.UpToDate
    }

    private fun fetchReleaseByTag(versionCode: Int): TagLookup {
        val tag = "mediahub-$versionCode"
        val request = Request.Builder()
            .url("https://api.github.com/repos/YahiaElghayesh/app-releases/releases/tags/$tag")
            .header("Accept", "application/vnd.github+json")
            .build()

        val body: String = try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return TagLookup.NotFound
                if (!response.isSuccessful) {
                    // A 403 here is almost always GitHub's unauthenticated rate limit (60
                    // requests/hour per IP) -- worth calling out specifically since it's the one
                    // failure mode that looks like nothing is wrong (no crash, no obvious network
                    // issue) yet silently blocks every check until the hour rolls over.
                    val reason = if (response.code == 403) {
                        "GitHub rate limit hit (HTTP 403) -- try again later"
                    } else {
                        "GitHub returned HTTP ${response.code}"
                    }
                    return TagLookup.Failed(reason)
                }
                response.body?.string() ?: return TagLookup.Failed("Empty response from GitHub")
            }
        } catch (e: IOException) {
            return TagLookup.Failed(e.message ?: "Network error reaching GitHub")
        }

        val release = try {
            JSONObject(body)
        } catch (e: Exception) {
            return TagLookup.Failed("Couldn't parse GitHub's response")
        }

        val assets = release.optJSONArray("assets") ?: return TagLookup.NotFound
        var downloadUrl: String? = null
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            if (asset.optString("name") == "mediahub.apk") {
                downloadUrl = asset.optString("browser_download_url")
                break
            }
        }
        return downloadUrl?.let { TagLookup.Found(ReleaseInfo(versionCode, tag, it)) } ?: TagLookup.NotFound
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
