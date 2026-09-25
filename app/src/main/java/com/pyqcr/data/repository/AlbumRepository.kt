package com.pyqcr.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.pyqcr.data.db.AppDatabase
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

    fun getAllFolders(): Flow<List<String>> {
        return imageDao.getAllFolders()
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

    suspend fun refreshImagesFromMediaStore() = withContext(ioDispatcher) {
        // Preserve existing ratings and AI scores so they aren't wiped by re-insert
        val existingRatings = imageDao.getAllUriRatings().associate { it.uri to it.rating }
        val existingAiScores = imageDao.getAllUriAiScores().associate { it.uri to it.aiScore }
        val existingAiReasons = imageDao.getAllUriAiReasons().associate { it.uri to it.aiReason }

        val images = loadImagesFromMediaStore().map { entity ->
            entity.copy(
                rating = existingRatings[entity.uri] ?: entity.rating,
                aiScore = existingAiScores[entity.uri] ?: entity.aiScore,
                aiReason = existingAiReasons[entity.uri] ?: entity.aiReason
            )
        }
        imageDao.deleteAll()
        imageDao.insertImages(images)
    }

    private fun loadImagesFromMediaStore(): List<ImageEntity> {
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
            uri, projection, null, null,
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