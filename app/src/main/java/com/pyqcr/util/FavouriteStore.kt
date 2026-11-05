package com.pyqcr.util

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pyqcr.ui.util.MediaStoreUtils
import java.io.File

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

    // ---------- Physical "Favourites" album (WeChat compatibility) ----------

    /**
     * WeChat's photo picker does NOT read MediaStore flags: it lists image
     * FILES grouped by bucket, so `IS_FAVORITE = 1` alone can never make a
     * photo appear in a WeChat "Favourites" album (browsers and galleries that
     * honour the column do see it). To make the same set visible everywhere,
     * every favourited image is also published as a real file under
     * `Pictures/PYQAlbum/Favourites/` (MediaStore insert + IS_PENDING clear +
     * MEDIA_SCANNER + notifyChange), and removed again when un-favourited.
     */
    private const val FAVOURITE_ALBUM_DIR = "PYQAlbum/Favourites"

    /**
     * Mirrors the favourite state of [uris] into the physical Favourites
     * album. Favourite = copy the image in (skipped when the copy is already
     * there); un-favourite = delete the copy. Must run on an IO thread.
     * Returns the number of copies created/removed.
     */
    fun syncFavouriteAlbum(
        context: Context,
        uris: List<String>,
        favourite: Boolean
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var changed = 0
        uris.forEach { uri ->
            try {
                val copyName = favouriteCopyName(context, uri) ?: return@forEach
                if (favourite) {
                    if (findFavouriteCopyUri(context, copyName) == null &&
                        insertFavouriteCopy(context, uri, copyName)
                    ) {
                        changed++
                    }
                } else if (deleteFavouriteCopy(context, copyName)) {
                    changed++
                }
            } catch (e: Exception) {
                // One bad row must not abort the rest of the batch.
            }
        }
        return changed
    }

    /**
     * Copies every image already flagged favourite (including flags set in the
     * system gallery before this feature existed) into the physical album.
     * Idempotent — existing copies are skipped. Run on an IO thread.
     */
    fun backfillFavouriteAlbum(context: Context): Int =
        syncFavouriteAlbum(context, loadFavouriteUris(context).toList(), true)

    /** Stable, collision-free name for the album copy of [sourceUri]. */
    private fun favouriteCopyName(context: Context, sourceUri: String): String? {
        val resolver = context.contentResolver
        var displayName: String? = null
        var mime: String? = null
        try {
            resolver.query(
                Uri.parse(sourceUri),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    displayName = c.getString(0)
                    mime = c.getString(1)
                }
            }
        } catch (e: Exception) {
        }
        if (displayName.isNullOrBlank()) displayName = "image.jpg"
        val dot = displayName!!.lastIndexOf('.')
        val ext = when {
            dot > 0 -> displayName!!.substring(dot)
            mime == "image/png" -> ".png"
            mime == "image/webp" -> ".webp"
            mime == "image/heic" || mime == "image/heif" -> ".heic"
            mime == "image/gif" -> ".gif"
            else -> ".jpg"
        }
        // MediaStore row id makes the name unique and lets an un-favourite
        // find the copy again even after an app restart (no prefs needed).
        val id = Uri.parse(sourceUri).lastPathSegment ?: return null
        return "fav_$id$ext"
    }

    private fun favouriteCopySelection(): String =
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"

    private fun findFavouriteCopyUri(context: Context, copyName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(copyName),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun insertFavouriteCopy(
        context: Context,
        sourceUri: String,
        copyName: String
    ): Boolean {
        val resolver = context.contentResolver
        val mime = try {
            resolver.query(
                Uri.parse(sourceUri),
                arrayOf(MediaStore.MediaColumns.MIME_TYPE), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        } ?: "image/jpeg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, copyName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$FAVOURITE_ALBUM_DIR"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.IS_FAVORITE, 1)
        }
        val dest = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return false

        val copied = try {
            resolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                resolver.openOutputStream(dest)?.use { output ->
                    input.copyTo(output)
                    true
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
        if (!copied) {
            try {
                resolver.delete(dest, null, null)
            } catch (e: Exception) {
            }
            return false
        }
        // Clear IS_PENDING, hand the real path to MediaScannerConnection and
        // notify — the steps that make third-party pickers (WeChat) see it.
        MediaStoreUtils.publishImage(context, dest)
        // A stale .nomedia in the folder would hide the whole album.
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                FAVOURITE_ALBUM_DIR
            )
            File(dir, ".nomedia").takeIf { it.exists() }?.delete()
        } catch (e: Exception) {
        }
        return true
    }

    private fun deleteFavouriteCopy(context: Context, copyName: String): Boolean {
        val copyUri = findFavouriteCopyUri(context, copyName) ?: return false
        return try {
            context.contentResolver.delete(copyUri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Favourite copies currently present in the physical album. */
    fun loadFavouriteAlbumNames(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val names = mutableSetOf<String>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                favouriteCopySelection(),
                arrayOf("%$FAVOURITE_ALBUM_DIR%"),
                null
            )?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { names += it }
            }
        } catch (e: Exception) {
        }
        return names
    }
}