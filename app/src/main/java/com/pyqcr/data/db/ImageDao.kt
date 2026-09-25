package com.pyqcr.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {
    @Query("SELECT * FROM images ORDER BY dateAdded DESC")
    fun getAllImages(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE folderName = :folderName ORDER BY dateAdded DESC")
    fun getImagesByFolder(folderName: String): Flow<List<ImageEntity>>

    @Query("""
        SELECT i.* FROM images i
        INNER JOIN image_tag_cross_ref c ON i.uri = c.imageUri
        INNER JOIN tags t ON c.tagId = t.id
        WHERE t.name = :tagName
        ORDER BY i.dateAdded DESC
    """)
    fun getImagesByTag(tagName: String): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE rating BETWEEN :min AND :max ORDER BY rating DESC")
    fun getImagesByRating(min: Float, max: Float): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images ORDER BY rating DESC")
    fun getImagesSortedByUserRatingDesc(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images ORDER BY rating ASC")
    fun getImagesSortedByUserRatingAsc(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images ORDER BY aiScore DESC")
    fun getImagesSortedByAiScoreDesc(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images ORDER BY aiScore ASC")
    fun getImagesSortedByAiScoreAsc(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE uri = :uri")
    suspend fun getImageByUri(uri: String): ImageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImage(image: ImageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<ImageEntity>)

    @Update
    suspend fun updateImage(image: ImageEntity)

    @Query("UPDATE images SET rating = :rating WHERE uri = :uri")
    suspend fun updateRating(uri: String, rating: Float)

    @Query("UPDATE images SET rating = :rating WHERE uri IN (:uris)")
    suspend fun updateRatings(uris: List<String>, rating: Float)

    @Query("UPDATE images SET aiScore = :score WHERE uri = :uri")
    suspend fun updateAiScore(uri: String, score: Float)

    @Query("SELECT uri, rating FROM images")
    suspend fun getAllUriRatings(): List<UriRating>

    @Query("SELECT uri, aiScore FROM images")
    suspend fun getAllUriAiScores(): List<UriAiScore>

    @Query("DELETE FROM images")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT folderName FROM images ORDER BY folderName")
    fun getAllFolders(): Flow<List<String>>
}