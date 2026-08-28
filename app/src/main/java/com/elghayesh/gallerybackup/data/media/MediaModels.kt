package com.elghayesh.gallerybackup.data.media

import android.net.Uri

/**
 * A single photo or video found on the device.
 * [folderPath] is the folder it lives in, relative to external storage root,
 * e.g. "DCIM/Camera" or "Pictures/Screenshots/Work" ("" means the storage root itself).
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val folderPath: String,
    val dateModifiedSec: Long,
    val size: Long,
    val mimeType: String,
    val isVideo: Boolean,
    val durationMs: Long = 0L,
)

/**
 * One node of the on-device folder tree. [path] is the full relative path from the
 * storage root ("" for the synthetic root node), [name] is just the last path segment.
 */
class FolderNode(
    val path: String,
    val name: String,
) {
    val children: MutableMap<String, FolderNode> = linkedMapOf()
    val items: MutableList<MediaItem> = mutableListOf()

    /** Total media count in this folder and every folder beneath it. */
    fun totalItemCount(): Int =
        items.size + children.values.sumOf { it.totalItemCount() }

    fun coverUri(): Uri? =
        items.firstOrNull()?.uri ?: children.values.firstNotNullOfOrNull { it.coverUri() }

    companion object {
        const val PATH_SEPARATOR = "/"

        /** Builds a folder tree from a flat scan of every media item on the device. */
        fun buildTree(allItems: List<MediaItem>): FolderNode {
            val root = FolderNode(path = "", name = "")
            for (item in allItems) {
                val segments = item.folderPath
                    .trim('/')
                    .split(PATH_SEPARATOR)
                    .filter { it.isNotBlank() }
                var node = root
                var builtPath = ""
                for (segment in segments) {
                    builtPath = if (builtPath.isEmpty()) segment else "$builtPath/$segment"
                    node = node.children.getOrPut(segment) { FolderNode(builtPath, segment) }
                }
                node.items.add(item)
            }
            return root
        }
    }
}

/** Walks [root] down [path] (e.g. "DCIM/Camera"), returning null if the path no longer exists. */
fun FolderNode.findNode(path: String): FolderNode? {
    if (path.isBlank()) return this
    var node = this
    for (segment in path.trim('/').split(FolderNode.PATH_SEPARATOR)) {
        node = node.children[segment] ?: return null
    }
    return node
}
