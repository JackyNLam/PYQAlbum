package com.pyqcr.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistence for the AI Edit screen's image selections (first/source,
 * second/third reference images), shared by the album batch menu, the
 * image-detail "AI Edit" button, and the AI Edit screen itself.
 *
 * Stored in the "pyqcr_ai_edit_select" SharedPreferences (plain prefs — the
 * URIs are not sensitive and must survive the same way the album's other
 * selection prefs do).
 */
object AiEditSelectionStore {

    private const val PREFS = "pyqcr_ai_edit_select"
    private const val KEY_SOURCE_URIS = "ai_edit_source_uris"
    private const val KEY_TARGET_URIS = "ai_edit_target_uris"
    private const val KEY_THIRD_URIS = "ai_edit_third_uris"
    private const val KEY_SOURCE_AREAS = "ai_edit_source_areas"

    private val gson = Gson()

    /**
     * The per-image area selection (crop rect / brush strokes), keyed by URI.
     * Persisted as JSON so leaving AI Edit for another screen (or the process
     * being recreated) does not throw away hours of brushing.
     */
    fun loadSourceAreas(context: Context): Map<String, EditSelection> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE_AREAS, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, EditSelection>>() {}.type
            gson.fromJson<Map<String, EditSelection>>(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveSourceAreas(context: Context, areas: Map<String, EditSelection>) {
        val json = try {
            gson.toJson(areas)
        } catch (e: Exception) {
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SOURCE_AREAS, json).apply()
    }

    fun saveSourceArea(context: Context, uri: String, selection: EditSelection) {
        saveSourceAreas(context, loadSourceAreas(context) + (uri to selection))
    }

    fun removeSourceArea(context: Context, uri: String) {
        val areas = loadSourceAreas(context)
        if (uri in areas) saveSourceAreas(context, areas - uri)
    }

    fun loadSourceUris(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SOURCE_URIS, emptySet()) ?: emptySet()
    }

    fun saveSourceUris(context: Context, uris: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SOURCE_URIS, uris).apply()
    }

    fun loadTargetUris(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_TARGET_URIS, emptySet()) ?: emptySet()
    }

    fun saveTargetUris(context: Context, uris: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_TARGET_URIS, uris).apply()
    }

    fun loadThirdUris(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_THIRD_URIS, emptySet()) ?: emptySet()
    }

    fun saveThirdUris(context: Context, uris: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_THIRD_URIS, uris).apply()
    }

    /** Drop every stored selection (used by the AI Edit screen's "Clear All"). */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SOURCE_URIS)
            .remove(KEY_TARGET_URIS)
            .remove(KEY_THIRD_URIS)
            .remove(KEY_SOURCE_AREAS)
            .apply()
    }

    /**
     * Mark [uri] as a first-image source, removing it from the reference
     * roles if it was previously a second/third image. Used by the
     * image-detail screen's "AI Edit" button.
     */
    fun addSource(context: Context, uri: String) {
        val sources = (loadSourceUris(context) + uri)
            .minus(loadTargetUris(context))
            .minus(loadThirdUris(context))
        saveSourceUris(context, sources)
        saveTargetUris(context, loadTargetUris(context) - uri)
        saveThirdUris(context, loadThirdUris(context) - uri)
    }
}