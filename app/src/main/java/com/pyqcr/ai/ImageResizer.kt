package com.pyqcr.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Resize images before sending to AI API.
 * Follows the logic from photo_rating.py Step 1: resize to max 800px on longest side.
 */
class ImageResizer(private val context: Context) {

    /**
     * Resize image to max dimension, save to cache, return the path.
     * Returns null if decoding fails.
     */
    fun resizeForAi(
        imageUri: Uri,
        maxDimension: Int = 800,
        quality: Int = 80
    ): String? {
        return try {
            // First decode bounds
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            // Calculate sample size
            var sampleSize = 1
            while (origWidth / sampleSize > maxDimension && origHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val sampledBitmap = context.contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null

            // Scale to exact maxDimension
            val (newWidth, newHeight) = calculateTargetSize(
                sampledBitmap.width, sampledBitmap.height, maxDimension
            )

            val resizedBitmap = Bitmap.createScaledBitmap(sampledBitmap, newWidth, newHeight, true)
            sampledBitmap.recycle()

            // Save to cache
            val cacheDir = File(context.cacheDir, "ai_rating")
            cacheDir.mkdirs()
            val outputFile = File(cacheDir, "resized_${System.currentTimeMillis()}_${Math.random()}.jpg")
            FileOutputStream(outputFile).use { out ->
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            resizedBitmap.recycle()

            outputFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearCache() {
        val cacheDir = File(context.cacheDir, "ai_rating")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun calculateTargetSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        return if (width >= height) {
            val newWidth = minOf(width, maxDimension)
            val scale = newWidth.toFloat() / width
            (newWidth to (height * scale).toInt())
        } else {
            val newHeight = minOf(height, maxDimension)
            val scale = newHeight.toFloat() / height
            ((width * scale).toInt() to newHeight)
        }
    }
}