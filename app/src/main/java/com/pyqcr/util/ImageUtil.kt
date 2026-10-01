package com.pyqcr.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pyqcr.ui.util.MediaStoreUtils
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Image utility functions for batch resize and rescale operations.
 */
object ImageUtil {

    /**
     * Resize image to 50% of original size (inSampleSize=2).
     * Overwrites the original or saves to outputUri.
     */
    fun resize50Percent(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        outputUri: Uri
    ): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 2  // Decode at 50% size
            }
            val srcBitmap = contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return false

            val result = contentResolver.openOutputStream(outputUri)?.use { out ->
                srcBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            } ?: false

            srcBitmap.recycle()
            result
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Rescale image to square by adding padding (background color) to the shorter sides.
     * @param bgColor ARGB color for padding background. Default black.
     */
    fun rescaleToSquare(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        outputUri: Uri,
        bgColor: Int = Color.BLACK
    ): Boolean {
        return try {
            val srcBitmap = contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return false

            val size = maxOf(srcBitmap.width, srcBitmap.height)
            val dstBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(dstBitmap)

            // Fill background
            canvas.drawColor(bgColor)

            // Center the original image
            val left = (size - srcBitmap.width) / 2f
            val top = (size - srcBitmap.height) / 2f
            canvas.drawBitmap(srcBitmap, left, top, Paint().apply {
                isFilterBitmap = true
            })

            val result = contentResolver.openOutputStream(outputUri)?.use { out ->
                dstBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            } ?: false

            srcBitmap.recycle()
            dstBitmap.recycle()
            result
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Build a collage bitmap by joining the source images edge-to-edge, without
     * letterboxing and without downscaling the sources.
     *
     * Images are arranged in [columns] columns, top-to-bottom. Every row fills
     * the full canvas width: each image keeps its aspect ratio and is scaled
     * only as much as needed so the row's combined width matches the widest row
     * (measured at the tallest source's height). The canvas is the sum of the
     * row sizes, so e.g. a 2×2 collage of four 12MP photos yields a ~48MP image
     * (≈4× one original) — and mixed landscape/portrait sets are joined flush
     * with no background bars, because each image has its own proportional cell.
     *
     * Output size is bounded only for safety: if the joined canvas would exceed
     * [maxCanvasPixels] (or ~70% of the app heap), everything is scaled down
     * uniformly so the request cannot OOM the process. Sources are decoded one
     * at a time, at most as large as the cell they occupy in the final canvas,
     * and recycled immediately.
     *
     * @return the collage bitmap, or null if none of the sources could be decoded.
     */
    fun createCollage(
        context: Context,
        sourceUris: List<String>,
        columns: Int = 3,
        backgroundColor: Int = Color.WHITE,
        maxCanvasPixels: Long = 120_000_000L
    ): Bitmap? {
        val contentResolver = context.contentResolver
        val n = sourceUris.size
        if (n == 0) return null
        val colCount = columns.coerceAtLeast(1)

        // Bounds pass — read each source's intrinsic size without decoding pixels.
        // Cells stay aligned with [sourceUris] (unreadable sources get a zero
        // placeholder) so the draw pass can map a cell back to its input URI.
        data class Cell(val width: Int, val height: Int)
        val cells = ArrayList<Cell>(n)
        sourceUris.forEach { uriString ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
            } catch (e: Exception) {
                // ignore — this source is skipped
            }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                cells.add(Cell(bounds.outWidth, bounds.outHeight))
            } else {
                cells.add(Cell(0, 0))
            }
        }
        // List of (index into sourceUris, bounds) for the readable sources only.
        val valid = cells.mapIndexedNotNull { i, cell -> if (cell.width > 0) i to cell else null }
        if (valid.isEmpty()) return null

        // Group the valid sources into rows of at most [colCount] images.
        val rows = ArrayList<List<Int>>()
        var index = 0
        while (index < valid.size) {
            val row = ArrayList<Int>()
            repeat(minOf(colCount, valid.size - index)) { row.add(index); index++ }
            rows.add(row)
        }

        // Layout: canvas width = the widest row when each image sits at the
        // tallest source's height; every row then fills that same width, so its
        // height is width ÷ (sum of its aspect ratios) and nothing is letterboxed.
        val rowAspectSums = rows.map { row ->
            row.sumOf { valid[it].second.width.toDouble() / valid[it].second.height }
        }
        val refHeight = valid.maxOf { it.second.height }
        val canvasWidthD = refHeight.toDouble() * (rowAspectSums.maxOrNull() ?: 0.0)
        val rowHeights = rowAspectSums.map { (canvasWidthD / it).toFloat() }
        val canvasHeight = rowHeights.sum()
        val canvasWidth = canvasWidthD.toFloat()

        // Safety cap: keep the output inside [maxCanvasPixels] and ~70% of the
        // app heap (the canvas bitmap plus the one in-flight source decoding must
        // both fit). A uniform scale keeps the flush layout intact.
        val totalPixels = canvasWidthD * canvasHeight.toDouble()
        val heapMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.memoryClass ?: 256
        val heapBudget = heapMb.toLong() * 175_000L  // ≈ 70% of heap bytes ÷ 4 bytes/pixel
        val budget = minOf(maxCanvasPixels, heapBudget.coerceAtLeast(8_000_000L))
        val scale = if (totalPixels > budget) {
            kotlin.math.sqrt(budget.toDouble() / totalPixels).toFloat()
        } else 1f

        val outWidth = (canvasWidth * scale).toInt().coerceAtLeast(1)
        val outHeight = (canvasHeight * scale).toInt().coerceAtLeast(1)
        val outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)
        canvas.drawColor(backgroundColor)
        val paint = Paint().apply { isFilterBitmap = true }

        // Draw pass — decode each source just large enough for its final cell
        // (power-of-two inSampleSize), draw it flush with its neighbours, recycle.
        var y = 0f
        rows.forEachIndexed { rowIndex, row ->
            val rowHeight = rowHeights[rowIndex]
            var x = 0f
            row.forEach { entry ->
                val (srcIndex, cell) = valid[entry]
                val drawWidth = (rowHeight * cell.width / cell.height).toFloat()
                val finalW = drawWidth * scale
                val finalH = rowHeight * scale
                var sample = 1
                while (cell.width / (sample shl 1) >= finalW && cell.height / (sample shl 1) >= finalH) {
                    sample = sample shl 1
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val src = try {
                    contentResolver.openInputStream(Uri.parse(sourceUris[srcIndex]))?.use { input ->
                        BitmapFactory.decodeStream(input, null, opts)
                    }
                } catch (e: Exception) {
                    null
                }
                if (src != null) {
                    canvas.drawBitmap(
                        src,
                        null,
                        RectF(x * scale, y * scale, (x + drawWidth) * scale, (y + rowHeight) * scale),
                        paint
                    )
                    src.recycle()
                }
                x += drawWidth
            }
            y += rowHeight
        }
        return outBitmap
    }

    /**
     * Save a bitmap as a JPEG into Pictures/PYQAlbum/.
     * API 29+: inserted directly through MediaStore (RELATIVE_PATH).
     * Older APIs: written as a file and scanned into MediaStore.
     *
     * @return the content:// URI of the saved image, or a file:// URI fallback
     *         on old APIs when the MediaStore lookup fails; null on error.
     */
    fun saveCollageBitmap(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/PYQAlbum"
                    )
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
                uri.toString()
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PYQAlbum"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                MediaStoreUtils.scanFile(context, file.absolutePath)
                // Prefer the MediaStore content URI; fall back to a plain file URI.
                queryContentUriByPath(context, file.absolutePath)?.toString()
                    ?: Uri.fromFile(file).toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun queryContentUriByPath(context: Context, path: String): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DATA}=?"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}