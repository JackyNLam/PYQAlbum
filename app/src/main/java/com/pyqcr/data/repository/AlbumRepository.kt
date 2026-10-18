package com.pyqcr.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
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
        loadImagesFromMediaStore("${MediaStore.MediaColumns.IS_TRASHED} = 1")
            .map { it.toImageItem() }
    }

    /**
     * Images currently marked as favourite (MediaStore IS_FAVORITE, API 33+).
     * Empty on older APIs. Used by the "Favourite" pseudo-tag in Tag browse.
     */
    suspend fun getFavouriteImages(): List<ImageItem> = withContext(ioDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext emptyList()
        loadImagesFromMediaStore("${MediaStore.MediaColumns.IS_FAVORITE} = 1")
            .map { it.toImageItem() }
    }

    private fun loadImagesFromMediaStore(selection: String?): List<ImageEntity> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor = context.contentResolver.query(
            uri, projection, selection, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )

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
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
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