package com.pyqcr.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Platform "favourite" flag for gallery images, backed by the MediaStore
 * IS_FAVORITE column (Android 13 / API 33+). The flag lives in the media
 * provider, so OTHER apps that read MediaStore — WeChat's image picker,
 * Google Photos' Favourites album, … — can identify the marked images.
 */
object FavouriteStore {

    /** IS_FAVORITE only exists from Android 13 (API 33) onward. */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Whether the MediaStore row behind [uri] is currently marked favourite. */
    fun isFavourite(context: Context, uri: String): Boolean {
        if (!supported) return false
        return try {
            context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(MediaStore.MediaColumns.IS_FAVORITE),
                null, null, null
            )?.use { c ->
                c.moveToFirst() && c.getInt(0) == 1
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Set (true) or clear (false) the favourite flag by writing IS_FAVORITE
     * directly. Returns false when the provider rejects the write — the caller
     * then falls back to the platform [createFavoriteRequest] dialog, which
     * shows a system prompt the user approves once.
     */
    fun setFavourite(context: Context, uri: String, favourite: Boolean): Boolean {
        if (!supported) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_FAVORITE, if (favourite) 1 else 0)
            }
            context.contentResolver.update(Uri.parse(uri), values, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Platform favourite request (Android 13+): shows the system "mark as
     * favourite?" permission/confirmation dialog for [uris] in ONE prompt —
     * the reliable path for photos the app does not own, where a direct
     * IS_FAVORITE write is not allowed. Returns null on older APIs / failure.
     */
    fun createFavoriteRequest(
        context: Context,
        uris: List<String>,
        favourite: Boolean
    ): IntentSender? {
        if (!supported) return null
        return try {
            MediaStore.createFavoriteRequest(
                context.contentResolver,
                uris.map { Uri.parse(it) }.toMutableList(),
                favourite
            ).intentSender
        } catch (e: Exception) {
            null
        }
    }

    /** Content URIs of every image currently marked favourite in MediaStore. */
    fun loadFavouriteUris(context: Context): Set<String> {
        if (!supported) return emptySet()
        val result = mutableSetOf<String>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.MediaColumns.IS_FAVORITE} = 1",
                null, null
            )?.use { c ->
                val idColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (c.moveToNext()) {
                    result.add(
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            c.getLong(idColumn)
                        ).toString()
                    )
                }
            }
        } catch (e: Exception) {
        }
        return result
    }
}