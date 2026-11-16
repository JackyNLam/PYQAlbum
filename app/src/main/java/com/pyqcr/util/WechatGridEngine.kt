package com.pyqcr.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pyqcr.ui.util.MediaStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Port of the WechatGrid web tool (https://jackynlam.github.io/WechatGrid/).
 *
 * Nine long JPEG "moment" images are produced from one center photo plus a set
 * of hidden photos. Every output keeps the same center tile in the middle, so
 * WeChat's 3x3 grid preview shows the center photo while tapping any of the
 * nine reveals a different photo underneath — the classic "hidden photos" trick.
 */
object WechatGridEngine {

    /** Website default; also what fits comfortably in a phone's heap. */
    const val DEFAULT_TILE = 1080

    /**
     * Pixels the renderer may allocate at once: the 3x3 center square plus the
     * tallest output stack. Above this Android starts throwing OOM on mid-range
     * devices, so the requested tile size is scaled down instead.
     */
    private const val BUDGET_PIXELS = 20_000_000L

    private const val JPEG_QUALITY = 100
    private const val OUTPUT_DIR = "PYQAlbum/WechatGrid"

    /** All stack-mode totals offered by the website (even numbers, 18..90). */
    val MODES: List<Int> = (18..90 step 2).toList()

    data class Stack(
        val top: Int,
        val bottom: Int,
        val perStack: Int,
        val heightMultiplier: Int,
        val start: Int
    )

    data class Config(val total: Int, val stacks: List<Stack>) {
        /** Tiles in the tallest output, including the center one. */
        val maxRows: Int get() = stacks.maxOfOrNull { it.heightMultiplier } ?: 1
    }

    data class Result(
        val uris: List<String>,
        /** Effective tile size after the memory cap. */
        val tileWidth: Int,
        val tileHeight: Int,
        /** True when the requested size was reduced to fit memory. */
        val capped: Boolean,
        /** Photos that could not be decoded (drawn as black tiles). */
        val unreadable: Int
    )

    /**
     * Distribute [total] hidden photos over the 9 outputs: every output keeps
     * `top == bottom`, and the first stacks get one extra photo per side when
     * the total is not divisible by 9 pairs (website `getModeConfig`).
     */
    fun config(total: Int): Config {
        val half = total / 2
        val base = half / 9
        val extra = half - base * 9
        val stacks = ArrayList<Stack>(9)
        var start = 0
        for (i in 0 until 9) {
            val top = base + if (i < extra) 1 else 0
            val perStack = top * 2
            stacks += Stack(
                top = top,
                bottom = top,
                perStack = perStack,
                heightMultiplier = perStack + 1,
                start = start
            )
            start += perStack
        }
        return Config(total, stacks)
    }

    /** Dropdown label, e.g. "54 Photos (3 top + 3 bottom)" or "20 Photos (4×1 + 2×8)". */
    fun modeLabel(total: Int): String {
        val stacks = config(total).stacks
        val first = stacks.firstOrNull()?.perStack ?: return "$total Photos"
        val uniform = stacks.all { it.perStack == first }
        if (uniform) {
            return "$total Photos (${first / 2} top + ${first / 2} bottom)"
        }
        val groups = ArrayList<String>()
        var index = 0
        while (index < stacks.size) {
            val perStack = stacks[index].perStack
            var count = 0
            while (index < stacks.size && stacks[index].perStack == perStack) {
                count++
                index++
            }
            groups += "${perStack}×$count"
        }
        return "$total Photos (${groups.joinToString(" + ")})"
    }

    /**
     * Scale [tileWidth] x [tileHeight] down until the 3x3 center square plus
     * the tallest stack fits [BUDGET_PIXELS].
     */
    private fun effectiveTile(config: Config, tileWidth: Int, tileHeight: Int): Triple<Int, Int, Boolean> {
        val w = tileWidth.coerceAtLeast(100)
        val h = tileHeight.coerceAtLeast(100)
        val factor = 9.0 + config.maxRows.toDouble()
        val needed = w.toDouble() * h.toDouble() * factor
        if (needed <= BUDGET_PIXELS) return Triple(w, h, false)
        val scale = kotlin.math.sqrt(BUDGET_PIXELS / needed)
        val newW = (w * scale).toInt().coerceAtLeast(100)
        val newH = (h * scale).toInt().coerceAtLeast(100)
        return Triple(newW, newH, true)
    }

    /**
     * Render the nine outputs and save them as `moment-01.jpg` … `moment-09.jpg`
     * under `Pictures/PYQAlbum/WechatGrid`, published so WeChat's picker sees
     * them. [others] must hold exactly `config.total` URIs; group `i` feeds
     * output `i` in selection order.
     */
    suspend fun generate(
        context: Context,
        centerUri: String,
        others: List<String>,
        tileWidth: Int,
        tileHeight: Int,
        rotatePortrait: Boolean,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): Result? = withContext(Dispatchers.Default) {
        val config = config(others.size)
        if (others.size != config.total) return@withContext null
        val (tileW, tileH, capped) = effectiveTile(config, tileWidth, tileHeight)

        onProgress(0, "Loading center photo…")
        val center = ImageEditEngine.decodeBitmap(
            context,
            centerUri,
            maxDim = maxOf(tileW, tileH) * 3
        ) ?: return@withContext null

        // The center photo is padded to a square on white, then stretched over
        // the whole 3x3 block and sliced — exactly what splitCenterImage does.
        val squareSide = maxOf(center.width, center.height).coerceAtLeast(1)
        val blockW = tileW * 3
        val blockH = tileH * 3
        val block = try {
            Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            center.recycle()
            return@withContext null
        }
        Canvas(block).apply { drawColor(Color.WHITE) }
        Canvas(block).drawBitmap(
            center,
            null,
            RectF(
                (blockW - center.width * (blockW.toFloat() / squareSide)) / 2f,
                (blockH - center.height * (blockH.toFloat() / squareSide)) / 2f,
                (blockW + center.width * (blockW.toFloat() / squareSide)) / 2f,
                (blockH + center.height * (blockH.toFloat() / squareSide)) / 2f
            ),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        center.recycle()

        val uris = ArrayList<String>(9)
        var unreadable = 0
        try {
            for (groupIndex in 0 until 9) {
                yield()
                val stackConfig = config.stacks[groupIndex]
                onProgress(
                    groupIndex,
                    "Building image ${groupIndex + 1} / 9…"
                )
                val group = others.subList(
                    stackConfig.start,
                    stackConfig.start + stackConfig.perStack
                )
                val stack = try {
                    Bitmap.createBitmap(
                        tileW,
                        tileH * stackConfig.heightMultiplier,
                        Bitmap.Config.ARGB_8888
                    )
                } catch (e: OutOfMemoryError) {
                    return@withContext null
                }
                val canvas = Canvas(stack)
                canvas.drawColor(Color.BLACK)

                // Center slice: middle row/column of the 3x3 center block.
                canvas.drawBitmap(
                    block,
                    Rect((groupIndex % 3) * tileW, (groupIndex / 3) * tileH,
                        (groupIndex % 3 + 1) * tileW, (groupIndex / 3 + 1) * tileH),
                    Rect(0, stackConfig.top * tileH, tileW,
                        (stackConfig.top + 1) * tileH),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )

                for (j in group.indices) {
                    val bitmap = ImageEditEngine.decodeBitmap(
                        context,
                        group[j],
                        maxDim = maxOf(tileW, tileH)
                    )
                    if (bitmap == null) {
                        unreadable++
                        continue
                    }
                    val row = if (j < stackConfig.top) j else j + 1
                    drawContained(
                        canvas,
                        bitmap,
                        RectF(0f, row * tileH.toFloat(), tileW.toFloat(),
                            (row + 1) * tileH.toFloat()),
                        rotatePortrait
                    )
                    bitmap.recycle()
                }

                val uri = saveJpeg(
                    context,
                    stack,
                    "moment-${(groupIndex + 1).toString().padStart(2, '0')}.jpg"
                )
                stack.recycle()
                if (uri == null) return@withContext null
                uris += uri
            }
        } finally {
            if (!block.isRecycled) block.recycle()
        }
        onProgress(9, if (unreadable > 0) "Done — $unreadable photo(s) could not be read" else "Done")
        Result(uris, tileW, tileH, capped, unreadable)
    }

    /** Same geometry as the website's drawContainRect (black bars, optional 90° turn). */
    private fun drawContained(canvas: Canvas, src: Bitmap, rect: RectF, rotatePortrait: Boolean) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val black = Paint().apply { color = Color.BLACK }
        canvas.drawRect(rect, black)
        if (rotatePortrait && src.height > src.width) {
            val rotated = Bitmap.createBitmap(src.height, src.width, Bitmap.Config.ARGB_8888)
            val m = Matrix().apply { postRotate(90f) }
            Canvas(rotated).drawBitmap(src, m, paint)
            val scale = minOf(rect.width() / rotated.width, rect.height() / rotated.height)
            val dw = rotated.width * scale
            val dh = rotated.height * scale
            canvas.drawBitmap(
                rotated,
                null,
                RectF(
                    rect.centerX() - dw / 2f,
                    rect.centerY() - dh / 2f,
                    rect.centerX() + dw / 2f,
                    rect.centerY() + dh / 2f
                ),
                paint
            )
            rotated.recycle()
            return
        }
        val scale = minOf(rect.width() / src.width, rect.height() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        canvas.drawBitmap(
            src,
            null,
            RectF(
                rect.centerX() - dw / 2f,
                rect.centerY() - dh / 2f,
                rect.centerX() + dw / 2f,
                rect.centerY() + dh / 2f
            ),
            paint
        )
    }

    private fun saveJpeg(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/" + OUTPUT_DIR
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return null
                val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                } ?: false
                if (!written) {
                    context.contentResolver.delete(uri, null, null)
                    return null
                }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                MediaStoreUtils.publishImage(context, uri)
                uri.toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    OUTPUT_DIR
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                MediaStoreUtils.scanFile(context, file.absolutePath)
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}