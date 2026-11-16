package com.pyqcr.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * Decodes a gallery or vault image for on-device analysis (QR decoding, OCR).
 *
 * Bounded to [maxDim] on the long side so a 50 MP photo never allocates a full
 * bitmap, and EXIF rotation is applied so analysis sees the upright image.
 * [source] is either a `content://` URI or an absolute file path.
 */
object ImageSourceBitmap {

    fun load(context: Context, source: String, maxDim: Int): Bitmap? {
        val raw = try {
            if (source.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(source))?.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                BitmapFactory.decodeFile(source)
            }
        } catch (e: Exception) {
            null
        } ?: return null

        var bitmap = downscale(raw, maxDim)
        val rotation = exifRotation(context, source)
        if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val factor = maxDim.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            src,
            (src.width * factor).toInt().coerceAtLeast(1),
            (src.height * factor).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun exifRotation(context: Context, source: String): Float {
        val orientation = try {
            if (source.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(source))?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } else {
                ExifInterface(source).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            null
        }
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }
}