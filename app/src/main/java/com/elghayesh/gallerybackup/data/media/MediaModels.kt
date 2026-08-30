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

/** Every folder in this tree (any depth), not including this node itself. */
fun FolderNode.flattenAllFolders(): List<FolderNode> {
    val result = mutableListOf<FolderNode>()
    for (child in children.values) {
        result.add(child)
        result.addAll(child.flattenAllFolders())
    }
    return result
}

/** Most recent [MediaItem.dateModifiedSec] anywhere in this folder or its subfolders; 0 if empty. */
fun FolderNode.latestModifiedSec(): Long {
    val ownMax = items.maxOfOrNull { it.dateModifiedSec } ?: 0L
    val childMax = children.values.maxOfOrNull { it.latestModifiedSec() } ?: 0L
    return maxOf(ownMax, childMax)
}

/**
 * Returns a copy of this tree with [excludedFolders] removed entirely, and with folders in
 * [hiddenFolders] / items in [hiddenMediaIds] removed unless [showHidden] is true. Items in
 * [trashedMediaIds] are always removed regardless of [showHidden] -- trash is a separate
 * concept from hidden, with its own screen.
 */
fun FolderNode.filtered(
    excludedFolders: Set<String>,
    hiddenFolders: Set<String>,
    hiddenMediaIds: Set<Long>,
    showHidden: Boolean,
    trashedMediaIds: Set<Long> = emptySet(),
): FolderNode {
    val result = FolderNode(path, name)
    for ((childName, child) in children) {
        if (child.path in excludedFolders) continue
        if (!showHidden && child.path in hiddenFolders) continue
        result.children[childName] =
            child.filtered(excludedFolders, hiddenFolders, hiddenMediaIds, showHidden, trashedMediaIds)
    }
    for (item in items) {
        if (item.id in trashedMediaIds) continue
        if (!showHidden && item.id in hiddenMediaIds) continue
        result.items.add(item)
    }
    return result
}

/** Every [MediaItem] in this folder and all its subfolders. */
fun FolderNode.allItemsRecursive(): List<MediaItem> =
    items + children.values.flatMap { it.allItemsRecursive() }

/**
 * Returns a copy of this tree with an empty [FolderNode] added for each path in
 * [virtualPaths] that doesn't already exist -- lets a just-created, still-empty folder
 * show up in the gallery before any media has been moved/copied into it.
 */
fun FolderNode.withVirtualFolders(virtualPaths: Set<String>): FolderNode {
    if (virtualPaths.isEmpty()) return this
    val result = FolderNode(path, name)
    result.children.putAll(children)
    result.items.addAll(items)
    for (virtualPath in virtualPaths) {
        var node = result
        var builtPath = ""
        for (segment in virtualPath.trim('/').split(FolderNode.PATH_SEPARATOR).filter { it.isNotBlank() }) {
            builtPath = if (builtPath.isEmpty()) segment else "$builtPath/$segment"
            val existing = node.children[segment]
            val next = if (existing != null) {
                FolderNode(builtPath, segment).apply {
                    children.putAll(existing.children)
                    items.addAll(existing.items)
                }
            } else {
                FolderNode(builtPath, segment)
            }
            node.children[segment] = next
            node = next
        }
    }
    return result
}
