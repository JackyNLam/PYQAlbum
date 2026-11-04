package com.pyqcr.util

import android.app.PendingIntent
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
     * PRIMARY (Android 11/12): set (true) or clear (false) the favourite flag
     * by writing IS_FAVORITE directly. Returns false when the provider rejects
     * the write. On Android 13+ the platform favourite-request dialog
     * ([createFavoriteRequest]) is preferred instead, because it makes the
     * media provider perform the write itself — that is the path OEM galleries
     * (HONOR "My favourites") sync from. Callers use this direct write on
     * Android 11/12, and as the fallback when the dialog cannot be built.
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
            val ok = updated && isFavourite(context, uri) == favourite
            if (ok) notifyFavouriteChanged(context, listOf(uri))
            ok
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Tells every MediaStore observer that a favourite flag changed. Galleries
     * (HONOR/Huawei "My favourites", Samsung, Google Photos) cache the column
     * after a media refresh, so an explicit notify on the row, the images
     * collection and the media authority makes them re-read it instead of
     * waiting for the next full scan.
     */
    fun notifyFavouriteChanged(context: Context, uris: List<String>) {
        if (!supported) return
        try {
            uris.forEach { context.contentResolver.notifyChange(Uri.parse(it), null) }
            context.contentResolver.notifyChange(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null
            )
            context.contentResolver.notifyChange(
                Uri.parse("content://${MediaStore.AUTHORITY}"), null
            )
        } catch (e: Exception) {
        }
    }

    /**
     * PRIMARY on Android 13+: builds the platform favourite request — one
     * confirmation dialog for [imageUris]; the media provider itself persists
     * IS_FAVORITE with system authority, and OEM galleries (HONOR/Huawei "My
     * favourites", Samsung) pick that up, unlike a silent third-party write.
     * Used for rows this app does NOT own as well. Returns the platform
     * PendingIntent so callers can build a launcher from it; null below
     * Android 13 (the favourite-request DIALOG itself is API 33+, even though
     * the IS_FAVORITE column exists from API 30) or on failure — callers then
     * use the direct write ([setFavourite]) or the WRITE-ACCESS request
     * ([createWriteAccessRequest]).
     */
    fun setMediaFavoriteStatus(
        context: Context,
        imageUris: List<Uri>,
        isFavorite: Boolean
    ): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            MediaStore.createFavoriteRequest(
                context.contentResolver,
                imageUris,
                isFavorite
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convenience wrapper for callers holding URI strings; delegates to
     * [setMediaFavoriteStatus] (the canonical favourite-request builder).
     */
    fun createFavoriteRequest(
        context: Context,
        uris: List<String>,
        favourite: Boolean
    ): IntentSender? = setMediaFavoriteStatus(
        context,
        uris.map { Uri.parse(it) },
        favourite
    )?.intentSender

    /**
     * Android 11/12 bridge for marking favourites on rows this app does not
     * own: builds the platform WRITE-ACCESS request (API 30+). The system shows
     * one dialog; once the user grants access, a direct [setFavourite] write on
     * those rows is persisted by the media provider even though the app is not
     * their owner — which is what other gallery apps read. Returns null on
     * older APIs / failure; callers then fall back to [setFavourite] alone
     * (persists only on rows this app owns).
     */
    fun createWriteAccessRequest(context: Context, uris: List<String>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            MediaStore.createWriteRequest(
                context.contentResolver,
                uris.map { Uri.parse(it) }
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