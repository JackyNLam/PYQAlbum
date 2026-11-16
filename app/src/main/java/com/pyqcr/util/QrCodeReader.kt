package com.pyqcr.util

import android.content.Context
import android.graphics.Bitmap
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
        val bitmap = ImageSourceBitmap.load(context, source, MAX_DECODE_DIM)
            ?: return@withContext null
        try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
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