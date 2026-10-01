package com.pyqcr.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun getAllTagsOnce(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTagById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Delete
    suspend fun deleteTag(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTagById(id: Long)

    // Cross-ref operations
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToImage(crossRef: ImageTagCrossRef)

    @Delete
    suspend fun removeTagFromImage(crossRef: ImageTagCrossRef)

    @Query("DELETE FROM image_tag_cross_ref WHERE imageUri = :imageUri AND tagId = :tagId")
    suspend fun removeTagFromImage(imageUri: String, tagId: Long)

    @Query("DELETE FROM image_tag_cross_ref WHERE imageUri IN (:imageUris) AND tagId = :tagId")
    suspend fun removeTagFromImages(imageUris: List<String>, tagId: Long)

    @Query("DELETE FROM image_tag_cross_ref WHERE imageUri IN (:imageUris)")
    suspend fun removeAllTagsFromImages(imageUris: List<String>)

    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN image_tag_cross_ref c ON t.id = c.tagId
        WHERE c.imageUri = :imageUri
        ORDER BY t.name
    """)
    fun getTagsForImage(imageUri: String): Flow<List<TagEntity>>

    @Query("""
        SELECT DISTINCT t.* FROM tags t
        INNER JOIN image_tag_cross_ref c ON t.id = c.tagId
        WHERE c.imageUri IN (:imageUris)
        ORDER BY t.name
    """)
    suspend fun getTagsForImages(imageUris: List<String>): List<TagEntity>

    @Query("SELECT c.imageUri FROM image_tag_cross_ref c WHERE c.tagId = :tagId")
    suspend fun getImageUrisForTag(tagId: Long): List<String>

    @Query("SELECT DISTINCT c.imageUri FROM image_tag_cross_ref c")
    fun getAllTaggedImageUris(): Flow<List<String>>

    /** Re-point one image's cross-refs to [newUri] (used after an in-app move). */
    @Query("UPDATE image_tag_cross_ref SET imageUri = :newUri WHERE imageUri = :oldUri")
    suspend fun moveImageCrossRef(oldUri: String, newUri: String)

    /**
     * Drop cross-refs whose image is no longer in the library (moved/deleted
     * outside the app). [uris] must not be empty — SQLite rejects `NOT IN ()`.
     */
    @Query("DELETE FROM image_tag_cross_ref WHERE imageUri NOT IN (:uris)")
    suspend fun deleteCrossRefsNotIn(uris: List<String>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToImages(crossRefs: List<ImageTagCrossRef>)

    @Query("SELECT COUNT(*) FROM image_tag_cross_ref WHERE imageUri = :imageUri AND tagId = :tagId")
    suspend fun hasTag(imageUri: String, tagId: Long): Int
}