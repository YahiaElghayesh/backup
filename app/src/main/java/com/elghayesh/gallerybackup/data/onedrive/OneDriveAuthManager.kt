package com.elghayesh.gallerybackup.data.onedrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class OneDriveAuthResult {
    data class Granted(val accessToken: String) : OneDriveAuthResult()
    data class Error(val message: String) : OneDriveAuthResult()
}

/**
 * Handles Microsoft sign-in for OneDrive access via a plain OAuth2 Authorization Code + PKCE
 * flow in the system browser -- deliberately not the MSAL SDK, which (for its "Android"
 * platform redirect type) needs a redirect URI containing a base64 hash of the APK's signing
 * certificate. That is brittle for a sideloaded, CI-built APK where the signing key isn't
 * fixed. Registering this app's Azure client as a "Mobile and desktop applications" platform
 * instead accepts a plain custom-scheme redirect URI ([REDIRECT_URI]) with no signing key
 * involved at all, at the cost of doing the token exchange ourselves instead of via MSAL.
 */
class OneDriveAuthManager(context: Context) {

    private val appContext = context.applicationContext
    private val settings = OneDriveSettingsRepository(appContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Null if the user hasn't entered their Azure app's client id in settings yet. */
    suspend fun buildAuthorizeIntent(): Intent? {
        val clientId = settings.clientId.first()
        if (clientId.isBlank()) return null

        val verifier = generateCodeVerifier()
        val challenge = codeChallenge(verifier)
        val state = UUID.randomUUID().toString()
        settings.setPendingAuth(verifier, state)

        val uri = Uri.parse("$AUTHORITY/oauth2/v2.0/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("prompt", "select_account")
            .build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Call with the redirect [Uri] MainActivity received once the browser sends the user back. */
    suspend fun handleRedirect(uri: Uri): OneDriveAuthResult {
        val pending = settings.pendingAuth.first()
        settings.clearPendingAuth()

        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        if (error != null) return OneDriveAuthResult.Error(error)

        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code == null || state == null || pending.verifier == null || state != pending.state) {
            return OneDriveAuthResult.Error("Sign-in response didn't match the request. Please try again.")
        }

        val clientId = settings.clientId.first()
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", pending.verifier)
            .build()
        return postToken(form)
    }

    private suspend fun refreshAccessToken(): OneDriveAuthResult {
        val refreshToken = settings.refreshToken.first() ?: return OneDriveAuthResult.Error("Not connected.")
        val clientId = settings.clientId.first()
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("redirect_uri", REDIRECT_URI)
            .build()
        return postToken(form)
    }

    /** Returns a currently-valid access token, transparently refreshing it first if needed, or null if reconnecting is required. */
    suspend fun getValidAccessToken(): String? {
        val cached = settings.accessToken.first()
        val expiresAt = settings.accessTokenExpiresAtSec.first()
        val nowSec = System.currentTimeMillis() / 1000
        if (cached != null && nowSec < expiresAt - 60) return cached
        return when (val result = refreshAccessToken()) {
            is OneDriveAuthResult.Granted -> result.accessToken
            is OneDriveAuthResult.Error -> null
        }
    }

    private suspend fun postToken(form: FormBody): OneDriveAuthResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$AUTHORITY/oauth2/v2.0/token").post(form).build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(body).optString("error_description") }.getOrNull()
                    return@use OneDriveAuthResult.Error(
                        message?.takeIf { it.isNotBlank() } ?: "Sign-in failed (HTTP ${response.code}).",
                    )
                }
                val json = JSONObject(body)
                val accessToken = json.getString("access_token")
                val expiresIn = json.optLong("expires_in", 3600L)
                val refreshToken = if (json.has("refresh_token")) json.getString("refresh_token") else null
                settings.setTokens(accessToken, refreshToken, System.currentTimeMillis() / 1000 + expiresIn)
                OneDriveAuthResult.Granted(accessToken)
            }
        } catch (e: Exception) {
            OneDriveAuthResult.Error(e.message ?: "Sign-in failed.")
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        const val REDIRECT_URI = "mediahub://oauth/onedrive"
        const val SCOPES = "Files.ReadWrite offline_access"
        const val AUTHORITY = "https://login.microsoftonline.com/common"
    }
}
