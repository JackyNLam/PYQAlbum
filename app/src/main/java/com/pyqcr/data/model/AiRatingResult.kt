package com.pyqcr.data.model

data class AiRatingResult(
    val score: Float,
    val reason: String,
    val imageUri: String,
    val imageName: String
)