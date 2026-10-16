package com.pyqcr.ai

import android.content.Context

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