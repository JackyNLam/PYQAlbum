package com.pyqcr.util

import android.content.Context

/**
 * The images the user selected in the folder view before opening Image Edit.
 *
 * Selection happens in the batch (⋮) menu — "Edit images" stores the URIs here
 * and navigates. Image Edit reads the list on creation, so its picker and its
 * "add layer" picker only ever offer those images. Persisted (not in-memory) so
 * process death between the folder view and the editor keeps the selection.
 */
object ImageEditSelectionStore {

    private const val PREFS = "pyqcr_image_edit_select"
    private const val KEY_URIS = "image_edit_selected_uris"
    private const val SEPARATOR = "\n"

    fun save(context: Context, uris: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URIS, uris.joinToString(SEPARATOR))
            .apply()
    }

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URIS, null) ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_URIS)
            .apply()
    }
}