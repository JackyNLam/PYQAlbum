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

    /**
     * The MediaStore IS_FAVORITE column exists from Android 11 (API 30) — the
     * system favourite-request DIALOG (createFavoriteRequest) itself needs
     * Android 13 (API 33) and is guarded separately inside
     * [createFavoriteRequest], so callers can fall back to the direct
     * (verified) write on API 30-32.
     */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

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
     * FALLBACK ONLY — set (true) or clear (false) the favourite flag by writing
     * IS_FAVORITE directly. Returns false when the provider rejects the write.
     * Direct writes only persist on rows this app owns, so callers should use
     * [createFavoriteRequest] (the platform dialog, which the media provider
     * honours for ANY row) as the primary marking method and call this only
     * when that request cannot be built.
     */
    fun setFavourite(context: Context, uri: String, favourite: Boolean): Boolean {
        if (!supported) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_FAVORITE, if (favourite) 1 else 0)
            }
            val updated = context.contentResolver.update(Uri.parse(uri), values, null, null) > 0
            // Some providers report a successful update without persisting the
            // flag on rows the app does not own — verify the column actually
            // changed so the caller falls back to the platform request dialog
            // (createFavoriteRequest) instead of silently "succeeding".
            updated && isFavourite(context, uri) == favourite
        } catch (e: Exception) {
            false
        }
    }

    /**
     * PRIMARY marking method (Android 13+): builds the platform favourite
     * request. The system shows ONE confirmation dialog for [uris]; on approval
     * the media provider itself persists IS_FAVORITE — unlike a direct write,
     * this works for rows this app does NOT own (e.g. photos taken by other
     * apps), which is exactly what other gallery apps read. Returns null on
     * older APIs / failure — the caller may then fall back to [setFavourite].
     */
    fun createFavoriteRequest(
        context: Context,
        uris: List<String>,
        favourite: Boolean
    ): IntentSender? {
        // The favourite-request DIALOG itself is Android 13+ (API 33), even
        // though the IS_FAVORITE column exists from API 30 — on Android 11/12
        // callers must fall back to the direct verified write ([setFavourite]).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
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