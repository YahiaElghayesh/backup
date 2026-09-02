package com.elghayesh.gallerybackup.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.galleryPrefsStore by preferencesDataStore(name = "gallery_ui_preferences")

enum class ViewType { GRID, LIST }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class FolderSortOrder(val label: String) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    COUNT_DESC("Most items first"),
    COUNT_ASC("Fewest items first"),
}

/** One of a handful of preset accent colors -- deliberately not a full color picker, to keep this simple. */
enum class AccentColor(val seed: Long) {
    BLUE(0xFF1A73E8),
    TEAL(0xFF00897B),
    PURPLE(0xFF8E24AA),
    ORANGE(0xFFF4511E),
    GREEN(0xFF43A047),
    PINK(0xFFD81B60),
    RED(0xFFE53935),
    INDIGO(0xFF3949AB),
}

/** A custom folder tile: solid [colorSeed] background with [text] instead of a photo thumbnail. */
data class FolderCover(val text: String, val colorSeed: Long)

/**
 * Gallery display/browsing preferences: how folders look and sort, theming, and which
 * folders/items are excluded or hidden from view. Separate from [SettingsRepository],
 * which covers backup behavior -- this is "how the gallery looks", that is "what gets
 * backed up".
 */
class GalleryPreferencesRepository(private val context: Context) {

    private object Keys {
        val VIEW_TYPE = stringPreferencesKey("view_type")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val FOLDER_SORT = stringPreferencesKey("folder_sort")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val INCLUDED_FOLDERS = stringSetPreferencesKey("included_folders")
        val HIDDEN_FOLDERS = stringSetPreferencesKey("hidden_folders")
        val HIDDEN_MEDIA_IDS = stringSetPreferencesKey("hidden_media_ids")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val VIRTUAL_FOLDERS = stringSetPreferencesKey("virtual_folders")
        val FOLDER_COVERS_JSON = stringPreferencesKey("folder_covers_json")
        val FAVORITE_MEDIA_IDS = stringSetPreferencesKey("favorite_media_ids")
    }

    val viewType: Flow<ViewType> = context.galleryPrefsStore.data.map { prefs ->
        prefs[Keys.VIEW_TYPE]?.let { runCatching { ViewType.valueOf(it) }.getOrNull() } ?: ViewType.GRID
    }

    val gridColumns: Flow<Int> = context.galleryPrefsStore.data.map { prefs ->
        (prefs[Keys.GRID_COLUMNS] ?: 3).coerceIn(2, 5)
    }

    val themeMode: Flow<ThemeMode> = context.galleryPrefsStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    val accentColor: Flow<AccentColor> = context.galleryPrefsStore.data.map { prefs ->
        prefs[Keys.ACCENT_COLOR]?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() } ?: AccentColor.BLUE
    }

    val folderSort: Flow<FolderSortOrder> = context.galleryPrefsStore.data.map { prefs ->
        prefs[Keys.FOLDER_SORT]?.let { runCatching { FolderSortOrder.valueOf(it) }.getOrNull() } ?: FolderSortOrder.NAME_ASC
    }

    val excludedFolders: Flow<Set<String>> =
        context.galleryPrefsStore.data.map { it[Keys.EXCLUDED_FOLDERS] ?: emptySet() }

    /**
     * Folders pinned to the gallery's home page from the folder explorer. Purely additive and
     * flat -- each entry shows up as its own tile at the gallery root (with its full real
     * contents, unfiltered by this set), on top of whatever's already there. Pinning a subfolder
     * doesn't affect its parent's own pin state or vice versa; browsing into a folder normally
     * (whether reached via a pin or by drilling down for real) always shows everything inside it.
     */
    val includedFolders: Flow<Set<String>> =
        context.galleryPrefsStore.data.map { it[Keys.INCLUDED_FOLDERS] ?: emptySet() }

    val hiddenFolders: Flow<Set<String>> =
        context.galleryPrefsStore.data.map { it[Keys.HIDDEN_FOLDERS] ?: emptySet() }

    val hiddenMediaIds: Flow<Set<Long>> =
        context.galleryPrefsStore.data.map { prefs ->
            (prefs[Keys.HIDDEN_MEDIA_IDS] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
        }

    val showHidden: Flow<Boolean> =
        context.galleryPrefsStore.data.map { it[Keys.SHOW_HIDDEN] ?: false }

    val trashRetentionDays: Flow<Int> =
        context.galleryPrefsStore.data.map { (it[Keys.TRASH_RETENTION_DAYS] ?: 30).coerceIn(1, 365) }

    val virtualFolders: Flow<Set<String>> =
        context.galleryPrefsStore.data.map { it[Keys.VIRTUAL_FOLDERS] ?: emptySet() }

    val folderCovers: Flow<Map<String, FolderCover>> =
        context.galleryPrefsStore.data.map { parseFolderCovers(it[Keys.FOLDER_COVERS_JSON]) }

    val favoriteMediaIds: Flow<Set<Long>> =
        context.galleryPrefsStore.data.map { prefs ->
            (prefs[Keys.FAVORITE_MEDIA_IDS] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
        }

    suspend fun setViewType(type: ViewType) {
        context.galleryPrefsStore.edit { it[Keys.VIEW_TYPE] = type.name }
    }

    suspend fun setGridColumns(columns: Int) {
        context.galleryPrefsStore.edit { it[Keys.GRID_COLUMNS] = columns.coerceIn(2, 5) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.galleryPrefsStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAccentColor(color: AccentColor) {
        context.galleryPrefsStore.edit { it[Keys.ACCENT_COLOR] = color.name }
    }

    suspend fun setFolderSort(order: FolderSortOrder) {
        context.galleryPrefsStore.edit { it[Keys.FOLDER_SORT] = order.name }
    }

    suspend fun setFolderExcluded(path: String, excluded: Boolean) {
        context.galleryPrefsStore.edit { prefs ->
            val current = prefs[Keys.EXCLUDED_FOLDERS] ?: emptySet()
            prefs[Keys.EXCLUDED_FOLDERS] = if (excluded) current + path else current - path
        }
    }

    suspend fun setFolderHidden(path: String, hidden: Boolean) {
        context.galleryPrefsStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_FOLDERS] ?: emptySet()
            prefs[Keys.HIDDEN_FOLDERS] = if (hidden) current + path else current - path
        }
    }

    suspend fun setExcludedFolders(paths: Set<String>) {
        context.galleryPrefsStore.edit { it[Keys.EXCLUDED_FOLDERS] = paths }
    }

    /** Pins or unpins a single folder to the gallery's home page. See [includedFolders]. */
    suspend fun setFolderIncluded(path: String, included: Boolean) {
        context.galleryPrefsStore.edit { prefs ->
            val current = prefs[Keys.INCLUDED_FOLDERS] ?: emptySet()
            prefs[Keys.INCLUDED_FOLDERS] = if (included) current + path else current - path
        }
    }

    suspend fun setHiddenFolders(paths: Set<String>) {
        context.galleryPrefsStore.edit { it[Keys.HIDDEN_FOLDERS] = paths }
    }

    suspend fun setMediaHidden(id: Long, hidden: Boolean) {
        context.galleryPrefsStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_MEDIA_IDS] ?: emptySet()
            val idStr = id.toString()
            prefs[Keys.HIDDEN_MEDIA_IDS] = if (hidden) current + idStr else current - idStr
        }
    }

    suspend fun setShowHidden(show: Boolean) {
        context.galleryPrefsStore.edit { it[Keys.SHOW_HIDDEN] = show }
    }

    suspend fun setTrashRetentionDays(days: Int) {
        context.galleryPrefsStore.edit { it[Keys.TRASH_RETENTION_DAYS] = days.coerceIn(1, 365) }
    }

    suspend fun addVirtualFolder(path: String) {
        context.galleryPrefsStore.edit { prefs ->
            prefs[Keys.VIRTUAL_FOLDERS] = (prefs[Keys.VIRTUAL_FOLDERS] ?: emptySet()) + path
        }
    }

    /** Called once a virtual folder gets its first real file, so it stops being tracked as virtual. */
    suspend fun removeVirtualFolder(path: String) {
        context.galleryPrefsStore.edit { prefs ->
            prefs[Keys.VIRTUAL_FOLDERS] = (prefs[Keys.VIRTUAL_FOLDERS] ?: emptySet()) - path
        }
    }

    suspend fun setMediaFavorite(id: Long, favorite: Boolean) {
        context.galleryPrefsStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_MEDIA_IDS] ?: emptySet()
            val idStr = id.toString()
            prefs[Keys.FAVORITE_MEDIA_IDS] = if (favorite) current + idStr else current - idStr
        }
    }

    suspend fun setFolderCover(path: String, cover: FolderCover?) {
        context.galleryPrefsStore.edit { prefs ->
            val current = parseFolderCovers(prefs[Keys.FOLDER_COVERS_JSON]).toMutableMap()
            if (cover == null) current.remove(path) else current[path] = cover
            prefs[Keys.FOLDER_COVERS_JSON] = serializeFolderCovers(current)
        }
    }

    private fun parseFolderCovers(json: String?): Map<String, FolderCover> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val array = org.json.JSONArray(json)
            buildMap {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    put(obj.getString("path"), FolderCover(obj.getString("text"), obj.getLong("color")))
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun serializeFolderCovers(map: Map<String, FolderCover>): String {
        val array = org.json.JSONArray()
        for ((path, cover) in map) {
            array.put(
                org.json.JSONObject().apply {
                    put("path", path)
                    put("text", cover.text)
                    put("color", cover.colorSeed)
                },
            )
        }
        return array.toString()
    }
}
