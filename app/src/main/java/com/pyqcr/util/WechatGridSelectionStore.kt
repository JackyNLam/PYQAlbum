package com.pyqcr.util

import android.content.Context

/**
 * Photos handed to the WeChat Grid screen from another view.
 *
 * The folder view's batch menu ("WeChat Grid") and the viewer's swipe-up bar
 * both store the selection here and navigate. The FIRST photo becomes the
 * center tile, the rest become hidden photos in that order. Persisted (not
 * in-memory) so process death between the two screens keeps the selection.
 */
object WechatGridSelectionStore {

    private const val PREFS = "pyqcr_wechat_grid_select"
    private const val KEY_CENTER = "wechat_grid_center"
    private const val KEY_HIDDEN = "wechat_grid_hidden"
    private const val SEPARATOR = "\n"

    data class Selection(val center: String, val hidden: List<String>)

    fun save(context: Context, uris: List<String>) {
        val center = uris.firstOrNull() ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CENTER, center)
            .putString(KEY_HIDDEN, uris.drop(1).joinToString(SEPARATOR))
            .apply()
    }

    fun load(context: Context): Selection? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val center = prefs.getString(KEY_CENTER, null)?.takeIf { it.isNotBlank() } ?: return null
        val hidden = prefs.getString(KEY_HIDDEN, "")
            .orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
        return Selection(center, hidden)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CENTER)
            .remove(KEY_HIDDEN)
            .apply()
    }
}