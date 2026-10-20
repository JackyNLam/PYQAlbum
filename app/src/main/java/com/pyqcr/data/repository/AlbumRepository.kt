package com.pyqcr.data.repository

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.pyqcr.data.db.AppDatabase
import com.pyqcr.data.db.FolderInfo
import com.pyqcr.data.db.ImageEntity
import com.pyqcr.data.model.ImageItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/** A media row (image or video) sitting in the system recycle bin (API 30+ trash). */
data class TrashedMedia(
    val uri: String,
    val displayName: String,
    val folderName: String,
    val isVideo: Boolean,
    val dateAdded: Long
)

class AlbumRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val imageDao = database.imageDao()
    private val tagDao = database.tagDao()

    fun getAllImages(): Flow<List<ImageItem>> {
        return imageDao.getAllImages().map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getImagesByFolder(folderName: String): Flow<List<ImageItem>> {
        return imageDao.getImagesByFolder(folderName).map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getAllImagesDateAsc(): Flow<List<ImageItem>> {
        return imageDao.getAllImagesDateAsc().map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getImagesByFolderDateAsc(folderName: String): Flow<List<ImageItem>> {
        return imageDao.getImagesByFolderDateAsc(folderName).map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getImagesByTag(tagName: String): Flow<List<ImageItem>> {
        return imageDao.getImagesByTag(tagName).map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getImagesByRating(min: Float, max: Float): Flow<List<ImageItem>> {
        return imageDao.getImagesByRating(min, max).map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getImagesSortedByUserRatingDesc(): Flow<List<ImageItem>> {
        return imageDao.getImagesSortedByUserRatingDesc().map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getImagesSortedByAiScoreDesc(): Flow<List<ImageItem>> {
        return imageDao.getImagesSortedByAiScoreDesc().map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getImagesSortedByUserRatingAsc(): Flow<List<ImageItem>> {
        return imageDao.getImagesSortedByUserRatingAsc().map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getImagesSortedByAiScoreAsc(): Flow<List<ImageItem>> {
        return imageDao.getImagesSortedByAiScoreAsc().map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getAllFoldersWithInfo(): Flow<List<FolderInfo>> {
        return imageDao.getAllFoldersWithInfo()
    }

    fun getAllFoldersWithInfoByName(): Flow<List<FolderInfo>> {
        return imageDao.getAllFoldersWithInfoByName()
    }

    fun getAllFoldersWithInfoByCount(): Flow<List<FolderInfo>> {
        return imageDao.getAllFoldersWithInfoByCount()
    }

    fun getAllFoldersWithInfoByOldest(): Flow<List<FolderInfo>> {
        return imageDao.getAllFoldersWithInfoByOldest()
    }

    fun getAllFoldersWithInfoByCountAsc(): Flow<List<FolderInfo>> {
        return imageDao.getAllFoldersWithInfoByCountAsc()
    }

    fun getAllFoldersWithInfoByNameDesc(): Flow<List<FolderInfo>> {
        return imageDao.getAllFoldersWithInfoByNameDesc()
    }

    fun getAllFolders(): Flow<List<String>> {
        return imageDao.getAllFolders()
    }

    fun getAllImagesByNameAsc(): Flow<List<ImageItem>> {
        return imageDao.getAllImagesByNameAsc().map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    fun getAllImagesByNameDesc(): Flow<List<ImageItem>> {
        return imageDao.getAllImagesByNameDesc().map { entities ->
            entities.map { it.toImageItem() }
        }
    }

    suspend fun getImageByUri(uri: String): ImageItem? {
        return imageDao.getImageByUri(uri)?.toImageItem()
    }

    suspend fun updateRating(uri: String, rating: Float) {
        imageDao.updateRating(uri, rating)
    }

    suspend fun updateRatings(uris: List<String>, rating: Float) {
        imageDao.updateRatings(uris, rating)
    }

    suspend fun updateAiScore(uri: String, score: Float) {
        imageDao.updateAiScore(uri, score)
    }

    suspend fun updateAiReason(uri: String, reason: String) {
        imageDao.updateAiReason(uri, reason)
    }

    /** Remove images from the local DB and drop their tag associations. */
    suspend fun removeImagesFromDb(uris: List<String>) {
        if (uris.isEmpty()) return
        imageDao.deleteImagesByUri(uris)
        tagDao.removeAllTagsFromImages(uris)
    }

    suspend fun refreshImagesFromMediaStore() = withContext(ioDispatcher) {
        try {
            val images = loadImagesFromMediaStore(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "${MediaStore.MediaColumns.IS_TRASHED} = 0"
                } else {
                    null
                }
            )
            val liveUris = images.map { it.uri }

            // MediaStore unavailable (no permission / storage error): keep the
            // previously cached library untouched — never wipe it on a hiccup.
            if (liveUris.isEmpty()) return@withContext

            // Diff against the cached rows instead of deleteAll+reinsert: insert
            // new rows, update only rows whose metadata changed, and delete rows
            // whose file disappeared. Unchanged rows are never rewritten, so
            // opening the album screen no longer blanks the folder grid for
            // seconds while the whole table is wiped and rebuilt.
            val existingByUri = imageDao.getAllImagesOnce().associateBy { it.uri }
            val toInsert = mutableListOf<ImageEntity>()
            val toUpdate = mutableListOf<ImageEntity>()
            for (entity in images) {
                val old = existingByUri[entity.uri]
                if (old == null) {
                    toInsert.add(entity)
                } else if (old.displayName != entity.displayName ||
                    old.folderName != entity.folderName ||
                    old.width != entity.width || old.height != entity.height ||
                    old.sizeBytes != entity.sizeBytes || old.dateAdded != entity.dateAdded
                ) {
                    // Metadata changed — preserve the user's rating / AI info.
                    toUpdate.add(entity.copy(
                        rating = old.rating,
                        aiScore = old.aiScore,
                        aiReason = old.aiReason
                    ))
                }
            }
            if (toInsert.isNotEmpty()) imageDao.insertImages(toInsert)
            if (toUpdate.isNotEmpty()) imageDao.insertImages(toUpdate) // REPLACE upsert

            if (liveUris.isNotEmpty()) {
                imageDao.deleteImagesNotIn(liveUris)
            }

            // Keep tag cross-refs consistent with the library: drop any row whose URI
            // no longer exists in MediaStore (image moved/deleted outside the app).
            // Left in place, such a row can silently match an unrelated image when
            // MediaStore recycles the numeric ID. Skipped when the library is empty
            // (e.g. MediaStore briefly unavailable) so tags are never wiped wholesale.
            if (liveUris.isNotEmpty()) {
                tagDao.deleteCrossRefsNotIn(liveUris)
            }
            // Tags whose images are gone (moved/deleted outside the app) are now
            // orphaned — drop them so the tag list does not show tags with no images.
            // Guarded: only runs while the images table is non-empty, so a brief
            // MediaStore hiccup can never wipe the whole tag list.
            tagDao.deleteOrphanedTagsGuarded()
        } catch (e: Exception) {
            // A failed MediaStore scan must never crash the refresh (which would
            // leave the album screen blank until the app is reopened) — keep the
            // previously cached library as-is.
            android.util.Log.e("AlbumRepository", "refreshImagesFromMediaStore failed", e)
        }
    }

    /**
     * Images currently in the system recycle bin (API 30+ trash). Empty on
     * older APIs where the trash does not exist.
     */
    suspend fun getTrashedImages(): List<ImageItem> = withContext(ioDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        loadTrashedImagesFromMediaStore().map { it.toImageItem() }
    }

    /**
     * Read the recycle bin. On Android 11+ the MediaProvider HIDES trashed rows
     * from plain external-volume queries — an "IS_TRASHED = 1" selection alone
     * returns nothing, so the recycle-bin grid reads empty even though the
     * delete really did move the images to the trash. Trashed items are only
     * returned when the query opts in via QUERY_ARG_MATCH_TRASHED (or when the
     * trash volume is queried). The rows come back with their normal external
     * content URIs, so thumbnails / restore / permanent delete keep working.
     */
    private fun loadTrashedImagesFromMediaStore(): List<ImageEntity> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, 1)
                // QUERY_ARG_SQL_SELECTION / QUERY_ARG_SQL_SORT_ORDER are not
                // exposed by this build's android.jar, so use the stable
                // platform string values they map to (runtime-identical).
                putString(
                    "android:query-arg-sql-selection",
                    "${MediaStore.MediaColumns.IS_TRASHED} = 1"
                )
                putString(
                    "android:query-arg-sql-sort-order",
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )
            }
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                queryArgs,
                null
            )
            readImageEntities(cursor, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        } catch (e: Exception) {
            // Provider rejected the match-trashed argument — fall back to the
            // plain selection form (works where trashed rows stay visible).
            loadImagesFromMediaStore("${MediaStore.MediaColumns.IS_TRASHED} = 1")
        }
    }

    /** Same match-trashed read for the video collection (videos go to the
     *  system recycle bin exactly like images). */
    private fun loadTrashedVideosFromMediaStore(): List<ImageEntity> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, 1)
                putString(
                    "android:query-arg-sql-selection",
                    "${MediaStore.MediaColumns.IS_TRASHED} = 1"
                )
                putString(
                    "android:query-arg-sql-sort-order",
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )
            }
            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                queryArgs,
                null
            )
            readImageEntities(cursor, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        } catch (e: Exception) {
            // Same fallback as images — plain selection form.
            val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val cursor = context.contentResolver.query(
                uri, videoProjection, "${MediaStore.MediaColumns.IS_TRASHED} = 1", null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )
            readImageEntities(cursor, uri)
        }
    }

    /**
     * All media (images AND videos) currently in the system recycle bin,
     * newest first. Empty on older APIs where the trash does not exist.
     */
    suspend fun getTrashedMedia(): List<TrashedMedia> = withContext(ioDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        val images = loadTrashedImagesFromMediaStore().map {
            TrashedMedia(it.uri, it.displayName, it.folderName, false, it.dateAdded)
        }
        val videos = loadTrashedVideosFromMediaStore().map {
            TrashedMedia(it.uri, it.displayName, it.folderName, true, it.dateAdded)
        }
        (images + videos).sortedByDescending { it.dateAdded }
    }

    /**
     * Images currently marked as favourite (MediaStore IS_FAVORITE, API 30+).
     * Empty on older APIs. Used by the "Favourite" pseudo-tag in Tag browse.
     * Trashed rows are excluded — a recycled image belongs in the recycle bin,
     * not the favourites browse.
     */
    suspend fun getFavouriteImages(): List<ImageItem> = withContext(ioDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        loadImagesFromMediaStore(
            "${MediaStore.MediaColumns.IS_FAVORITE} = 1 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"
        ).map { it.toImageItem() }
    }

    private val imageProjection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATA,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    )

    private val videoProjection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME
    )

    private fun loadImagesFromMediaStore(selection: String?): List<ImageEntity> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor = context.contentResolver.query(
            uri, imageProjection, selection, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        return readImageEntities(cursor, uri)
    }

    private fun readImageEntities(
        cursor: Cursor?,
        collectionUri: Uri
    ): List<ImageEntity> {
        val images = mutableListOf<ImageEntity>()

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val widthCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol)
                val data = c.getString(dataCol)
                val contentUri = Uri.withAppendedPath(
                    collectionUri,
                    id.toString()
                )
                val width = c.getInt(widthCol)
                val height = c.getInt(heightCol)
                val size = c.getLong(sizeCol)
                val dateAdded = c.getLong(dateCol)
                val folderName = c.getString(folderCol) ?: "Unknown"

                images.add(
                    ImageEntity(
                        uri = contentUri.toString(),
                        displayName = name ?: "Unknown",
                        width = width,
                        height = height,
                        sizeBytes = size,
                        dateAdded = dateAdded,
                        folderName = folderName
                    )
                )
            }
        }
        return images
    }

    private fun ImageEntity.toImageItem() = ImageItem(
        uri = uri,
        displayName = displayName,
        rating = rating,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        dateAdded = dateAdded,
        folderName = folderName,
        aiScore = aiScore,
        aiReason = aiReason
    )
}