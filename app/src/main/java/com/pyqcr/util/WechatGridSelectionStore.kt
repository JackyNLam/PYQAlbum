package com.pyqcr.util

import android.content.Context

/**
 * The photo pool the WeChat Grid screen picks from.
 *
 * The folder view's batch menu ("WeChat Grid") and the viewer's swipe-up bar
 * *add* their selection here instead of handing a one-shot list over; the pool
 * survives navigation and app restarts, so the next batch simply grows it.
 * Inside the WeChat Grid screen the center/hidden pickers list this pool and
 * nothing else — the rest of the gallery is never offered there.
 */
object WechatGridSelectionStore {

    private const val PREFS = "pyqcr_wechat_grid_select"
    private const val KEY_POOL = "wechat_grid_pool"
    private const val SEPARATOR = "\n"

    /** Append [uris] to the pool, keeping the existing order and dropping duplicates. */
    fun add(context: Context, uris: Collection<String>) {
        val pool = loadPool(context).toMutableList()
        uris.forEach { if (it.isNotBlank() && it !in pool) pool += it }
        savePool(context, pool)
    }

    /** The pool in the order the photos were added. */
    fun loadPool(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_POOL, "")
            .orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }

    /** Persist exactly [uris] as the pool (used to drop stale entries). */
    fun savePool(context: Context, uris: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POOL, uris.joinToString(SEPARATOR))
            .apply()
    }

    fun remove(context: Context, uri: String) {
        savePool(context, loadPool(context).filterNot { it == uri })
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_POOL)
            .apply()
    }
}