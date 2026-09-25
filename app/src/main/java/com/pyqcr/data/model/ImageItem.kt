package com.pyqcr.data.model

data class ImageItem(
    val uri: String,
    val displayName: String,
    val rating: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val dateAdded: Long = 0L,
    val folderName: String = "",
    val aiScore: Float? = null,
    val aiReason: String? = null
)