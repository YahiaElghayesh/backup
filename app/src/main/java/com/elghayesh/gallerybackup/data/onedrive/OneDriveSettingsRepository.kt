package com.elghayesh.gallerybackup.data.onedrive

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.oneDriveStore by preferencesDataStore(name = "onedrive_settings")

/** The PKCE verifier/state saved between launching the sign-in browser tab and its redirect coming back. */
data class PendingAuth(val verifier: String?, val state: String?)

/**
 * Persists OneDrive's own connection state, separate from [com.elghayesh.gallerybackup.data.settings.SettingsRepository]
 * (which is Google Drive-specific despite the generic name, kept as-is to avoid touching working
 * code). The user supplies their own Azure app registration's client id here rather than it
 * being baked into the build, since -- unlike Google Cloud OAuth clients -- an Azure "Mobile and
 * desktop" client id has no secret to protect and is safe to store client-side.
 */
class OneDriveSettingsRepository(private val context: Context) {

    private object Keys {
        val CLIENT_ID = stringPreferencesKey("client_id")
        val ENABLED = booleanPreferencesKey("enabled")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val ACCESS_TOKEN_EXPIRES_AT = longPreferencesKey("access_token_expires_at")
        val PENDING_VERIFIER = stringPreferencesKey("pending_verifier")
        val PENDING_STATE = stringPreferencesKey("pending_state")
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        val LAST_SYNC_STATUS = stringPreferencesKey("last_sync_status")
    }

    val clientId: Flow<String> = context.oneDriveStore.data.map { it[Keys.CLIENT_ID] ?: "" }
    val enabled: Flow<Boolean> = context.oneDriveStore.data.map { it[Keys.ENABLED] ?: false }
    val connected: Flow<Boolean> = context.oneDriveStore.data.map { !it[Keys.REFRESH_TOKEN].isNullOrEmpty() }
    val accessToken: Flow<String?> = context.oneDriveStore.data.map { it[Keys.ACCESS_TOKEN] }
    val refreshToken: Flow<String?> = context.oneDriveStore.data.map { it[Keys.REFRESH_TOKEN] }
    val accessTokenExpiresAtSec: Flow<Long> = context.oneDriveStore.data.map { it[Keys.ACCESS_TOKEN_EXPIRES_AT] ?: 0L }
    val pendingAuth: Flow<PendingAuth> =
        context.oneDriveStore.data.map { PendingAuth(it[Keys.PENDING_VERIFIER], it[Keys.PENDING_STATE]) }
    val lastSyncTime: Flow<Long?> = context.oneDriveStore.data.map { it[Keys.LAST_SYNC_TIME] }
    val lastSyncStatus: Flow<String?> = context.oneDriveStore.data.map { it[Keys.LAST_SYNC_STATUS] }

    suspend fun setClientId(id: String) {
        context.oneDriveStore.edit { it[Keys.CLIENT_ID] = id.trim() }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.oneDriveStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setPendingAuth(verifier: String, state: String) {
        context.oneDriveStore.edit { prefs ->
            prefs[Keys.PENDING_VERIFIER] = verifier
            prefs[Keys.PENDING_STATE] = state
        }
    }

    suspend fun clearPendingAuth() {
        context.oneDriveStore.edit { prefs ->
            prefs.remove(Keys.PENDING_VERIFIER)
            prefs.remove(Keys.PENDING_STATE)
        }
    }

    suspend fun setTokens(accessToken: String, refreshToken: String?, expiresAtSec: Long) {
        context.oneDriveStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.ACCESS_TOKEN_EXPIRES_AT] = expiresAtSec
            if (refreshToken != null) prefs[Keys.REFRESH_TOKEN] = refreshToken
        }
    }

    suspend fun disconnect() {
        context.oneDriveStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.REFRESH_TOKEN)
            prefs.remove(Keys.ACCESS_TOKEN_EXPIRES_AT)
        }
    }

    suspend fun recordSyncResult(status: String, timeMillis: Long = System.currentTimeMillis()) {
        context.oneDriveStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_TIME] = timeMillis
            prefs[Keys.LAST_SYNC_STATUS] = status
        }
    }
}
