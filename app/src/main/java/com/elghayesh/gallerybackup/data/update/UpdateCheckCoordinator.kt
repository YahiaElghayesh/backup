package com.elghayesh.gallerybackup.data.update

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets a "Check for updates" button in Settings trigger the same check [AppUpdateController]
 * (mounted once near the root of the Compose tree, unrelated to Settings in the nav graph) runs
 * automatically on launch, and see its outcome -- without the two composables needing a direct
 * reference to each other.
 */
object UpdateCheckCoordinator {
    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    /** Null until the first check finishes; true if that check found an update, false if it
     * completed and found none. A check that FAILED outright (network error, bad HTTP response,
     * unparseable JSON) is NOT reported here as false -- see [lastCheckFailureReason] -- so a
     * silent failure never gets displayed as "you're on the latest version". */
    private val _lastCheckFoundUpdate = MutableStateFlow<Boolean?>(null)
    val lastCheckFoundUpdate: StateFlow<Boolean?> = _lastCheckFoundUpdate.asStateFlow()

    /** Non-null only when the most recent check failed outright rather than completing with an
     * up-to-date/available result -- see [UpdateChecker.checkForUpdate]'s [com.elghayesh.gallerybackup.data.update.UpdateCheckResult.Failed]. */
    private val _lastCheckFailureReason = MutableStateFlow<String?>(null)
    val lastCheckFailureReason: StateFlow<String?> = _lastCheckFailureReason.asStateFlow()

    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun requestCheck() {
        _requests.tryEmit(Unit)
    }

    fun onCheckStarted() {
        _isChecking.value = true
        _lastCheckFailureReason.value = null
    }

    fun onCheckFinished(foundUpdate: Boolean) {
        _isChecking.value = false
        _lastCheckFoundUpdate.value = foundUpdate
        _lastCheckFailureReason.value = null
    }

    fun onCheckFailed(reason: String) {
        _isChecking.value = false
        _lastCheckFoundUpdate.value = null
        _lastCheckFailureReason.value = reason
    }
}
