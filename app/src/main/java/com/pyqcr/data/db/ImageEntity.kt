package com.pyqcr.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val uri: String,            // content:// URI as PK
    val displayName: String,
    val rating: Float = 0f,                 // 0.0 - 5.0
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAdded: Long,
    val folderName: String,
    val aiScore: Float? = null              // AI score (1-100), optional
)

data class UriRating(
    val uri: String,
    val rating: Float
)

data class UriAiScore(
    val uri: String,
    val aiScore: Float?
)