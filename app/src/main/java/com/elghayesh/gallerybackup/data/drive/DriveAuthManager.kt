package com.elghayesh.gallerybackup.data.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Handles Google authorization for Drive access using the Identity Services
 * Authorization API. This works from a sideloaded APK exactly as well as a Play Store
 * one -- Play Store distribution has nothing to do with whether OAuth works, only the
 * OAuth client registered in Google Cloud Console matters (see README for setup).
 *
 * We deliberately request only [DRIVE_FILE_SCOPE] ("drive.file"): the app can only see
 * and write files/folders that it itself created. That keeps this out of Google's
 * "restricted scope" verification process, so the OAuth consent screen can stay in
 * Production without Google review, and tokens do not expire every 7 days the way they
 * would if the consent screen were stuck in Testing mode.
 */
class DriveAuthManager(context: Context) {

    private val appContext = context.applicationContext
    private val client = Identity.getAuthorizationClient(appContext)

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()

    /**
     * Attempts to authorize. If the user already granted access before, this resolves
     * silently with a fresh access token and no UI. If consent is needed (first run, or
     * a previous grant was revoked), returns [DriveAuthResult.ConsentRequired] with a
     * [PendingIntent] the caller must launch via an Activity Result contract.
     */
    suspend fun authorize(): DriveAuthResult = try {
        val result = client.authorize(request).await()
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
            if (pendingIntent != null) {
                DriveAuthResult.ConsentRequired(pendingIntent)
            } else {
                DriveAuthResult.Error("Google did not provide a consent screen to show.")
            }
        } else {
            val token = result.accessToken
            if (token != null) {
                DriveAuthResult.Granted(token)
            } else {
                DriveAuthResult.Error("Google granted access but returned no token.")
            }
        }
    } catch (e: Exception) {
        DriveAuthResult.Error(e.message ?: "Authorization failed.")
    }

    /** Call this with the [Intent] delivered to your ActivityResult callback after the user responds to consent. */
    fun handleConsentResponse(data: Intent?): DriveAuthResult = try {
        val result = client.getAuthorizationResultFromIntent(data)
        val token = result.accessToken
        if (token != null) {
            DriveAuthResult.Granted(token)
        } else {
            DriveAuthResult.Error("No access token after consent.")
        }
    } catch (e: Exception) {
        DriveAuthResult.Error(e.message ?: "Consent was not completed.")
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}

sealed class DriveAuthResult {
    data class Granted(val accessToken: String) : DriveAuthResult()
    data class ConsentRequired(val pendingIntent: PendingIntent) : DriveAuthResult()
    data class Error(val message: String) : DriveAuthResult()
}
