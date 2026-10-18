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
     *
     * Robust against decode hiccups AND oversized sources: the source is first
     * tried as a raw byte array (reliable for content:// URIs whose streams
     * can't be re-opened), but files over [MAX_READ_FOR_BYTES] skip the byte
     * path (a full read would OOM the worker) and decode from a sampled
     * stream instead. Every decode/scale/compress failure falls back one step
     * further — the LAST resort sends the ORIGINAL bytes so the image still
     * reaches the AI API instead of being dropped before upload.
     */
    fun resizeForAi(
        imageUri: Uri,
        maxDimension: Int = 800,
        quality: Int = 80
    ): String? {
        return try {
            val bytes = readBytesSafely(imageUri)

            if (bytes == null || bytes.size == 0) {
                // No byte array (too big or unreadable) — try a sampled stream
                // decode straight from the source.
                return decodeFromStreamAndSave(imageUri, maxDimension, quality)
            }

            // First decode bounds
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) {
                // Header did not parse — hand the whole decode to the stream path.
                return decodeFromStreamAndSave(imageUri, maxDimension, quality)
            }

            // Calculate sample size so the LONGEST side lands near maxDimension;
            // decodeByteArray applies inSampleSize as a power-of-two subsample.
            val longest = maxOf(origWidth, origHeight)
            var sampleSize = 1
            while (longest / (sampleSize * 2) >= maxDimension) {
                sampleSize *= 2
            }

            // Decode with sample size, falling back to a bigger sample on OOM.
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
                // Byte decode failed outright — stream decode (e.g. exotic
                // containers), then the exact-maxDimension pipeline.
                sampledBitmap = decodeFromStream(context, imageUri, sampleSize)
            }
            if (sampledBitmap == null) {
                // Genuinely refusing to decode — send the ORIGINAL bytes anyway
                // so the image is not dropped before reaching the AI API.
                return saveOriginalBytes(bytes)
            }

            // Scale to exact maxDimension; on OOM keep the sampled bitmap at
            // its decoded size rather than failing the image.
            val targetSize = calculateTargetSize(
                sampledBitmap.width, sampledBitmap.height, maxDimension
            )
            val scaledBitmap = try {
                Bitmap.createScaledBitmap(
                    sampledBitmap, targetSize.first, targetSize.second, true
                )
            } catch (e: OutOfMemoryError) {
                null
            }
            val finalBitmap = scaledBitmap ?: sampledBitmap
            if (scaledBitmap != null) {
                sampledBitmap.recycle()
            }

            val saved = saveBitmap(finalBitmap, quality)
            if (saved != null) return saved

            // Writing the resized JPEG failed — fall back to the original bytes.
            return saveOriginalBytes(bytes)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "resizeForAi OOM for $imageUri")
            null
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

    /**
     * Byte-array source. Very large files (panoramas, raw exports) skip the
     * full read — the byte array would OOM the worker process — and take the
     * sampled stream-decode path instead.
     */
    private fun readBytesSafely(imageUri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                try {
                    val available = input.available().toLong()
                    if (available > 0 && available > MAX_READ_FOR_BYTES) {
                        return@use null
                    }
                } catch (e: Exception) {
                }
                try {
                    input.readBytes()
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Sampled decode straight from the content stream (no byte array). */
    private fun decodeFromStream(context: Context, imageUri: Uri, sampleSize: Int): Bitmap? {
        return try {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                })
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Full stream-based pipeline for sources that refuse the byte-array path. */
    private fun decodeFromStreamAndSave(imageUri: Uri, maxDimension: Int, quality: Int): String? {
        val sampled = decodeFromStream(context, imageUri, 1) ?: return null
        val targetSize = calculateTargetSize(sampled.width, sampled.height, maxDimension)
        val scaledBitmap = try {
            Bitmap.createScaledBitmap(sampled, targetSize.first, targetSize.second, true)
        } catch (e: OutOfMemoryError) {
            null
        }
        val finalBitmap = scaledBitmap ?: sampled
        if (scaledBitmap != null) {
            sampled.recycle()
        }
        return saveBitmap(finalBitmap, quality)
    }

    /** JPEG-encode [bitmap] into the ai_rating cache dir; null on failure. */
    private fun saveBitmap(bitmap: Bitmap, quality: Int): String? {
        return try {
            val cacheDir = File(context.cacheDir, "ai_rating")
            cacheDir.mkdirs()
            val outputFile = File(cacheDir, "resized_${System.currentTimeMillis()}_${Math.random()}.jpg")
            val ok = FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            bitmap.recycle()
            if (ok && outputFile.exists() && outputFile.length() > 0L) {
                outputFile.absolutePath
            } else {
                outputFile.delete()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Last resort: send the ORIGINAL bytes so the image still reaches the AI. */
    private fun saveOriginalBytes(bytes: ByteArray): String? {
        return try {
            val cacheDir = File(context.cacheDir, "ai_rating")
            cacheDir.mkdirs()
            val outputFile = File(cacheDir, "original_${System.currentTimeMillis()}_${Math.random()}.jpg")
            outputFile.writeBytes(bytes)
            if (outputFile.exists() && outputFile.length() > 0L) {
                outputFile.absolutePath
            } else {
                outputFile.delete()
                null
            }
        } catch (e: Exception) {
            null
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
        private const val MAX_READ_FOR_BYTES = 64L * 1024 * 1024
    }
}