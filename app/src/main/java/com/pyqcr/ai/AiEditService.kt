package com.pyqcr.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
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
 * A single point of a brush stroke, normalized to 0f..1f relative to the
 * displayed image dimensions.
 */
data class BrushStrokePoint(val x: Float, val y: Float)

/**
 * One continuous brush stroke. [points] are in normalized image space;
 * [radius] is the stroke width as a fraction of the image's shorter side;
 * [eraser] strokes remove previously painted area instead of adding to it.
 */
data class BrushStroke(
    val points: List<BrushStrokePoint>,
    val radius: Float,
    val eraser: Boolean = false
)

/**
 * The union of brush strokes that selects an edit region. When passed to the
 * AI, everything OUTSIDE the painted strokes is made transparent.
 */
data class BrushMask(val strokes: List<BrushStroke>) {
    fun isEmpty(): Boolean = strokes.isEmpty()
}

/**
 * How one source image should be edited:
 *  - [cropRect]: rectangle selection (normalized); null = whole image.
 *  - [mask]: brush selection; everything outside the painted strokes is
 *    replaced with a blurred context pattern in the input image, and the
 *    composite confines the edit to the painted area.
 *
 * Rect and brush are alternative selection modes in the UI, but the service
 * supports any combination.
 */
data class EditSelection(
    val cropRect: CropRect? = null,
    val mask: BrushMask? = null
) {
    val hasCrop: Boolean get() = cropRect != null && cropRect.isValid()
    val hasMask: Boolean get() = mask != null && !mask!!.isEmpty()
}

/**
 * One source image queued for a background AI edit job: the content URI plus
 * how to edit it. Serialized to JSON (Gson) so the WorkManager worker can
 * read it back on another process lifetime.
 */
data class AiEditJobItem(
    val uri: String,
    val selection: EditSelection
)

/**
 * Image data prepared for the AI API: base64-encoded bytes with the pixel
 * dimensions of the image being sent (after optional upscaling to meet the
 * minimum 512px resolution requirement), plus the MIME type used in the
 * data URL ("image/jpeg"; the brush-mask surrounding pattern is opaque so
 * no alpha channel is needed).
 */
data class CroppedImageData(
    val base64: String,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/jpeg"
)

/**
 * AI Edit Service that calls the Qwen Image Edit API via DashScope.
 *
 * This service sends cropped area(s) of source image(s) + an optional reference
 * target image + a custom prompt to the qwen-image-2.0 / qwen-image-edit family
 * multimodal model, then composites the AI-edited area back onto the original
 * source at the same position and saves the result to a gallery-visible
 * location.
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
     * When [selections] contains an entry for a source path, only the cropped/
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
        selections: Map<String, EditSelection> = emptyMap(),
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

            val selection = selections[sourcePath] ?: EditSelection()

            if (selection.hasCrop) {
                val cr = selection.cropRect!!
                onDebug("  Cropping area (${"%.2f".format(cr.left)},${"%.2f".format(cr.top)})-(${"%.2f".format(cr.right)},${"%.2f".format(cr.bottom)})...")
            }
            if (selection.hasMask) {
                onDebug("  Applying brush mask: ${selection.mask!!.strokes.size} stroke(s), outside area transparent")
            }
            val inputImageData = prepareSourceData(sourcePath, selection)

            if (inputImageData == null) {
                onDebug("❌ Failed to process source image: $sourcePath")
                completed++
                onProgress(completed, total)
                continue
            }

            onDebug("  Input image: ${inputImageData.width}x${inputImageData.height} (${inputImageData.mimeType}), requesting output size: ${inputImageData.width}x${inputImageData.height}")

            val targetBase64 = if (targetImagePath != null) {
                encodeImageToBase64(targetImagePath)
            } else null

            // Build content array: target reference image first, source image to edit
            // second, then the prompt. Swapped on user request: with the source first,
            // the model based its output on the target image instead of the source.
            val content = mutableListOf<Map<String, Any>>()
            if (targetBase64 != null) {
                content.add(mapOf(
                    "image" to "data:image/jpeg;base64,$targetBase64"
                ))
            }
            content.add(mapOf(
                "image" to "data:${inputImageData.mimeType};base64,${inputImageData.base64}"
            ))
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
                    // Request the exact size of the image we sent. qwen-image-2.0
                    // supports freely set width/height (total pixels between
                    // 512x512 and 2048x2048), and both are snapped to multiples
                    // of 16 — the same grid the API generates on — so the AI
                    // result comes back at the selection's aspect ratio without
                    // the grid mismatch that makes the model zoom out and
                    // outpaint the borders. scaleToFit() is the safety net
                    // for any residual difference.
                    "size" to "${inputImageData.width}*${inputImageData.height}",
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

            val maxRetries = 1
            var retryCount = 0
            var imageSaved = false

            while (!imageSaved && retryCount < maxRetries) {
                coroutineContext.ensureActive()
                retryCount++
                try {
                    onDebug("  Sending API request (attempt $retryCount)...")
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
                        onDebug("❌ HTTP $responseCode — skipping image (no retry)")
                        continue
                    }

                    // Parse the response to extract image URLs
                    val imageUrl = extractImageUrl(rawBody)
                    if (imageUrl == null) {
                        onDebug("❌ No image URL found in response — skipping image (no retry)")
                        continue
                    }

                    onDebug("  Image URL received, downloading...")

                    // Download AI-edited area to a temp file
                    val tempFile = File(outputDir, ".ai_edit_temp_${index}_${System.currentTimeMillis()}.jpg")
                    downloadImage(imageUrl, tempFile)

                    if (selection.hasCrop || selection.hasMask) {
                        val ts = System.currentTimeMillis()
                        val baseName = "PYQAlbum_AI_Edit_${ts}_ai_edit_${index}"

                        // Debug 1: the exact image sent to the AI
                        // (cropped area / masked image, resized to the API size
                        // range while keeping the selection's aspect ratio).
                        val inputBytes = Base64.getDecoder().decode(inputImageData.base64)
                        val inputUri = saveImageBytesToMediaStore(
                            context, inputBytes, "${baseName}_input.jpg", onDebug
                        )
                        if (inputUri != null) {
                            onDebug("  📥 Saved AI input debug image: $inputUri")
                        }

                        // Debug 2: the raw AI result before any correction
                        // (downloaded as-is from the API).
                        val resultUri = saveImageFileToMediaStore(
                            context, tempFile, "${baseName}_result.jpg", onDebug
                        )
                        if (resultUri != null) {
                            onDebug("  📤 Saved AI result debug image: $resultUri")
                        }

                        // Composite the edited area back onto the original
                        val savedUri = compositeAndSaveToMediaStore(
                            context = context,
                            originalPath = sourcePath,
                            editedAreaPath = tempFile.absolutePath,
                            selection = selection,
                            suffix = "_ai_edit_${index}",
                            onDebug = onDebug,
                            fileName = "${baseName}.jpg"
                        )
                        if (savedUri != null) {
                            outputUris.add(savedUri)
                            imageSaved = true
                            onDebug("✅ Saved to gallery: $savedUri")
                        } else {
                            onDebug("❌ Failed to composite and save — skipping image")
                        }
                        // Clean up temp file
                        tempFile.delete()
                    } else {
                        // No crop/mask: save the raw AI result directly
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
                    onDebug("❌ Exception: ${e::class.simpleName}: ${e.message} — skipping image (no retry)")
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
     * Scale a bitmap to a size the qwen-image-2.0 API accepts while keeping
     * the aspect ratio (no cropping, no padding). The policy: upscale so the
     * shorter side is at least 512px, downscale so the longer side is at
     * most 2048px, then snap both dimensions to the nearest multiple of 16.
     * Returns the same bitmap when no resize is needed.
     *
     * The 16px grid matters: the API rounds the requested output size to the
     * nearest multiple of 16, so an un-snapped input (e.g. 1094x1433) gets
     * generated on a different grid (1088x1440). That mismatch makes the
     * model reframe the scene — the subject shrinks and the borders get
     * outpainted. Sending an already-snapped image and requesting that exact
     * size keeps input and output on the same grid.
     */
    private fun resizeToApiSize(bitmap: Bitmap): Bitmap {
        val MIN_SIDE = 512
        val MAX_SIDE = 2048
        val GRID = 16
        val w = bitmap.width
        val h = bitmap.height
        val minSide = minOf(w, h)
        val maxSide = maxOf(w, h)
        var scale = 1f
        if (minSide < MIN_SIDE) scale = maxOf(scale, MIN_SIDE.toFloat() / minSide)
        if (maxSide > MAX_SIDE) scale = minOf(scale, MAX_SIDE.toFloat() / maxSide)
        val nw = (((w * scale) / GRID).roundToInt() * GRID).coerceAtLeast(GRID)
        val nh = (((h * scale) / GRID).roundToInt() * GRID).coerceAtLeast(GRID)
        if (nw == w && nh == h) return bitmap
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
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

    /**
     * Prepare the source image for the AI request according to [selection]:
     *
     *  - Decodes the image and applies EXIF rotation so pixel coordinates
     *    match how the user saw it in the dialog.
     *  - If a brush [BrushMask] is present, fills everything outside the
     *    painted strokes with a blurred context pattern (see
     *    [applyMaskToBitmap]) so the API doesn't see a black void around the
     *    subject and reframe it.
     *  - If a crop rect is present, crops to that area; otherwise the whole
     *    (masked) image is used.
     *  - Resizes into the qwen-image-2.0 accepted range (min side 512px, max
     *    side 2048px, snapped to multiples of 16) keeping the aspect ratio.
     *  - Encodes as JPEG (the masked input is fully opaque, so no alpha is
     *    lost).
     *
     * @return [CroppedImageData] with the base64 string, the pixel dimensions
     *         sent to the AI, and the MIME type for the data URL.
     */
    private fun prepareSourceData(sourcePath: String, selection: EditSelection): CroppedImageData? {
        return try {
            val rawBitmap = BitmapFactory.decodeFile(sourcePath) ?: return null
            var bitmap = applyExifOrientation(sourcePath, rawBitmap)

            // Brush mask: make everything outside the strokes transparent.
            if (selection.hasMask) {
                val masked = applyMaskToBitmap(bitmap, selection.mask!!)
                if (masked !== bitmap) bitmap.recycle()
                bitmap = masked
            }

            // When a brush mask is used without an explicit crop rect, crop to
            // the bounding box of the painted strokes so the AI only sees the
            // brushed area — not the full image with a small transparent window.
            // Otherwise the AI stretches the tiny visible region to fill its
            // entire output canvas, and the composite only shows a sliver.
            if (selection.hasMask && !selection.hasCrop) {
                val bb = brushBoundingBox(selection.mask!!, bitmap.width, bitmap.height)
                if (bb.isValid()) {
                    val iw = bitmap.width
                    val ih = bitmap.height
                    val pixelRect = android.graphics.Rect(
                        (bb.left * iw).roundToInt().coerceIn(0, iw),
                        (bb.top * ih).roundToInt().coerceIn(0, ih),
                        (bb.right * iw).roundToInt().coerceIn(0, iw),
                        (bb.bottom * ih).roundToInt().coerceIn(0, ih)
                    )
                    if (pixelRect.width() > 0 && pixelRect.height() > 0) {
                        val cropped = Bitmap.createBitmap(
                            bitmap,
                            pixelRect.left, pixelRect.top,
                            pixelRect.width(), pixelRect.height()
                        )
                        bitmap.recycle()
                        bitmap = cropped
                    }
                }
            }

            // Crop to the selected rect (if any).
            if (selection.hasCrop) {
                val cropRect = selection.cropRect!!
                val iw = bitmap.width
                val ih = bitmap.height
                // roundToInt() instead of toInt() so pixel boundaries are the
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
                    bitmap.recycle()
                    return null
                }
                val cropped = Bitmap.createBitmap(
                    bitmap,
                    pixelRect.left, pixelRect.top,
                    pixelRect.width(), pixelRect.height()
                )
                if (cropped !== bitmap) bitmap.recycle()
                bitmap = cropped
            }

            // Resize to a size the qwen-image-2.0 API accepts (each side within
            // ~512..2048 px, aspect ratio preserved — no cropping, no padding)
            // so the input sent to the AI is exactly the area the user selected.
            // The output size is requested to match, and scaleToFit() handles
            // any residual difference.
            val resized = resizeToApiSize(bitmap)
            if (resized !== bitmap) bitmap.recycle()

            val outW = resized.width
            val outH = resized.height

            val bytes = java.io.ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
            resized.recycle()
            CroppedImageData(
                base64 = Base64.getEncoder().encodeToString(bytes.toByteArray()),
                width = outW,
                height = outH,
                mimeType = "image/jpeg"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Render a [BrushMask] into an opaque-white/transparent mask bitmap of
     * size [w]×[h]. Non-eraser strokes paint opaque white; eraser strokes
     * punch holes back to transparent (PorterDuff CLEAR). Stroke width is
     * [BrushStroke.radius] × the shorter side, matching the overlay preview.
     */
    private fun renderMaskBitmap(w: Int, h: Int, mask: BrushMask): Bitmap {
        val maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        val reference = minOf(w, h).toFloat()
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = android.graphics.Color.WHITE
        }
        val eraserPaint = Paint(paint).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        for (stroke in mask.strokes) {
            val pts = stroke.points
            if (pts.isEmpty()) continue
            val p = if (stroke.eraser) eraserPaint else paint
            p.strokeWidth = stroke.radius * reference
            if (pts.size == 1) {
                canvas.drawPoint(pts[0].x * w, pts[0].y * h, p)
            } else {
                val path = Path()
                path.moveTo(pts[0].x * w, pts[0].y * h)
                for (i in 1 until pts.size) {
                    path.lineTo(pts[i].x * w, pts[i].y * h)
                }
                canvas.drawPath(path, p)
            }
        }
        return maskBitmap
    }

    /**
     * Apply a [BrushMask] to [src]: everything outside the painted strokes is
     * replaced with a heavily blurred, desaturated copy of the source so the
     * AI input clearly reads as masked background around the edit region — not
     * the recognizable original photo. A fully transparent background is
     * flattened to solid black when the API converts the PNG to RGB, and the
     * model treats that void as ignorable — reframing/enlarging the masked
     * content to fill the canvas; likewise it reuses recognizable surroundings
     * as if they were real content. The blurred color blocks keep the scene's
     * layout as spatial anchors while the sharp masked area reads as the edit
     * target. Returns a new ARGB bitmap; the caller recycles [src] separately.
     */
    private fun applyMaskToBitmap(src: Bitmap, mask: BrushMask): Bitmap {
        val w = src.width
        val h = src.height
        val maskBitmap = renderMaskBitmap(w, h, mask)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Surrounding pattern: the source shrunk to ~1/64 and scaled back up
        // in doubling steps, so bilinear filtering smears it into a very heavy
        // blur, then desaturated so it no longer reads as the original photo —
        // it reads as masked background. Large-scale color blocks and layout
        // survive to anchor the subject's position/scale for the model, but
        // fine detail is gone and the surroundings clearly aren't content.
        var blurW = maxOf(1, w / 64)
        var blurH = maxOf(1, h / 64)
        var blurred = Bitmap.createScaledBitmap(src, blurW, blurH, true)
        while (blurW < w || blurH < h) {
            val nw = minOf(blurW * 2, w)
            val nh = minOf(blurH * 2, h)
            val next = Bitmap.createScaledBitmap(blurred, nw, nh, true)
            blurred.recycle()
            blurred = next
            blurW = nw
            blurH = nh
        }
        val bgPaint = Paint().apply {
            isFilterBitmap = true
            // "Background treatment": cut chroma to ~40% so the surrounding is
            // visually distinct from the sharp masked foreground, while the
            // remaining blurred colors still anchor the scene layout.
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.4f) })
        }
        canvas.drawBitmap(blurred, 0f, 0f, bgPaint)

        // On top of the heavy blur, scatter a random field of soft color blobs
        // sampled from the blurred tones. The surroundings then read as an
        // intentional mosaic-like texture instead of a single flat color:
        // random position, size and brightness with soft radial falloff, so
        // they look "meaningfully patterned" to the model but never like real
        // photo content.
        val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = java.util.Random(System.nanoTime())
        val blobCount = (24 + w * h / 40_000).coerceAtMost(140)
        val maxDim = maxOf(w, h)
        repeat(blobCount) {
            val cx = rnd.nextInt(w).toFloat()
            val cy = rnd.nextInt(h).toFloat()
            val radius = maxDim / 90f + rnd.nextFloat() * maxDim / 8f
            val base = blurred.getPixel(cx.toInt(), cy.toInt())
            val r = ((base shr 16 and 0xFF) + (rnd.nextInt(41) - 20)).coerceIn(0, 255)
            val g = ((base shr 8 and 0xFF) + (rnd.nextInt(41) - 20)).coerceIn(0, 255)
            val b = ((base and 0xFF) + (rnd.nextInt(41) - 20)).coerceIn(0, 255)
            val center = Color.argb(230 + rnd.nextInt(26), r, g, b)
            blobPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(center, center and 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, blobPaint)
        }
        blobPaint.shader = null

        blurred.recycle()

        // Masked foreground: source pixels inside the painted strokes only,
        // composited on top of the pattern background.
        val masked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mCanvas = Canvas(masked)
        mCanvas.drawBitmap(src, 0f, 0f, null)
        val dstIn = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        mCanvas.drawBitmap(maskBitmap, 0f, 0f, dstIn)
        canvas.drawBitmap(masked, 0f, 0f, null)
        masked.recycle()
        maskBitmap.recycle()
        return result
    }

    /**
     * Compute the axis-aligned bounding box of all painted strokes in a
     * [BrushMask]. Coordinates are normalized 0f..1f relative to the image,
     * with a 5% padding so the AI input carries visible surrounding context
     * (the blurred pattern placed there by [applyMaskToBitmap]) instead of a
     * razor-thin border that leaves the subject subject to reframing.
     * Returns a [CropRect] that covers the painted area.
     */
    private fun brushBoundingBox(mask: BrushMask, w: Int, h: Int): CropRect {
        val allStrokes = mask.strokes
        if (allStrokes.isEmpty()) return CropRect(0f, 0f, 1f, 1f)
        var minX = 1f; var minY = 1f
        var maxX = 0f; var maxY = 0f
        val refPx = minOf(w, h).toFloat()
        for (stroke in allStrokes) {
            // Radius in normalized space: stroke radius is relative to the
            // shorter image side, so the half-extent in x is r*refPx/w and
            // in y is r*refPx/h.
            val rx = if (w > 0) stroke.radius * refPx / w else 0f
            val ry = if (h > 0) stroke.radius * refPx / h else 0f
            for (p in stroke.points) {
                val x0 = p.x - rx; val x1 = p.x + rx
                val y0 = p.y - ry; val y1 = p.y + ry
                if (x0 < minX) minX = x0
                if (y0 < minY) minY = y0
                if (x1 > maxX) maxX = x1
                if (y1 > maxY) maxY = y1
            }
        }
        val margin = 0.05f
        return CropRect(
            (minX - margin).coerceIn(0f, 1f),
            (minY - margin).coerceIn(0f, 1f),
            (maxX + margin).coerceIn(0f, 1f),
            (maxY + margin).coerceIn(0f, 1f)
        )
    }

    /**
     * Composite the AI-edited area bitmap onto the original image at the crop
     * position, then save the result to the device gallery via MediaStore.
     *
     * @param fileName Optional explicit output file name; when null a name is
     *   generated from [suffix] and the current time.
     * @return The MediaStore content:// URI string, or null on failure.
     */
    private fun compositeAndSaveToMediaStore(
        context: Context,
        originalPath: String,
        editedAreaPath: String,
        selection: EditSelection,
        suffix: String,
        onDebug: (String) -> Unit,
        fileName: String? = null
    ): String? {
        return try {
            val cropRect = selection.cropRect?.takeIf { it.isValid() }
            val mask = selection.mask?.takeIf { !it.isEmpty() }

            // Load original and edited bitmaps (apply EXIF rotation to both
            // so pixel positions align with how the dialog displayed the image)
            val rawOriginal = BitmapFactory.decodeFile(originalPath) ?: return null
            val original = applyExifOrientation(originalPath, rawOriginal)
            val rawEdited = BitmapFactory.decodeFile(editedAreaPath) ?: run {
                original.recycle(); return null
            }

            // When no explicit crop rect was set but there's a brush mask,
            // derive a crop rect from the bounding box of the painted strokes.
            // The AI input was already cropped to this bounding box in
            // prepareSourceData, so the AI result is brush-area-sized.
            val effectiveCropRect = cropRect
                ?: mask?.let { brushBoundingBox(it, original.width, original.height).takeIf { bb -> bb.isValid() } }

            val iw = original.width
            val ih = original.height

            var edited = applyExifOrientation(editedAreaPath, rawEdited)

            // Where the edited result lands on the original:
            //  - explicit crop rect: the user-selected rectangle
            //  - mask bounding box: the extent of the brush strokes
            //  - neither: the whole image
            val dstRect: android.graphics.Rect
            val expectedW: Int
            val expectedH: Int

            if (effectiveCropRect != null) {
                // roundToInt() instead of toInt() — must match prepareSourceData
                dstRect = android.graphics.Rect(
                    (effectiveCropRect.left * iw).roundToInt().coerceIn(0, iw),
                    (effectiveCropRect.top * ih).roundToInt().coerceIn(0, ih),
                    (effectiveCropRect.right * iw).roundToInt().coerceIn(0, iw),
                    (effectiveCropRect.bottom * ih).roundToInt().coerceIn(0, ih)
                )
                expectedW = dstRect.width()
                expectedH = dstRect.height()
            } else {
                dstRect = android.graphics.Rect(0, 0, iw, ih)
                expectedW = iw
                expectedH = ih
            }

            onDebug("  AI result: ${edited.width}x${edited.height}, expected area: ${expectedW}x${expectedH}")

            // Scale the AI result to fit the destination.
            // If the aspect ratio differs, center-crop first to avoid stretching.
            var scaledEdited = if (edited.width != expectedW || edited.height != expectedH) {
                val scaled = scaleToFit(edited, expectedW, expectedH)
                edited.recycle()
                scaled
            } else {
                edited
            }

            // Confine the visible edit to the brush mask (if any): mask bitmap
            // rendered at original resolution, cropped/scaled to the edited
            // bitmap size, then ANDed into its alpha channel. Everything
            // outside the painted strokes keeps the original pixels.
            if (mask != null) {
                var maskBitmap = renderMaskBitmap(iw, ih, mask)
                if (effectiveCropRect != null) {
                    val croppedMask = Bitmap.createBitmap(
                        maskBitmap,
                        dstRect.left, dstRect.top,
                        dstRect.width(), dstRect.height()
                    )
                    if (croppedMask !== maskBitmap) maskBitmap.recycle()
                    maskBitmap = croppedMask
                }
                if (maskBitmap.width != scaledEdited.width || maskBitmap.height != scaledEdited.height) {
                    val scaledMask = scaleToFit(maskBitmap, scaledEdited.width, scaledEdited.height)
                    if (scaledMask !== maskBitmap) maskBitmap.recycle()
                    maskBitmap = scaledMask
                }
                val maskedEdited = Bitmap.createBitmap(
                    scaledEdited.width, scaledEdited.height, Bitmap.Config.ARGB_8888
                )
                val mCanvas = Canvas(maskedEdited)
                mCanvas.drawBitmap(scaledEdited, 0f, 0f, null)
                val dstIn = Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                mCanvas.drawBitmap(maskBitmap, 0f, 0f, dstIn)
                maskBitmap.recycle()
                scaledEdited.recycle()
                scaledEdited = maskedEdited
            }

            // Composite onto the original using integer-precise Rect dst
            // to avoid sub-pixel rendering offsets when drawing at the crop position.
            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            // isFilterBitmap=false since the AI result is already pre-scaled to
            // the expected size — no filtering needed for a 1:1 pixel copy, and
            // it prevents bilinear edge bleed that could make the composite seem
            // slightly larger than the selection.
            val paint = Paint().apply { isFilterBitmap = false }
            canvas.drawBitmap(scaledEdited,
                android.graphics.Rect(0, 0, scaledEdited.width, scaledEdited.height),
                dstRect,
                paint)

            // Save via MediaStore (gallery-visible)
            val outName = fileName ?: "PYQAlbum_AI_Edit_${System.currentTimeMillis()}$suffix.jpg"
            val savedUri = saveToMediaStore(context, result, outName, onDebug)

            // Clean up — recycle() is idempotent on Android, so double-free is harmless
            original.recycle()
            edited.recycle()
            scaledEdited.recycle()
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

    /**
     * Save arbitrary image bytes (e.g. the image sent to the AI, or the raw
     * AI result) to the gallery for debugging. Decodes to a Bitmap first so
     * the stored copy is a readable JPEG; if decoding fails, the raw bytes
     * are written as-is.
     */
    private fun saveImageBytesToMediaStore(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        onDebug: (String) -> Unit
    ): String? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                val uri = saveToMediaStore(context, bitmap, fileName, onDebug)
                bitmap.recycle()
                uri
            } else {
                onDebug("  ⚠️ Could not decode debug image bytes, storing raw bytes: $fileName")
                saveRawBytesToMediaStore(context, bytes, fileName, onDebug)
            }
        } catch (e: Exception) {
            onDebug("  ❌ Debug image save failed: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Convenience wrapper: save an image file to the gallery for debugging.
     */
    private fun saveImageFileToMediaStore(
        context: Context,
        file: File,
        fileName: String,
        onDebug: (String) -> Unit
    ): String? {
        return try {
            if (!file.exists()) {
                onDebug("  ⚠️ Debug file missing: ${file.absolutePath}")
                return null
            }
            saveImageBytesToMediaStore(context, file.readBytes(), fileName, onDebug)
        } catch (e: Exception) {
            onDebug("  ❌ Debug file read failed: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Write raw bytes to the gallery without decoding. Used as a fallback
     * when the image format is not decodable by BitmapFactory.
     */
    private fun saveRawBytesToMediaStore(
        context: Context,
        bytes: ByteArray,
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
                    out.write(bytes)
                    true
                } ?: false

                if (!written) {
                    context.contentResolver.delete(uri, null, null)
                    return null
                }

                val updateValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, updateValues, null, null)
                uri.toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PYQAlbum"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out -> out.write(bytes) }
                try {
                    android.media.MediaScannerConnection.scanFile(
                        context, arrayOf(file.absolutePath), null, null
                    )
                } catch (_: Exception) {}
                file.absolutePath
            }
        } catch (e: Exception) {
            onDebug("  ❌ Raw debug save failed: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // ---- Chat-compatible fallback (updated with crop support) ----

    /**
     * Try the chat-completions compatible endpoint as a fallback.
     * When [selections] is provided, applies the same crop/mask→composite flow.
     */
    suspend fun editImagesChatCompatible(
        apiKey: String,
        modelName: String,
        sourceImagePaths: List<String>,
        selections: Map<String, EditSelection> = emptyMap(),
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

            val selection = selections[sourcePath] ?: EditSelection()

            val inputImageData = prepareSourceData(sourcePath, selection)
            if (inputImageData == null) {
                completed++
                onProgress(completed, total)
                continue
            }

            // Same image ordering as editImages(): target reference image first,
            // source image to edit second, then the prompt.
            val content = mutableListOf<Map<String, Any>>()
            if (targetImagePath != null) {
                val tb64 = encodeImageToBase64(targetImagePath)
                if (tb64 != null) {
                    content.add(mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:image/jpeg;base64,$tb64")
                    ))
                }
            }
            content.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:${inputImageData.mimeType};base64,${inputImageData.base64}")
            ))
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

                            if (selection.hasCrop || selection.hasMask) {
                                val savedUri = compositeAndSaveToMediaStore(
                                    context, sourcePath, tempFile.absolutePath, selection,
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