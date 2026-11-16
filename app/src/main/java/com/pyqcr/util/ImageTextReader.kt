package com.pyqcr.util

import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * On-device text extraction (OCR) for gallery images.
 *
 * Uses ML Kit's bundled Chinese recogniser, which also reads Latin script, so
 * it needs no network, no API key and no Play Services model download — the
 * same offline promise as [QrCodeReader].
 */
object ImageTextReader {

    private const val MAX_DECODE_DIM = 3000

    /**
     * Extract the text in [source] (content:// URI or absolute file path).
     * Returns null when nothing was recognised or the image could not be read.
     */
    suspend fun extract(context: Context, source: String): String? = withContext(Dispatchers.IO) {
        val bitmap = ImageSourceBitmap.load(context, source, MAX_DECODE_DIM)
            ?: return@withContext null
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        try {
            val text = suspendCancellableCoroutine<String?> { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        continuation.resume(textOf(result))
                    }
                    .addOnFailureListener {
                        continuation.resume(null)
                    }
            }
            text?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
            recognizer.close()
        }
    }

    /** Keep block/paragraph line breaks so the copied text stays readable. */
    private fun textOf(result: com.google.mlkit.vision.text.Text): String =
        result.textBlocks
            .joinToString("\n\n") { it.text }
            .trim()
}