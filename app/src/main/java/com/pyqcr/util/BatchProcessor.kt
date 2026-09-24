package com.pyqcr.util

import android.content.ContentResolver
import android.net.Uri
import com.pyqcr.data.db.ImageTagCrossRef
import com.pyqcr.data.db.TagDao

/**
 * Processor for batch operations on selected images.
 */
object BatchProcessor {

    /**
     * Batch add a tag to multiple images.
     */
    suspend fun batchAddTag(
        tagDao: TagDao,
        tagName: String,
        imageUris: List<String>
    ) {
        // Get or create tag
        var tag = tagDao.getTagByName(tagName)
        val tagId = if (tag != null) {
            tag.id
        } else {
            tagDao.insertTag(
                com.pyqcr.data.db.TagEntity(name = tagName)
            )
        }

        // Add cross-refs
        val crossRefs = imageUris.map { uri ->
            ImageTagCrossRef(imageUri = uri, tagId = tagId)
        }
        tagDao.addTagToImages(crossRefs)
    }

    /**
     * Batch remove a tag from multiple images.
     */
    suspend fun batchRemoveTag(
        tagDao: TagDao,
        tagId: Long,
        imageUris: List<String>
    ) {
        tagDao.removeTagFromImages(imageUris, tagId)
    }

    /**
     * Batch rescale images to square (50% resize + square padding).
     */
    suspend fun batchRescaleToSquare(
        contentResolver: ContentResolver,
        imageUris: List<String>,
        outputUris: List<Uri>
    ) {
        imageUris.forEachIndexed { index, uriString ->
            val sourceUri = Uri.parse(uriString)
            if (index < outputUris.size) {
                // First resize to 50%, then pad to square
                val tempUri = Uri.parse(uriString + "_temp")
                ImageUtil.resize50Percent(contentResolver, sourceUri, tempUri)
                ImageUtil.rescaleToSquare(contentResolver, tempUri, outputUris[index])
                // Clean up temp
                try {
                    contentResolver.delete(tempUri, null, null)
                } catch (_: Exception) {}
            }
        }
    }
}