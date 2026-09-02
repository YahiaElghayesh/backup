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

/** Most recent [MediaItem.dateModifiedSec] anywhere in this folder or its subfolders; 0 if empty. */
fun FolderNode.latestModifiedSec(): Long {
    val ownMax = items.maxOfOrNull { it.dateModifiedSec } ?: 0L
    val childMax = children.values.maxOfOrNull { it.latestModifiedSec() } ?: 0L
    return maxOf(ownMax, childMax)
}

/**
 * Resolves whether a single folder at [path] is effectively visible under the folder explorer's
 * opt-in "show this at the root, auto-include its subfolders unless I say otherwise" model: the
 * *nearest* explicit ancestor decision wins (an explicit exclude always beats an explicit include
 * at the same folder), and anything with no explicit decision anywhere in its ancestor chain
 * inherits whichever way its nearest ancestor went -- top-level folders with no explicit decision
 * of their own default to hidden, since opting in is the whole point of the explorer. Before the
 * explorer's ever been used to include anything ([includedFolders] empty), folders default to
 * shown instead -- explicit excludes still apply on top of that default, so unchecking a folder
 * works from a fresh install without needing to check another one first.
 */
fun isFolderEffectivelyVisible(path: String, includedFolders: Set<String>, explicitlyExcluded: Set<String>): Boolean {
    // No folder has ever been explicitly included yet -- default to showing everything (opt-out
    // model), so a fresh install isn't empty and excluding still works. The moment includedFolders
    // has an entry, unmarked folders default to hidden instead (opt-in model).
    var visible = includedFolders.isEmpty()
    var built = ""
    for (segment in path.split("/").filter { it.isNotBlank() }) {
        built = if (built.isEmpty()) segment else "$built/$segment"
        visible = when {
            built in explicitlyExcluded -> false
            built in includedFolders -> true
            else -> visible
        }
    }
    return visible
}

/**
 * Returns a copy of this tree with invisible folders/items pruned out. A folder's own items only
 * survive if the folder itself is visible under [includedFolders]/[explicitlyExcludedFolders] (see
 * [isFolderEffectivelyVisible]), but every folder is still walked regardless of its own visibility
 * so that a folder explicitly re-included deeper down an otherwise-invisible branch (e.g. an
 * invisible "DCIM" containing an explicitly included "DCIM/Camera") still surfaces -- an invisible
 * folder is kept in the result only as a pass-through container when it has a visible descendant,
 * and dropped entirely otherwise. Folders in [hiddenFolders] / items in [hiddenMediaIds] are removed
 * unless [showHidden] is true. Items in [trashedMediaIds] are always removed regardless of
 * [showHidden] -- trash is a separate concept from hidden, with its own screen.
 */
fun FolderNode.filtered(
    includedFolders: Set<String>,
    explicitlyExcludedFolders: Set<String>,
    hiddenFolders: Set<String>,
    hiddenMediaIds: Set<Long>,
    showHidden: Boolean,
    trashedMediaIds: Set<Long> = emptySet(),
): FolderNode {
    val visible = isFolderEffectivelyVisible(path, includedFolders, explicitlyExcludedFolders)
    val result = FolderNode(path, name)
    for ((childName, child) in children) {
        if (!showHidden && child.path in hiddenFolders) continue
        val filteredChild =
            child.filtered(includedFolders, explicitlyExcludedFolders, hiddenFolders, hiddenMediaIds, showHidden, trashedMediaIds)
        val childVisible = isFolderEffectivelyVisible(child.path, includedFolders, explicitlyExcludedFolders)
        if (childVisible || filteredChild.children.isNotEmpty() || filteredChild.items.isNotEmpty()) {
            result.children[childName] = filteredChild
        }
    }
    if (visible) {
        for (item in items) {
            if (item.id in trashedMediaIds) continue
            if (!showHidden && item.id in hiddenMediaIds) continue
            result.items.add(item)
        }
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
