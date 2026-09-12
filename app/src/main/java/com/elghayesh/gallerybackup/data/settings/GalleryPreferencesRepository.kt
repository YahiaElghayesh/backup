package com.elghayesh.gallerybackup.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.galleryPrefsStore by preferencesDataStore(name = "gallery_ui_preferences")

enum class ViewType { GRID, LIST }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What a folder's (or the whole gallery's) contents are ordered by -- direction is a separate
 * field on [FolderSortSetting], not baked into the criterion itself, so the sort dialog can show
 * criterion and direction as two independent radio groups. [PATH] only meaningfully orders
 * folders relative to each other; media items are always listed already scoped to one folder, so
 * they all share the same path and [PATH] is a no-op for them (falls back to [NAME] as a stable
 * tie-break). [DATE_TAKEN] uses [com.elghayesh.gallerybackup.data.media.MediaItem.dateTakenSec]. */
enum class SortCriterion(val label: String) {
    NAME("Name"),
    PATH("Path"),
    SIZE("Size"),
    LAST_MODIFIED("Last modified"),
    DATE_TAKEN("Date taken"),
    RANDOM("Random"),
}

/** A sort choice: [criterion] + [ascending], plus [randomSeed] which only matters for
 * [SortCriterion.RANDOM] -- regenerated each time Random is (re)confirmed from the sort dialog, so
 * the shuffled order stays stable across recompositions/navigations until the user deliberately
 * re-sorts, rather than reshuffling on every read. */
data class FolderSortSetting(val criterion: SortCriterion, val ascending: Boolean, val randomSeed: Long = 0L)

/** One of a handful of preset accent colors -- deliberately not a full color picker, to keep this
 * simple. Deliberately muted/desaturated rather than the bright, saturated tones a picker would
 * default to -- easier to stare at across a whole grid of folder covers. */
enum class AccentColor(val seed: Long) {
    BLUE(0xFF3B6FA0),
    TEAL(0xFF3F7C74),
    PURPLE(0xFF7C5295),
    ORANGE(0xFFC1652E),
    GREEN(0xFF4F7A52),
    PINK(0xFFB05C7A),
    RED(0xFFB0413E),
    INDIGO(0xFF4A5586),
    BROWN(0xFF7B5E4A),
    SLATE(0xFF5C6B73),
    OLIVE(0xFF6E7B4F),
    MUSTARD(0xFFB08D3F),
    MAROON(0xFF7A3B4A),
    NAVY(0xFF34495E),
    PLUM(0xFF6B4C6E),
    GRAY(0xFF5A5F66),
    WHITE(0xFFF2F1EF),
    BLACK(0xFF1C1C1C),
}

/** A custom folder tile, overriding the default (the folder's own first photo, recursing into
 * subfolders if it's empty). Either plain [Text] over a solid background color, or a specific
 * [Photo] the user picked from the folder's own items. */
sealed class FolderCover {
    /** [sizeScale] is the user's chosen starting size (1f = the tile's normal default size) --
     * the text still shrinks further from there if it doesn't fit, it just starts bigger or
     * smaller depending on preference. [wrapText] false (the default) prefers shrinking the font
     * to fit on one line over wrapping to a second one; true allows a normal wrap onto a second
     * line at the chosen size instead of shrinking for it. */
    data class Text(
        val text: String,
        val colorSeed: Long,
        val sizeScale: Float = 1f,
        val bold: Boolean = false,
        val wrapText: Boolean = false,
    ) : FolderCover()
    data class Photo(val uri: String) : FolderCover()
}

/** What a folder's media listing is split into runs by, with a small label + line between each
 * run. [NONE] means "don't group" -- a folder simply absent from
 * [GalleryPreferencesRepository.folderGroupSettings] behaves the same as [NONE], so opting back
 * out removes its entry entirely rather than storing an explicit "off". [LAST_MODIFIED_DAILY]/
 * [LAST_MODIFIED_MONTHLY] group by [com.elghayesh.gallerybackup.data.media.MediaItem.dateModifiedSec];
 * [DATE_TAKEN_DAILY]/[DATE_TAKEN_MONTHLY] by [com.elghayesh.gallerybackup.data.media.MediaItem.dateTakenSec].
 * [FILE_TYPE] splits photos from videos; [EXTENSION] splits by the file's own extension (e.g.
 * "jpg", "mp4"). */
enum class GroupCriterion(val label: String) {
    NONE("Do not group files"),
    LAST_MODIFIED_DAILY("Last modified (daily)"),
    LAST_MODIFIED_MONTHLY("Last modified (monthly)"),
    DATE_TAKEN_DAILY("Date taken (daily)"),
    DATE_TAKEN_MONTHLY("Date taken (monthly)"),
    FILE_TYPE("File type"),
    EXTENSION("Extension"),
}

/** A folder's own group-by choice -- opt-in per folder, since grouping only makes sense for a
 * folder with enough of a spread in whatever it's grouped by
 * to matter. [ascending] orders the runs themselves: oldest-group-first for the date criteria,
 * A-Z for [GroupCriterion.FILE_TYPE]/[GroupCriterion.EXTENSION]'s labels. */
data class FolderGroupSetting(val criterion: GroupCriterion, val ascending: Boolean)

/**
 * Gallery display/browsing preferences: how folders look and sort, theming, and which
 * folders/items are excluded or hidden from view. Separate from [SettingsRepository],
 * which covers backup behavior -- this is "how the gallery looks", that is "what gets
 * backed up".
 */
class GalleryPreferencesRepository(private val context: Context) {

    private object Keys {
        /** Legacy single view-type key, from before folder and media view types were split. Still
         * read as the fallback default for both new keys, so an existing install's choice carries over. */
        val VIEW_TYPE = stringPreferencesKey("view_type")
        val FOLDER_VIEW_TYPE = stringPreferencesKey("folder_view_type")
        val MEDIA_VIEW_TYPE = stringPreferencesKey("media_view_type")
        /** Column count for folder tiles. Key name kept as "grid_columns" for compatibility with
         * installs from before folder and media tile sizes were split into separate settings. */
        val FOLDER_GRID_COLUMNS = intPreferencesKey("grid_columns")
        val MEDIA_GRID_COLUMNS = intPreferencesKey("media_grid_columns")
        val FOLDER_ROW_SIZE = intPreferencesKey("folder_row_size")
        val MEDIA_ROW_SIZE = intPreferencesKey("media_row_size")
        /** List-view thumbnail WIDTH (dp), independent of [FOLDER_ROW_SIZE]/[MEDIA_ROW_SIZE]
         * (which is the thumbnail's height) -- together these let a list-row thumbnail be a
         * rectangle instead of always a square. */
        val FOLDER_THUMBNAIL_WIDTH = intPreferencesKey("folder_thumbnail_width")
        val MEDIA_THUMBNAIL_WIDTH = intPreferencesKey("media_thumbnail_width")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val FOLDER_SORT_CRITERION = stringPreferencesKey("folder_sort_criterion")
        val FOLDER_SORT_ASCENDING = booleanPreferencesKey("folder_sort_ascending")
        val FOLDER_SORT_RANDOM_SEED = longPreferencesKey("folder_sort_random_seed")
        val FOLDER_SORT_OVERRIDES_JSON = stringPreferencesKey("folder_sort_overrides_json")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val INCLUDED_FOLDERS = stringSetPreferencesKey("included_folders")
        val HIDDEN_FOLDERS = stringSetPreferencesKey("hidden_folders")
        val HIDDEN_MEDIA_IDS = stringSetPreferencesKey("hidden_media_ids")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val VIRTUAL_FOLDERS = stringSetPreferencesKey("virtual_folders")
        val FOLDER_COVERS_JSON = stringPreferencesKey("folder_covers_json")
        val FAVORITE_MEDIA_IDS = stringSetPreferencesKey("favorite_media_ids")
        val PIN_CONTENT_TO_BOTTOM = booleanPreferencesKey("pin_content_to_bottom")
        val FOLDER_GROUP_SETTINGS_JSON = stringPreferencesKey("folder_group_settings_json")
    }

    private fun legacyViewType(prefs: androidx.datastore.preferences.core.Preferences): ViewType? =
        prefs[Keys.VIEW_TYPE]?.let { runCatching { ViewType.valueOf(it) }.getOrNull() }

    /** Grid vs. list for folder tiles -- independent of [mediaViewType]. */
    val folderViewType: Flow<ViewType> = context.galleryPrefsStore.data.map { prefs ->
        prefs[Keys.FOLDER_VIEW_TYPE]?.let { runCatching { ViewType.valueOf(it) }.getOrNull() }
            ?: legacyViewType(prefs) ?: ViewType.GRID
    }

    /** Grid vs. list for photo/video tiles -- independent of [folderViewType]. */
    val mediaViewType: Flow<ViewType> = context.galleryPrefsStore.data.map { prefs ->
        prefs[Keys.MEDIA_VIEW_TYPE]?.let { runCatching { ViewType.valueOf(it) }.getOrNull() }
            ?: legacyViewType(prefs) ?: ViewType.GRID
    }

    /** How many folder tiles sit across the gallery's width -- independent of [mediaGridColumns]. */
    val folderGridColumns: Flow<Int> = context.galleryPrefsStore.data.map { prefs ->
        (prefs[Keys.FOLDER_GRID_COLUMNS] ?: 3).coerceIn(2, 6)
    }

    /** How many photo/video tiles sit across the gallery's width -- independent of [folderGridColumns]. */
    val mediaGridColumns: Flow<Int> = context.galleryPrefsStore.data.map { prefs ->
        (prefs[Keys.MEDIA_GRID_COLUMNS] ?: 3).coerceIn(2, 6)
    }

    /** List-view row thumbnail HEIGHT (dp) for folder rows -- independent of [mediaRowSize], the grid-view analog of [folderGridColumns]. */
    val folderRowSize: Flow<Int> = context.galleryPrefsStore.data.map { prefs ->
        (prefs[Keys.FOLDER_ROW_SIZE] ?: 48).coerceIn(40, 112)
    }

    /** List-view row thumbnail HEIGHT (dp) for photo/video rows -- independent of [folderRowSize]. */
    val mediaRowSize: Flow<Int> = context.galleryPrefsStore.data.map { prefs ->
        (prefs[Keys.MEDIA_ROW_SIZE] ?: 48).coerceIn(40, 112)
    }

    /** List-view row thumbnail WIDTH (dp) for folder rows -- independent of [folderRowSize] (its
     * height), so the thumbnail can be a rectangle rather than always a square. */
    val folderThumbnailWidth: Flow<Int> = context.galleryPrefsStore.data.map { prefs ->
        (prefs[Keys.FOLDER_THUMBNAIL_WIDTH] ?: 48).coerceIn(40, 112)
    }

    /** List-view row thumbnail WIDTH (dp) for photo/video rows -- independent of [mediaRowSize] (its height). */
    val mediaThumbnailWidth: Flow<Int> = context.galleryPrefsStore.data.map { prefs ->
        (prefs[Keys.MEDIA_THUMBNAIL_WIDTH] ?: 48).coerceIn(40, 112)
    }

    val themeMode: Flow<ThemeMode> = context.galleryPrefsStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    val accentColor: Flow<AccentColor> = context.galleryPrefsStore.data.map { prefs ->
        prefs[Keys.ACCENT_COLOR]?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() } ?: AccentColor.BLUE
    }

    /** The global default sort, used by any folder without its own entry in [folderSortOverrides]. */
    val folderSort: Flow<FolderSortSetting> = context.galleryPrefsStore.data.map { prefs ->
        val criterion = prefs[Keys.FOLDER_SORT_CRITERION]?.let { runCatching { SortCriterion.valueOf(it) }.getOrNull() }
            ?: SortCriterion.NAME
        FolderSortSetting(
            criterion = criterion,
            ascending = prefs[Keys.FOLDER_SORT_ASCENDING] ?: true,
            randomSeed = prefs[Keys.FOLDER_SORT_RANDOM_SEED] ?: 0L,
        )
    }

    /** Per-folder sort overrides, keyed by folder path (root is "") -- set via the sort dialog's
     * "use for this folder only" checkbox. A folder absent from this map uses [folderSort], the
     * global default, with no ancestor-cascading in between: each folder's own choice is either
     * exactly this map's entry for it, or exactly the global default, nothing in between. */
    val folderSortOverrides: Flow<Map<String, FolderSortSetting>> =
        context.galleryPrefsStore.data.map { parseFolderSortOverrides(it[Keys.FOLDER_SORT_OVERRIDES_JSON]) }

    /**
     * Folders pinned to the gallery's home page from the folder explorer. A flat set -- pinning a
     * subfolder doesn't affect its parent's own pin state or vice versa -- but not purely additive:
     * a pinned folder is *promoted* out of its real parent's own listing (wherever that parent is
     * shown, at any depth) and surfaces instead as its own tile at the gallery root, so it isn't
     * shown twice. Its own contents, once you open it, are still shown in full either way. Before
     * anything's ever been pinned (this set is empty), the root falls back to showing its real
     * top-level folders as usual; the moment it has an entry, the root becomes a pure opt-in list
     * -- only pinned folders show there, nothing appears just for being a real top-level folder.
     */
    val includedFolders: Flow<Set<String>> =
        context.galleryPrefsStore.data.map { it[Keys.INCLUDED_FOLDERS] ?: emptySet() }

    /**
     * Folders hidden from the gallery everywhere. Also folds in the legacy [Keys.EXCLUDED_FOLDERS]
     * key from an older, separate "exclude from gallery" mechanism -- so a folder hidden that way
     * (including from a bug during earlier testing where an attempted uncheck silently wrote here
     * instead of visibly failing) shows up as checked in the explorer's Hide checkbox and can be
     * unhidden the normal way, instead of staying invisibly excluded with no UI to undo it.
     */
    val hiddenFolders: Flow<Set<String>> =
        context.galleryPrefsStore.data.map { (it[Keys.HIDDEN_FOLDERS] ?: emptySet()) + (it[Keys.EXCLUDED_FOLDERS] ?: emptySet()) }

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

    /** When a folder/media listing doesn't fill the screen, anchor it to the bottom of the
     * viewport instead of the top -- doesn't change sort order, just where a short list sits, so
     * items land closer to a thumb on a tall screen. */
    val pinContentToBottom: Flow<Boolean> =
        context.galleryPrefsStore.data.map { it[Keys.PIN_CONTENT_TO_BOTTOM] ?: false }

    suspend fun setPinContentToBottom(value: Boolean) {
        context.galleryPrefsStore.edit { it[Keys.PIN_CONTENT_TO_BOTTOM] = value }
    }

    /** Per-folder group-by choices, keyed by folder path. A folder absent from this map is
     * ungrouped -- see [FolderGroupSetting]. */
    val folderGroupSettings: Flow<Map<String, FolderGroupSetting>> =
        context.galleryPrefsStore.data.map { parseFolderGroupSettings(it[Keys.FOLDER_GROUP_SETTINGS_JSON]) }

    /** Sets [path]'s group-by choice, or clears it entirely when [setting]'s criterion is
     * [GroupCriterion.NONE] -- mirroring the previous simple on/off toggle's behavior of just
     * removing the folder from the set rather than persisting an explicit "off" entry. */
    suspend fun setFolderGroupSetting(path: String, setting: FolderGroupSetting) {
        context.galleryPrefsStore.edit { prefs ->
            val current = parseFolderGroupSettings(prefs[Keys.FOLDER_GROUP_SETTINGS_JSON]).toMutableMap()
            if (setting.criterion == GroupCriterion.NONE) current.remove(path) else current[path] = setting
            prefs[Keys.FOLDER_GROUP_SETTINGS_JSON] = serializeFolderGroupSettings(current)
        }
    }

    suspend fun setFolderViewType(type: ViewType) {
        context.galleryPrefsStore.edit { it[Keys.FOLDER_VIEW_TYPE] = type.name }
    }

    suspend fun setMediaViewType(type: ViewType) {
        context.galleryPrefsStore.edit { it[Keys.MEDIA_VIEW_TYPE] = type.name }
    }

    suspend fun setFolderGridColumns(columns: Int) {
        context.galleryPrefsStore.edit { it[Keys.FOLDER_GRID_COLUMNS] = columns.coerceIn(2, 6) }
    }

    suspend fun setMediaGridColumns(columns: Int) {
        context.galleryPrefsStore.edit { it[Keys.MEDIA_GRID_COLUMNS] = columns.coerceIn(2, 6) }
    }

    suspend fun setFolderRowSize(sizeDp: Int) {
        context.galleryPrefsStore.edit { it[Keys.FOLDER_ROW_SIZE] = sizeDp.coerceIn(40, 112) }
    }

    suspend fun setMediaRowSize(sizeDp: Int) {
        context.galleryPrefsStore.edit { it[Keys.MEDIA_ROW_SIZE] = sizeDp.coerceIn(40, 112) }
    }

    suspend fun setFolderThumbnailWidth(widthDp: Int) {
        context.galleryPrefsStore.edit { it[Keys.FOLDER_THUMBNAIL_WIDTH] = widthDp.coerceIn(40, 112) }
    }

    suspend fun setMediaThumbnailWidth(widthDp: Int) {
        context.galleryPrefsStore.edit { it[Keys.MEDIA_THUMBNAIL_WIDTH] = widthDp.coerceIn(40, 112) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.galleryPrefsStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAccentColor(color: AccentColor) {
        context.galleryPrefsStore.edit { it[Keys.ACCENT_COLOR] = color.name }
    }

    suspend fun setFolderSort(setting: FolderSortSetting) {
        context.galleryPrefsStore.edit { prefs ->
            prefs[Keys.FOLDER_SORT_CRITERION] = setting.criterion.name
            prefs[Keys.FOLDER_SORT_ASCENDING] = setting.ascending
            prefs[Keys.FOLDER_SORT_RANDOM_SEED] = setting.randomSeed
        }
    }

    /** Sets [path]'s own sort, overriding the global default for just that exact folder ("use for
     * this folder only" in the sort dialog). Also clears any previous override at that path. */
    suspend fun setFolderSortOverride(path: String, setting: FolderSortSetting) {
        context.galleryPrefsStore.edit { prefs ->
            val current = parseFolderSortOverrides(prefs[Keys.FOLDER_SORT_OVERRIDES_JSON]).toMutableMap()
            current[path] = setting
            prefs[Keys.FOLDER_SORT_OVERRIDES_JSON] = serializeFolderSortOverrides(current)
        }
    }

    /** Clears any sort override at exactly [path] -- used when the new choice is "all folders", so
     * the folder the user made the choice from immediately reflects the new global default instead
     * of still being shadowed by whatever override it had before. */
    suspend fun clearFolderSortOverride(path: String) {
        context.galleryPrefsStore.edit { prefs ->
            val current = parseFolderSortOverrides(prefs[Keys.FOLDER_SORT_OVERRIDES_JSON]).toMutableMap()
            if (current.remove(path) != null) {
                prefs[Keys.FOLDER_SORT_OVERRIDES_JSON] = serializeFolderSortOverrides(current)
            }
        }
    }

    /** Hides or unhides one folder. Unhiding always clears it from both [hiddenFolders]'s keys. */
    suspend fun setFolderHidden(path: String, hidden: Boolean) {
        context.galleryPrefsStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_FOLDERS] ?: emptySet()
            prefs[Keys.HIDDEN_FOLDERS] = if (hidden) current + path else current - path
            if (!hidden) {
                val excluded = prefs[Keys.EXCLUDED_FOLDERS] ?: emptySet()
                if (path in excluded) prefs[Keys.EXCLUDED_FOLDERS] = excluded - path
            }
        }
    }

    /** Pins or unpins a single folder to the gallery's home page. See [includedFolders]. */
    suspend fun setFolderIncluded(path: String, included: Boolean) {
        context.galleryPrefsStore.edit { prefs ->
            val current = prefs[Keys.INCLUDED_FOLDERS] ?: emptySet()
            prefs[Keys.INCLUDED_FOLDERS] = if (included) current + path else current - path
        }
    }

    /** Clears every hidden folder, including any legacy [Keys.EXCLUDED_FOLDERS] entries. */
    suspend fun unhideAllFolders() {
        context.galleryPrefsStore.edit { prefs ->
            prefs[Keys.HIDDEN_FOLDERS] = emptySet()
            prefs[Keys.EXCLUDED_FOLDERS] = emptySet()
        }
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
                    // Installs from before photo covers existed never wrote a "type" -- those
                    // entries are all text covers, so that's the default when it's missing.
                    val cover = when (obj.optString("type", "text")) {
                        "photo" -> FolderCover.Photo(obj.getString("uri"))
                        else -> FolderCover.Text(
                            obj.getString("text"),
                            obj.getLong("color"),
                            obj.optDouble("sizeScale", 1.0).toFloat(),
                            obj.optBoolean("bold", false),
                            obj.optBoolean("wrapText", false),
                        )
                    }
                    put(obj.getString("path"), cover)
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
                    when (cover) {
                        is FolderCover.Text -> {
                            put("type", "text")
                            put("text", cover.text)
                            put("color", cover.colorSeed)
                            put("sizeScale", cover.sizeScale.toDouble())
                            put("bold", cover.bold)
                            put("wrapText", cover.wrapText)
                        }
                        is FolderCover.Photo -> {
                            put("type", "photo")
                            put("uri", cover.uri)
                        }
                    }
                },
            )
        }
        return array.toString()
    }

    private fun parseFolderSortOverrides(json: String?): Map<String, FolderSortSetting> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val array = org.json.JSONArray(json)
            buildMap {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val criterion = runCatching { SortCriterion.valueOf(obj.getString("criterion")) }.getOrNull() ?: continue
                    put(
                        obj.getString("path"),
                        FolderSortSetting(criterion, obj.getBoolean("ascending"), obj.optLong("randomSeed", 0L)),
                    )
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun serializeFolderSortOverrides(map: Map<String, FolderSortSetting>): String {
        val array = org.json.JSONArray()
        for ((path, setting) in map) {
            array.put(
                org.json.JSONObject().apply {
                    put("path", path)
                    put("criterion", setting.criterion.name)
                    put("ascending", setting.ascending)
                    put("randomSeed", setting.randomSeed)
                },
            )
        }
        return array.toString()
    }

    private fun parseFolderGroupSettings(json: String?): Map<String, FolderGroupSetting> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val array = org.json.JSONArray(json)
            buildMap {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val criterion = runCatching { GroupCriterion.valueOf(obj.getString("criterion")) }.getOrNull() ?: continue
                    put(obj.getString("path"), FolderGroupSetting(criterion, obj.getBoolean("ascending")))
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun serializeFolderGroupSettings(map: Map<String, FolderGroupSetting>): String {
        val array = org.json.JSONArray()
        for ((path, setting) in map) {
            array.put(
                org.json.JSONObject().apply {
                    put("path", path)
                    put("criterion", setting.criterion.name)
                    put("ascending", setting.ascending)
                },
            )
        }
        return array.toString()
    }
}
