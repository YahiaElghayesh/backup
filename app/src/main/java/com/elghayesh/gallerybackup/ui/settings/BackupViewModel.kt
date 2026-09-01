package com.elghayesh.gallerybackup.ui.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elghayesh.gallerybackup.data.drive.DriveAuthManager
import com.elghayesh.gallerybackup.data.drive.DriveAuthResult
import com.elghayesh.gallerybackup.data.onedrive.OneDriveAuthManager
import com.elghayesh.gallerybackup.data.onedrive.OneDriveAuthResult
import com.elghayesh.gallerybackup.data.onedrive.OneDriveSettingsRepository
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
    private val oneDriveSettings = OneDriveSettingsRepository(app)
    private val oneDriveAuthManager = OneDriveAuthManager(app)

    val selectedFolders: Flow<Set<String>> = settings.selectedFolders
    val wifiOnly: Flow<Boolean> = settings.wifiOnly
    val isConnected: Flow<Boolean> = settings.isConnected
    val lastSyncTime: Flow<Long?> = settings.lastSyncTime
    val lastSyncStatus: Flow<String?> = settings.lastSyncStatus

    val oneDriveClientId: Flow<String> = oneDriveSettings.clientId
    val oneDriveEnabled: Flow<Boolean> = oneDriveSettings.enabled
    val oneDriveConnected: Flow<Boolean> = oneDriveSettings.connected
    val oneDriveLastSyncTime: Flow<Long?> = oneDriveSettings.lastSyncTime
    val oneDriveLastSyncStatus: Flow<String?> = oneDriveSettings.lastSyncStatus

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Emits a [PendingIntent] the host Activity must launch when Google needs the user's consent. */
    private val consentRequestChannel = Channel<PendingIntent>(Channel.CONFLATED)
    val consentRequests: Flow<PendingIntent> = consentRequestChannel.receiveAsFlow()

    /** Emits an [Intent] the host Activity must launch (a plain browser tab) to start OneDrive sign-in. */
    private val oneDriveAuthRequestChannel = Channel<Intent>(Channel.CONFLATED)
    val oneDriveAuthRequests: Flow<Intent> = oneDriveAuthRequestChannel.receiveAsFlow()

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

    fun setOneDriveEnabled(enabled: Boolean) {
        viewModelScope.launch { oneDriveSettings.setEnabled(enabled) }
    }

    /** Saves [clientId] and immediately starts sign-in with it, in that order within one coroutine
     * so the connect step can never race ahead of the write and read a stale (blank) client id. */
    fun connectToOneDrive(clientId: String) {
        viewModelScope.launch {
            oneDriveSettings.setClientId(clientId)
            val intent = oneDriveAuthManager.buildAuthorizeIntent()
            if (intent == null) {
                _statusMessage.value = "Enter your Azure app's client id first."
            } else {
                oneDriveAuthRequestChannel.send(intent)
            }
        }
    }

    /** Call with the redirect [Uri] MainActivity received back from the OneDrive sign-in browser tab. */
    fun onOneDriveRedirect(uri: Uri) {
        viewModelScope.launch {
            when (val result = oneDriveAuthManager.handleRedirect(uri)) {
                is OneDriveAuthResult.Granted -> {
                    oneDriveSettings.setEnabled(true)
                    _statusMessage.value = "Connected to OneDrive."
                }
                is OneDriveAuthResult.Error -> _statusMessage.value = "Couldn't connect to OneDrive: ${result.message}"
            }
        }
    }

    fun disconnectOneDrive() {
        viewModelScope.launch {
            oneDriveSettings.disconnect()
            oneDriveSettings.setEnabled(false)
            _statusMessage.value =
                "Disconnected in-app. To fully revoke access, remove it under " +
                    "account.microsoft.com/privacy/app-access as well."
        }
    }
}
