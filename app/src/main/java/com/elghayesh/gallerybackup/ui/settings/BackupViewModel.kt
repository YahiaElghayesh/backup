package com.elghayesh.gallerybackup.ui.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elghayesh.gallerybackup.data.drive.DriveAuthManager
import com.elghayesh.gallerybackup.data.drive.DriveAuthResult
import com.elghayesh.gallerybackup.data.settings.SettingsRepository
import com.elghayesh.gallerybackup.sync.SyncScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val authManager = DriveAuthManager(app)

    val selectedFolders: Flow<Set<String>> = settings.selectedFolders
    val wifiOnly: Flow<Boolean> = settings.wifiOnly
    val isConnected: Flow<Boolean> = settings.isConnected
    val lastSyncTime: Flow<Long?> = settings.lastSyncTime
    val lastSyncStatus: Flow<String?> = settings.lastSyncStatus

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Emits a [PendingIntent] the host Activity must launch when Google needs the user's consent. */
    private val consentRequestChannel = Channel<PendingIntent>(Channel.CONFLATED)
    val consentRequests: Flow<PendingIntent> = consentRequestChannel.receiveAsFlow()

    fun toggleFolder(path: String, selected: Boolean) {
        viewModelScope.launch { settings.setFolderSelected(path, selected) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            settings.setWifiOnly(enabled)
            SyncScheduler.schedulePeriodicSync(getApplication(), wifiOnly = enabled)
        }
    }

    fun connectToDrive() {
        viewModelScope.launch {
            when (val result = authManager.authorize()) {
                is DriveAuthResult.Granted -> {
                    settings.setConnected(true)
                    _statusMessage.value = "Connected to Google Drive."
                }
                is DriveAuthResult.ConsentRequired -> consentRequestChannel.send(result.pendingIntent)
                is DriveAuthResult.Error -> _statusMessage.value = "Couldn't connect: ${result.message}"
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            when (val result = authManager.handleConsentResponse(data)) {
                is DriveAuthResult.Granted -> {
                    settings.setConnected(true)
                    _statusMessage.value = "Connected to Google Drive."
                }
                is DriveAuthResult.ConsentRequired -> _statusMessage.value = "Still needs consent -- try again."
                is DriveAuthResult.Error -> _statusMessage.value = "Couldn't connect: ${result.message}"
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            settings.setConnected(false)
            _statusMessage.value =
                "Disconnected in-app. To fully revoke access, remove it under " +
                    "myaccount.google.com/permissions as well."
        }
    }

    fun backupNow() {
        SyncScheduler.runOneOffSync(getApplication())
        _statusMessage.value = "Backup started."
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
