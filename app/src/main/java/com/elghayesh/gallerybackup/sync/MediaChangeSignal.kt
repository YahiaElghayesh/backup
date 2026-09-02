package com.elghayesh.gallerybackup.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide "something in MediaStore changed" pulse, emitted by [MediaChangeObserver] whenever
 * it fires. Lets the gallery screen -- which doesn't otherwise hear about ContentObserver
 * callbacks, since those are registered once at the Application level -- know it should rescan,
 * without the sync/backup code depending on any UI layer.
 */
object MediaChangeSignal {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}
