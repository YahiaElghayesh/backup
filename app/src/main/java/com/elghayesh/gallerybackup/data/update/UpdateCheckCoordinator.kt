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

    /** Null until the first check finishes; true if that check found an update, false if it was up to date (or failed). */
    private val _lastCheckFoundUpdate = MutableStateFlow<Boolean?>(null)
    val lastCheckFoundUpdate: StateFlow<Boolean?> = _lastCheckFoundUpdate.asStateFlow()

    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun requestCheck() {
        _requests.tryEmit(Unit)
    }

    fun onCheckStarted() {
        _isChecking.value = true
    }

    fun onCheckFinished(foundUpdate: Boolean) {
        _isChecking.value = false
        _lastCheckFoundUpdate.value = foundUpdate
    }
}
