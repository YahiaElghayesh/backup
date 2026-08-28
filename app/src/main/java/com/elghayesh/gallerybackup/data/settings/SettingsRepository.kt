package com.elghayesh.gallerybackup.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gallery_backup_settings")

/**
 * Persists the user's backup configuration: which top-level device folders to mirror
 * into Drive, whether to restrict uploads to Wi-Fi, and whether Drive access has been
 * granted. This is deliberately separate from the sync-progress tracking in Room
 * (`SyncedFileDao`/`SyncedFolderDao`) -- this is "what the user asked for", that is
 * "what has actually happened so far".
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val SELECTED_FOLDERS = stringSetPreferencesKey("selected_folders")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val IS_CONNECTED = booleanPreferencesKey("is_connected")
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        val LAST_SYNC_STATUS = stringPreferencesKey("last_sync_status")
    }

    val selectedFolders: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.SELECTED_FOLDERS] ?: emptySet() }

    val wifiOnly: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.WIFI_ONLY] ?: true }

    val isConnected: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.IS_CONNECTED] ?: false }

    val lastSyncTime: Flow<Long?> =
        context.dataStore.data.map { it[Keys.LAST_SYNC_TIME] }

    val lastSyncStatus: Flow<String?> =
        context.dataStore.data.map { it[Keys.LAST_SYNC_STATUS] }

    suspend fun setFolderSelected(path: String, selected: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SELECTED_FOLDERS] ?: emptySet()
            prefs[Keys.SELECTED_FOLDERS] = if (selected) current + path else current - path
        }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setConnected(connected: Boolean) {
        context.dataStore.edit { it[Keys.IS_CONNECTED] = connected }
    }

    suspend fun recordSyncResult(status: String, timeMillis: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_TIME] = timeMillis
            prefs[Keys.LAST_SYNC_STATUS] = status
        }
    }
}
