package com.pyqcr.data.db

import androidx.room.Entity

@Entity(
    tableName = "image_tag_cross_ref",
    primaryKeys = ["imageUri", "tagId"]
)
data class ImageTagCrossRef(
    val imageUri: String,
    val tagId: Long
)