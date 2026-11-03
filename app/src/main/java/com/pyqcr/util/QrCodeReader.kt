package com.pyqcr.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline QR-code decoding for gallery/vault images.
 *
 * Uses ZXing core (pure Java), so it works on any device with no Google Play
 * Services, no network, and no extra permissions. The image is decoded at a
 * bounded resolution (2600 px on the long side) — enough for a QR code that is
 * a small part of a photo, while never allocating a full 50 MP bitmap.
 */
object QrCodeReader {

    private const val MAX_DECODE_DIM = 2600

    /** Decode the first QR code in [source] (content:// URI or absolute file path). */
    suspend fun decode(context: Context, source: String): String? = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(context, source) ?: return@withContext null
        try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun loadBitmap(context: Context, source: String): Bitmap? {
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

        var bitmap = downscale(raw)
        val rotation = exifRotation(context, source)
        if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= MAX_DECODE_DIM) return src
        val factor = MAX_DECODE_DIM.toFloat() / longest
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

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.ALSO_INVERTED to true
        )
        val reader = MultiFormatReader().apply { setHints(hints) }

        // Full frame first, then a centred square crop (helps when the code is
        // small and surrounded by busy photo detail).
        val candidates = listOf(bitmap, centerSquare(bitmap))
        try {
            for (candidate in candidates) {
                val result = try {
                    val pixels = IntArray(candidate.width * candidate.height)
                    candidate.getPixels(pixels, 0, candidate.width, 0, 0, candidate.width, candidate.height)
                    val source = RGBLuminanceSource(candidate.width, candidate.height, pixels)
                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                } catch (e: Exception) {
                    null
                }
                if (result != null) return result.text
            }
        } finally {
            // The caller recycles [bitmap]; drop only the extra crop(s).
            candidates.forEach { if (it !== bitmap) it.recycle() }
        }
        return null
    }

    private fun centerSquare(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        return Bitmap.createBitmap(src, left, top, side, side)
    }
}