package com.pyqcr.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Normalized crop rectangle with coordinates 0f..1f relative to image dimensions.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    /** Minimum 1% of the image to be a valid selection. */
    fun isValid(): Boolean = right - left > 0.01f && bottom - top > 0.01f
}

/**
 * Image data prepared for the AI API: base64-encoded bytes with the pixel
 * dimensions of the image being sent (after optional upscaling to meet the
 * minimum 512px resolution requirement).
 */
data class CroppedImageData(
    val base64: String,
    val width: Int,
    val height: Int
)

/**
 * AI Edit Service that calls the Wan2.7 Image Edit API via DashScope.
 *
 * This service sends cropped area(s) of source image(s) + an optional reference
 * target image + a custom prompt to the Wan2.7-image-pro multimodal model,
 * then composites the AI-edited area back onto the original source at the
 * same position and saves the result to a gallery-visible location.
 */
class AiEditService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Edit images using the Wan2.7 Image Edit API.
     *
     * When [cropRects] contains an entry for a source path, only the cropped
     * area is sent to the AI; the result is composited back onto the original
     * image at the crop position and saved to the device gallery (MediaStore).
     * Without crop rects, the full image is sent and saved in the output dir
     * (original behavior).
     *
     * @return List of saved file paths or MediaStore content:// URIs.
     */
    suspend fun editImages(
        apiKey: String,
        modelName: String,
        sourceImagePaths: List<String>,
        cropRects: Map<String, CropRect> = emptyMap(),
        targetImagePath: String?,
        prompt: String,
        outputDir: File,
        context: Context,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDebug: (String) -> Unit = {},
        onFatalError: (String) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val outputUris = mutableListOf<String>()
        val total = sourceImagePaths.size
        var completed = 0

        for ((index, sourcePath) in sourceImagePaths.withIndex()) {
            coroutineContext.ensureActive()

            onDebug("▶️ Editing image ${index + 1}/$total: ${File(sourcePath).name}")

            val cropRect = cropRects[sourcePath]
            val inputImageData: CroppedImageData?
            val hasCrop = cropRect != null && cropRect.isValid()

            if (hasCrop) {
                onDebug("  Cropping area (${"%.2f".format(cropRect!!.left)},${"%.2f".format(cropRect.top)})-(${"%.2f".format(cropRect.right)},${"%.2f".format(cropRect.bottom)})...")
                inputImageData = cropAndEncode(sourcePath, cropRect)
            } else {
                inputImageData = loadFullImageData(sourcePath)
            }

            if (inputImageData == null) {
                onDebug("❌ Failed to process source image: $sourcePath")
                completed++
                onProgress(completed, total)
                continue
            }

            onDebug("  Input image: ${inputImageData.width}x${inputImageData.height}, requesting output size: 1024x1024")

            val targetBase64 = if (targetImagePath != null) {
                encodeImageToBase64(targetImagePath)
            } else null

            // Build content array: source image, optional target reference, prompt
            val content = mutableListOf<Map<String, Any>>()
            content.add(mapOf(
                "image" to "data:image/jpeg;base64,${inputImageData.base64}"
            ))
            if (targetBase64 != null) {
                content.add(mapOf(
                    "image" to "data:image/jpeg;base64,$targetBase64"
                ))
            }
            content.add(mapOf("text" to prompt))

            val requestBody = mapOf(
                "model" to modelName,
                "input" to mapOf(
                    "messages" to listOf(
                        mapOf(
                            "role" to "user",
                            "content" to content
                        )
                    )
                ),
                "parameters" to mapOf(
                    // Use a fixed standard output size because the model may
                    // not support arbitrary input-image dimensions. The AI
                    // result will be scaleToFit to the target crop area.
                    "size" to "1024*1024",
                    "watermark" to false,
                    "n" to 1
                )
            )

            val jsonBody = gson.toJson(requestBody)
            onDebug("Request body: ${jsonBody.take(500)}...")

            val request = Request.Builder()
                .url("https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
                .header("Authorization", "Bearer $apiKey")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val maxRetries = 3
            var retryCount = 0
            var imageSaved = false

            while (!imageSaved && retryCount < maxRetries) {
                coroutineContext.ensureActive()
                retryCount++
                try {
                    onDebug("  Sending API request (attempt $retryCount/$maxRetries)...")
                    val response = executeCancellable(request)
                    val responseCode = response.code
                    val rawBody = response.body?.string() ?: "<NULL BODY>"

                    onDebug("  Response HTTP $responseCode, body length: ${rawBody.length} chars")
                    if (rawBody.length > 2000) {
                        onDebug(rawBody.take(2000) + "\n... [${rawBody.length - 2000} more chars truncated]")
                    } else {
                        onDebug(rawBody)
                    }

                    if (!response.isSuccessful) {
                        val fatal = classifyApiError(responseCode, rawBody)
                        if (fatal != null) {
                            onDebug("🛑 FATAL API error: $fatal")
                            onFatalError(fatal)
                            return@withContext outputUris
                        }
                        onDebug("❌ HTTP $responseCode, retrying...")
                        continue
                    }

                    // Parse the response to extract image URLs
                    val imageUrl = extractImageUrl(rawBody)
                    if (imageUrl == null) {
                        onDebug("❌ No image URL found in response — retrying (attempt $retryCount/$maxRetries)")
                        continue
                    }

                    onDebug("  Image URL received, downloading...")

                    // Download AI-edited area to a temp file
                    val tempFile = File(outputDir, ".ai_edit_temp_${index}_${System.currentTimeMillis()}.jpg")
                    downloadImage(imageUrl, tempFile)

                    if (hasCrop && cropRect != null) {
                        // Composite the edited area back onto the original
                        val savedUri = compositeAndSaveToMediaStore(
                            context = context,
                            originalPath = sourcePath,
                            editedAreaPath = tempFile.absolutePath,
                            cropRect = cropRect,
                            suffix = "_ai_edit_${index}",
                            onDebug = onDebug
                        )
                        if (savedUri != null) {
                            outputUris.add(savedUri)
                            imageSaved = true
                            onDebug("✅ Saved to gallery: $savedUri")
                        } else {
                            onDebug("❌ Failed to composite and save — retrying...")
                        }
                        // Clean up temp file
                        tempFile.delete()
                    } else {
                        // No crop: save the raw AI result directly
                        val outputFileName = "ai_edit_${System.currentTimeMillis()}_${index}.jpg"
                        val outputFile = File(outputDir, outputFileName)
                        tempFile.renameTo(outputFile)
                        outputUris.add(outputFile.absolutePath)
                        imageSaved = true
                        onDebug("✅ Saved: ${outputFile.absolutePath}")
                    }

                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onDebug("❌ Exception (attempt $retryCount/$maxRetries): ${e::class.simpleName}: ${e.message}")
                    if (retryCount >= maxRetries) {
                        onDebug("❌ Gave up on image ${index + 1} after $maxRetries attempts")
                    }
                }
            }

            if (!imageSaved) {
                onDebug("⚠️ Failed to edit image ${index + 1} — skipping")
            }

            completed++
            onProgress(completed, total)
        }

        outputUris
    }

    // ---- Crop & Composite helpers ----

    /**
     * Crop the source image to the normalized [CropRect] and encode the
     * cropped area as a base64 data URL string.
     *
     * If the cropped region is smaller than 512px on either side, it is
     * upscaled proportionally so the minimum dimension is at least 512px,
     * satisfying the minimum resolution requirement of some AI models.
     *
     * @return [CroppedImageData] with the base64 string and the actual pixel
     *         dimensions of the image sent to the AI (after any upscaling).
     */
    /**
     * Rotate a decoded bitmap to match its EXIF orientation so pixel coordinates
     * align with how [coil3.compose.AsyncImage] displays the image.
     * Returns the original bitmap if no rotation is needed, or a new rotated
     * bitmap (the caller should recycle the original after).
     */
    private fun applyExifOrientation(filePath: String, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> return bitmap
            }
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * Scale [source] to [targetW]×[targetH] preserving content as much as
     * possible. If the aspect ratios differ, the source is center-cropped to
     * match the target aspect ratio before scaling, preventing stretching.
     */
    private fun scaleToFit(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcRatio = source.width.toFloat() / source.height.toFloat()
        val dstRatio = targetW.toFloat() / targetH.toFloat()
        if (kotlin.math.abs(srcRatio - dstRatio) > 0.001f) {
            // Aspect ratios differ — crop the source to match target ratio first
            val cropW: Int
            val cropH: Int
            if (srcRatio > dstRatio) {
                // Source is wider — crop horizontal edges
                cropH = source.height
                cropW = (cropH.toFloat() * dstRatio).roundToInt()
            } else {
                // Source is taller — crop vertical edges
                cropW = source.width
                cropH = (cropW.toFloat() / dstRatio).roundToInt()
            }
            val offsetX = (source.width - cropW) / 2
            val offsetY = (source.height - cropH) / 2
            val cropped = Bitmap.createBitmap(source, offsetX, offsetY, cropW, cropH)
            val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
            cropped.recycle()
            return scaled
        }
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun cropAndEncode(sourcePath: String, cropRect: CropRect): CroppedImageData? {
        return try {
            val rawBitmap = BitmapFactory.decodeFile(sourcePath) ?: return null
            val srcBitmap = applyExifOrientation(sourcePath, rawBitmap)
            val iw = srcBitmap.width
            val ih = srcBitmap.height
            // Use roundToInt() instead of toInt() so pixel boundaries are the
            // nearest integer, avoiding the systematic truncation bias that
            // makes the cropped area slightly larger when left and right have
            // different fractional parts.
            val pixelRect = android.graphics.Rect(
                (cropRect.left * iw).roundToInt().coerceIn(0, iw),
                (cropRect.top * ih).roundToInt().coerceIn(0, ih),
                (cropRect.right * iw).roundToInt().coerceIn(0, iw),
                (cropRect.bottom * ih).roundToInt().coerceIn(0, ih)
            )
            if (pixelRect.width() <= 0 || pixelRect.height() <= 0) {
                srcBitmap.recycle()
                return null
            }
            var cropped = Bitmap.createBitmap(
                srcBitmap,
                pixelRect.left, pixelRect.top,
                pixelRect.width(), pixelRect.height()
            )
            srcBitmap.recycle()

            // Upscale to at least 512px on each side
            val MIN_SIDE = 512
            var inputW = cropped.width
            var inputH = cropped.height
            if (inputW < MIN_SIDE || inputH < MIN_SIDE) {
                val scale = MIN_SIDE.toFloat() / minOf(inputW, inputH)
                val newW = (inputW * scale).roundToInt().coerceIn(MIN_SIDE, Int.MAX_VALUE)
                val newH = (inputH * scale).roundToInt().coerceIn(MIN_SIDE, Int.MAX_VALUE)
                val scaled = Bitmap.createScaledBitmap(cropped, newW, newH, true)
                cropped.recycle()
                cropped = scaled
                inputW = newW
                inputH = newH
            }

            val bytes = java.io.ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
            cropped.recycle()
            CroppedImageData(
                base64 = Base64.getEncoder().encodeToString(bytes.toByteArray()),
                width = inputW,
                height = inputH
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Load an entire image file, encode it as base64, and return its pixel
     * dimensions. Also upscales if either side is < 512px, applying the same
     * minimum-resolution policy as [cropAndEncode].
     */
    private fun loadFullImageData(sourcePath: String): CroppedImageData? {
        return try {
            val file = File(sourcePath)
            if (!file.exists()) return null

            val rawBitmap = BitmapFactory.decodeFile(sourcePath) ?: return null
            val srcBitmap = applyExifOrientation(sourcePath, rawBitmap)
            var inputW = srcBitmap.width
            var inputH = srcBitmap.height

            // Upscale to at least 512px on each side
            val MIN_SIDE = 512
            var bitmap = srcBitmap
            if (inputW < MIN_SIDE || inputH < MIN_SIDE) {
                val scale = MIN_SIDE.toFloat() / minOf(inputW, inputH)
                val newW = (inputW * scale).roundToInt().coerceIn(MIN_SIDE, Int.MAX_VALUE)
                val newH = (inputH * scale).roundToInt().coerceIn(MIN_SIDE, Int.MAX_VALUE)
                val scaled = Bitmap.createScaledBitmap(srcBitmap, newW, newH, true)
                srcBitmap.recycle()
                bitmap = scaled
                inputW = newW
                inputH = newH
            }

            val bytes = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
            bitmap.recycle()
            CroppedImageData(
                base64 = Base64.getEncoder().encodeToString(bytes.toByteArray()),
                width = inputW,
                height = inputH
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Composite the AI-edited area bitmap onto the original image at the crop
     * position, then save the result to the device gallery via MediaStore.
     *
     * @return The MediaStore content:// URI string, or null on failure.
     */
    private fun compositeAndSaveToMediaStore(
        context: Context,
        originalPath: String,
        editedAreaPath: String,
        cropRect: CropRect,
        suffix: String,
        onDebug: (String) -> Unit
    ): String? {
        return try {
            // Load original and edited bitmaps (apply EXIF rotation to both
            // so pixel positions align with how the dialog displayed the image)
            val rawOriginal = BitmapFactory.decodeFile(originalPath) ?: return null
            val original = applyExifOrientation(originalPath, rawOriginal)
            val rawEdited = BitmapFactory.decodeFile(editedAreaPath) ?: run {
                original.recycle(); return null
            }

            val iw = original.width
            val ih = original.height
            // roundToInt() instead of toInt() — must match cropAndEncode
            val pixelRect = android.graphics.Rect(
                (cropRect.left * iw).roundToInt().coerceIn(0, iw),
                (cropRect.top * ih).roundToInt().coerceIn(0, ih),
                (cropRect.right * iw).roundToInt().coerceIn(0, iw),
                (cropRect.bottom * ih).roundToInt().coerceIn(0, ih)
            )
            val cropW = pixelRect.width()
            val cropH = pixelRect.height()

            // Log AI result dimensions vs expected
            val edited = applyExifOrientation(editedAreaPath, rawEdited)
            onDebug("  AI result: ${edited.width}x${edited.height}, expected crop area: ${cropW}x${cropH}")

            // Scale the AI-edited area to match the crop dimensions.
            // If the aspect ratio differs from expected, center-crop first
            // to avoid stretching the content.
            val scaledEdited = if (edited.width != cropW || edited.height != cropH) {
                val scaled = scaleToFit(edited, cropW, cropH)
                edited.recycle()
                scaled
            } else {
                edited
            }

            // Composite onto the original using integer-precise Rect dst
            // to avoid sub-pixel rendering offsets when drawing at the crop position.
            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            // isFilterBitmap=false since the AI result is already pre-scaled to
            // cropW×cropH — no filtering needed for a 1:1 pixel copy, and it
            // prevents bilinear edge bleed that could make the composite seem
            // slightly larger than the selection.
            val paint = Paint().apply { isFilterBitmap = false }
            canvas.drawBitmap(scaledEdited,
                android.graphics.Rect(0, 0, scaledEdited.width, scaledEdited.height),
                android.graphics.Rect(pixelRect.left, pixelRect.top, pixelRect.right, pixelRect.bottom),
                paint)

            // Save via MediaStore (gallery-visible)
            val fileName = "PYQAlbum_AI_Edit_${System.currentTimeMillis()}$suffix.jpg"
            val savedUri = saveToMediaStore(context, result, fileName, onDebug)

            // Clean up
            original.recycle()
            edited.recycle()
            if (scaledEdited !== edited) scaledEdited.recycle()
            result.recycle()

            savedUri
        } catch (e: Exception) {
            onDebug("  ❌ Composite failed: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Save a bitmap to the device gallery (Pictures/PYQAlbum/) via MediaStore
     * on API 29+, or to the public Pictures directory on older versions.
     *
     * @return The content:// URI string, or null on failure.
     */
    private fun saveToMediaStore(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        onDebug: (String) -> Unit
    ): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/PYQAlbum"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return null

                val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                } ?: false

                if (!written) {
                    context.contentResolver.delete(uri, null, null)
                    return null
                }

                // Clear IS_PENDING to make the image visible
                val updateValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                val updated = context.contentResolver.update(uri, updateValues, null, null)
                if (updated == 0) {
                    onDebug("  ⚠️ MediaStore update returned 0 — file may be invisible")
                    context.contentResolver.delete(uri, null, null)
                    return null
                }

                uri.toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PYQAlbum"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                // Scan so the gallery picks it up
                try {
                    android.media.MediaScannerConnection.scanFile(
                        context, arrayOf(file.absolutePath), null, null
                    )
                } catch (_: Exception) {}
                file.absolutePath
            }
        } catch (e: Exception) {
            onDebug("  ❌ MediaStore save failed: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // ---- Chat-compatible fallback (updated with crop support) ----

    /**
     * Try the chat-completions compatible endpoint as a fallback.
     * When [cropRects] is provided, applies the same crop→composite flow.
     */
    suspend fun editImagesChatCompatible(
        apiKey: String,
        modelName: String,
        sourceImagePaths: List<String>,
        cropRects: Map<String, CropRect> = emptyMap(),
        targetImagePath: String?,
        prompt: String,
        outputDir: File,
        context: Context,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDebug: (String) -> Unit = {},
        onFatalError: (String) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val outputUris = mutableListOf<String>()
        val total = sourceImagePaths.size
        var completed = 0

        for ((index, sourcePath) in sourceImagePaths.withIndex()) {
            coroutineContext.ensureActive()
            onDebug("▶️ Editing image ${index + 1}/$total (chat-compatible): ${File(sourcePath).name}")

            val cropRect = cropRects[sourcePath]
            val hasCrop = cropRect != null && cropRect.isValid()

            val inputImageData = if (hasCrop) {
                cropAndEncode(sourcePath, cropRect!!)
            } else {
                loadFullImageData(sourcePath)
            }
            if (inputImageData == null) {
                completed++
                onProgress(completed, total)
                continue
            }

            val content = mutableListOf<Map<String, Any>>()
            content.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:image/jpeg;base64,${inputImageData.base64}")
            ))
            if (targetImagePath != null) {
                val tb64 = encodeImageToBase64(targetImagePath)
                if (tb64 != null) {
                    content.add(mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:image/jpeg;base64,$tb64")
                    ))
                }
            }
            content.add(mapOf("type" to "text", "text" to prompt))

            val messages = listOf(
                mapOf("role" to "user", "content" to content)
            )

            val requestBody = mapOf(
                "model" to modelName,
                "messages" to messages,
                "max_tokens" to 4096
            )

            val request = Request.Builder()
                .url("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(gson.toJson(requestBody).toRequestBody(jsonMediaType))
                .build()

            try {
                val response = executeCancellable(request)
                val rawBody = response.body?.string() ?: ""
                onDebug("  Response HTTP ${response.code}")

                if (response.isSuccessful) {
                    val jsonResponse = JsonParser.parseString(rawBody.sanitizeJson()).asJsonObject
                    val choices = jsonResponse.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val messageContent = choices[0].asJsonObject
                            .getAsJsonObject("message")
                            ?.get("content")?.asString ?: ""
                        onDebug("  Content: ${messageContent.take(300)}")

                        val imgUrlMatch = Regex("""(https?://[^\s"']+\.(?:jpg|jpeg|png|webp))""")
                            .find(messageContent)
                        if (imgUrlMatch != null) {
                            val imageUrl = imgUrlMatch.value
                            val tempFile = File(outputDir, ".ai_edit_temp_fb_${index}_${System.currentTimeMillis()}.jpg")
                            downloadImage(imageUrl, tempFile)

                            if (hasCrop && cropRect != null) {
                                val savedUri = compositeAndSaveToMediaStore(
                                    context, sourcePath, tempFile.absolutePath, cropRect,
                                    "_ai_edit_fb_$index", onDebug
                                )
                                if (savedUri != null) {
                                    outputUris.add(savedUri)
                                    onDebug("✅ Saved to gallery: $savedUri")
                                } else {
                                    onDebug("❌ Composite failed")
                                }
                                tempFile.delete()
                            } else {
                                val outFile = File(outputDir, "ai_edit_${System.currentTimeMillis()}_${index}.jpg")
                                tempFile.renameTo(outFile)
                                outputUris.add(outFile.absolutePath)
                                onDebug("✅ Saved: ${outFile.absolutePath}")
                            }
                        } else {
                            onDebug("❌ No image URL in chat-compatible response")
                        }
                    }
                } else {
                    onDebug("❌ Chat-compatible endpoint returned HTTP ${response.code}")
                }
            } catch (e: Exception) {
                onDebug("❌ Chat-compatible fallback failed: ${e.message}")
            }

            completed++
            onProgress(completed, total)
        }

        outputUris
    }

    // ---- Original helpers (unchanged) ----

    /**
     * Extract the first image URL from the Wan2.7 API response JSON.
     * Strips any leading/trailing non-JSON characters before parsing.
     */
    private fun extractImageUrl(rawBody: String): String? {
        return try {
            // Sanitize: find the first '{' and last '}' to strip any
            // non-JSON prefix/suffix (e.g. encoding artifacts or
            // gateway banners) that would break JsonParser.
            val start = rawBody.indexOf('{')
            val end = rawBody.lastIndexOf('}')
            if (start == -1 || end == -1 || start >= end) return null
            val clean = rawBody.substring(start, end + 1)
            val root = JsonParser.parseString(clean).asJsonObject
            val output = root.getAsJsonObject("output") ?: return null
            val choices = output.getAsJsonArray("choices") ?: return null
            if (choices.size() == 0) return null
            val message = choices[0].asJsonObject
                .getAsJsonObject("message") ?: return null
            val content = message.getAsJsonArray("content") ?: return null
            for (item in content) {
                val obj = item.asJsonObject
                val type = obj.get("type")?.asString
                if (type == "image" || type == "image_url") {
                    val imageValue = obj.get("image")
                    if (imageValue != null) {
                        if (imageValue.isJsonPrimitive && imageValue.asJsonPrimitive.isString) {
                            return imageValue.asString
                        }
                        val imageObj = imageValue.asJsonObject
                        val url = imageObj.get("url")?.asString
                        if (url != null) return url
                    }
                    val directUrl = obj.get("url")?.asString
                    if (directUrl != null) return directUrl
                }
                // Some models (e.g. qwen-image-edit-plus) omit the "type"
                // field and only include "image" + "role".
                val imageValue = obj.get("image")
                if (imageValue != null && imageValue.isJsonPrimitive && imageValue.asJsonPrimitive.isString) {
                    return imageValue.asString
                }
            }
            val resultUrl = output.get("result_url")?.asString
            if (resultUrl != null) return resultUrl
            val outputImageUrl = output.get("image_url")?.asString
            if (outputImageUrl != null) return outputImageUrl
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadImage(imageUrl: String, outputFile: File) {
        val connection = URL(imageUrl).openConnection()
        connection.setRequestProperty("User-Agent", "PYQAlbum/1.0")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.getInputStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    private fun classifyApiError(code: Int, body: String): String? {
        val b = body.lowercase()
        return when {
            b.contains("allocationquota") || b.contains("free quota") || b.contains("free tier") ->
                "API quota exhausted — your DashScope free tier is used up."
            code == 401 || b.contains("invalid_api_key") || b.contains("incorrect api key") ->
                "Invalid API key."
            code == 403 && b.contains("access_denied") ->
                "Access denied — this model isn't enabled for your account/key."
            b.contains("arrearage") || b.contains("insufficient") || b.contains("overdue") ->
                "Account arrears/insufficient balance."
            else -> null
        }
    }

    private fun encodeImageToBase64(imagePath: String): String? {
        return try {
            val file = File(imagePath)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            Base64.getEncoder().encodeToString(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        private const val TAG = "AiEditService"
    }
}

/**
 * Strip leading/trailing non-JSON characters from a raw response body
 * so [JsonParser.parseString] does not choke on encoding artifacts.
 */
private fun String.sanitizeJson(): String {
    val start = indexOf('{')
    val end = lastIndexOf('}')
    if (start == -1 || end == -1 || start >= end) return this
    return substring(start, end + 1)
}