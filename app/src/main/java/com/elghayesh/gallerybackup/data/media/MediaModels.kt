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
    /** When MediaStore first indexed this file (MediaColumns.DATE_ADDED) -- the closest thing
     * Android exposes to a real filesystem "date created", which isn't reliably readable across
     * OS versions/vendors any other way. Shown as "Created" in the Properties dialog. */
    val dateAddedSec: Long,
    /** A photo's real capture time -- read directly from its own EXIF DateTimeOriginal tag (see
     * MediaRepository.readExifDateTakenSec), NOT from MediaStore's own cached DATE_TAKEN column,
     * which proved unreliable: it's a value MediaStore cached once during whatever scan first
     * indexed the file, and never refreshes just because the file's actual EXIF changes
     * afterward. Falls back to MediaStore's DATE_TAKEN, then to [dateModifiedSec], for videos
     * (no EXIF) or when a photo has no EXIF date at all -- so this is always a usable timestamp
     * rather than sometimes zero, in units of seconds for consistency with [dateModifiedSec]. */
    val dateTakenSec: Long,
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

    /** The most recently modified item directly in this folder, or (recursing) in a subfolder --
     * "first" as in what the gallery itself shows first, not MediaStore's raw query order, which
     * has no defined chronological meaning at all. */
    fun coverUri(): Uri? =
        items.maxByOrNull { it.dateModifiedSec }?.uri ?: children.values.firstNotNullOfOrNull { it.coverUri() }

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

/** Most recent [MediaItem.dateTakenSec] anywhere in this folder or its subfolders; 0 if empty. */
fun FolderNode.latestDateTakenSec(): Long {
    val ownMax = items.maxOfOrNull { it.dateTakenSec } ?: 0L
    val childMax = children.values.maxOfOrNull { it.latestDateTakenSec() } ?: 0L
    return maxOf(ownMax, childMax)
}

/** Total file size (bytes) of every item in this folder and every folder beneath it -- the
 * per-folder analog of [MediaItem.size], for the "Size" sort criterion. */
fun FolderNode.totalSizeBytes(): Long =
    items.sumOf { it.size } + children.values.sumOf { it.totalSizeBytes() }

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
    items.maxByOrNull { it.dateModifiedSec }?.uri
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

/** Every real folder path in this tree (this node included, the synthetic empty-path root
 * excluded) -- used to snapshot "which folders currently have media" as the baseline for the
 * backup settings' auto-include-future-folders toggle (see [com.elghayesh.gallerybackup.ui.settings.BackupViewModel]):
 * a folder path already in that baseline was one the user could see and manually choose to back
 * up at the moment the toggle was turned on, so it's never auto-included later; a path that shows
 * up in a later scan but wasn't in the baseline is a folder that gained media afterward. */
fun FolderNode.allFolderPathsRecursive(): Set<String> {
    val result = mutableSetOf<String>()
    if (path.isNotEmpty()) result += path
    children.values.forEach { result += it.allFolderPathsRecursive() }
    return result
}

/** Every folder in this tree (this one included) whose own [FolderNode.name] contains [query],
 * case-insensitively -- used by the gallery's search feature to find folders by name regardless
 * of where they sit in the tree. The synthetic root (empty [FolderNode.path]) is never itself a
 * match candidate, since it has no real name a user could search for. */
fun FolderNode.searchFolders(query: String): List<FolderNode> {
    val matches = mutableListOf<FolderNode>()
    if (path.isNotEmpty() && name.contains(query, ignoreCase = true)) matches.add(this)
    children.values.forEach { matches.addAll(it.searchFolders(query)) }
    return matches
}

/** Every [MediaItem] anywhere in this tree whose [MediaItem.displayName] contains [query],
 * case-insensitively -- the media half of the gallery's search feature. */
fun FolderNode.searchMedia(query: String): List<MediaItem> =
    allItemsRecursive().filter { it.displayName.contains(query, ignoreCase = true) }

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
