package com.pyqcr.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
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
     *
     * Robust against decode hiccups: the source is first tried as a raw byte
     * array (reliable for content:// URIs whose streams can't be re-opened),
     * then as a stream; the sample size is derived from the decoded bounds so
     * very large panoramas never OOM on a full decode.
     */
    fun resizeForAi(
        imageUri: Uri,
        maxDimension: Int = 800,
        quality: Int = 80
    ): String? {
        return try {
            // Read the whole file once — simplest reliable decode path, and it
            // works for every URI scheme MediaStore hands back.
            val bytes = context.contentResolver.openInputStream(imageUri)
                ?.use { it.readBytes() }
            if (bytes == null || bytes.size == 0) return null

            // First decode bounds
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            // Calculate sample size so the LONGEST side lands near maxDimension;
            // decodeByteArray applies inSampleSize as a power-of-two subsample.
            val longest = maxOf(origWidth, origHeight)
            var sampleSize = 1
            while (longest / (sampleSize * 2) >= maxDimension) {
                sampleSize *= 2
            }

            // Decode with sample size, falling back to the full-size decode if
            // the stream path fails (some providers dislike inSampleSize).
            var sampledBitmap = try {
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }
                )
            } catch (e: OutOfMemoryError) {
                null
            }
            if (sampledBitmap == null && sampleSize > 1) {
                // OOM-safe retry: drop down to a bigger sample rather than fail.
                var rescueSample = sampleSize * 2
                while (sampledBitmap == null && rescueSample <= 512) {
                    sampledBitmap = try {
                        BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.size,
                            BitmapFactory.Options().apply { inSampleSize = rescueSample }
                        )
                    } catch (e: OutOfMemoryError) {
                        null
                    }
                    rescueSample *= 2
                }
            }
            if (sampledBitmap == null) {
                // Last resort: stream decode (e.g. exotic containers).
                sampledBitmap = context.contentResolver.openInputStream(imageUri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    })
                }
            }
            if (sampledBitmap == null) return null

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
            Log.w(TAG, "resizeForAi failed for $imageUri", e)
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

    companion object {
        private const val TAG = "ImageResizer"
    }
}