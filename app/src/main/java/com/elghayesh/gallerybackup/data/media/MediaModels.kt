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

/** Walks [root] down [path] (e.g. "DCIM/Camera"), returning null if the path no longer exists.
 * A "." segment (e.g. "DCIM/Camera/.") stays on the current node instead of descending -- used
 * by the gallery to link to "just this folder's own items" as a path distinct from the folder
 * itself, without needing a real child by that name. A literal "." never occurs in a real
 * MediaStore relative path or filesystem folder name, both of which skip dot-prefixed entries. */
fun FolderNode.findNode(path: String): FolderNode? {
    if (path.isBlank()) return this
    var node = this
    for (segment in path.trim('/').split(FolderNode.PATH_SEPARATOR)) {
        node = if (segment == ".") node else (node.children[segment] ?: return null)
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
 * Returns a copy of this tree with folders in [hiddenFolders] / items in [hiddenMediaIds] removed
 * unless [showHidden] is true. Items in [trashedMediaIds] are always removed regardless of
 * [showHidden] -- trash is a separate concept from hidden, with its own screen. This has nothing
 * to do with which folders are *pinned to the gallery's home page* (see
 * `GalleryPreferencesRepository.includedFolders`) -- pinning only decides what additionally shows
 * at the root; it never removes a folder from view when browsing into its real parent, so it
 * plays no part in this recursive prune.
 */
fun FolderNode.filtered(
    hiddenFolders: Set<String>,
    hiddenMediaIds: Set<Long>,
    showHidden: Boolean,
    trashedMediaIds: Set<Long> = emptySet(),
): FolderNode {
    val result = FolderNode(path, name)
    for ((childName, child) in children) {
        if (!showHidden && child.path in hiddenFolders) continue
        val filteredChild = child.filtered(hiddenFolders, hiddenMediaIds, showHidden, trashedMediaIds)
        // Drop a folder that's left with nothing in it -- e.g. every item in it was just trashed
        // by a move, which copies to the new location and trashes the original MediaStore row
        // rather than deleting it, so the row (and this folder) would otherwise keep showing up
        // as empty until the trash is actually purged. A deliberately-kept-empty user-created
        // folder is re-added afterward by withVirtualFolders, so this is safe to prune.
        if (filteredChild.items.isNotEmpty() || filteredChild.children.isNotEmpty()) {
            result.children[childName] = filteredChild
        }
    }
    for (item in items) {
        if (item.id in trashedMediaIds) continue
        if (!showHidden && item.id in hiddenMediaIds) continue
        result.items.add(item)
    }
    return result
}

/**
 * Total media count in this folder and every folder beneath it, except any child whose own path
 * is in [includedFolders] -- that child is pinned to the gallery's home page and promoted out of
 * this listing (see [promotedChildren]), so its contents shouldn't be double-counted on this
 * folder's own tile.
 */
fun FolderNode.promotionAwareItemCount(includedFolders: Set<String>): Int =
    items.size + promotedChildren(includedFolders).sumOf { it.promotionAwareItemCount(includedFolders) }

/** Like [FolderNode.coverUri], but skips any child promoted out via [includedFolders]. See [promotionAwareItemCount]. */
fun FolderNode.promotionAwareCoverUri(includedFolders: Set<String>): Uri? =
    items.firstOrNull()?.uri
        ?: promotedChildren(includedFolders).firstNotNullOfOrNull { it.promotionAwareCoverUri(includedFolders) }

/**
 * This folder's direct real children, minus any that are individually pinned to the gallery's
 * home page. A pinned child is promoted out of its real parent's listing everywhere that parent
 * is shown, and instead surfaces as its own separate tile at the gallery root -- so it isn't
 * shown in two places at once. Applies uniformly at any depth for any folder, including the root
 * itself: the root's own real children follow this same rule, they just aren't otherwise treated
 * as pinned by default (see [GalleryPreferencesRepository.includedFolders] for the full rule and
 * how the root additionally surfaces every pinned path as its own tile).
 */
fun FolderNode.promotedChildren(includedFolders: Set<String>): List<FolderNode> =
    children.values.filter { it.path !in includedFolders }

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
