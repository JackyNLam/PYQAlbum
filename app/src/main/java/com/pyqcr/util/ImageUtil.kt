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
     * Build a collage bitmap from multiple image URIs in the order given.
     *
     * Images are laid out in a uniform grid of [columns] columns on a
     * [backgroundColor] background. Every cell has the same size (the largest
     * decoded image's width/height), so images with different aspect ratios are
     * scaled to fit and letterboxed — the empty space around them shows the
     * chosen background color instead of ripping the layout. Very large images
     * are downsampled (power-of-two) so the longest side of each input is at
     * most [maxSourceDimension], and the output bitmap is capped at
     * [maxCanvasDimension] on its longest side to keep memory bounded.
     *
     * @return the collage bitmap, or null if none of the sources could be decoded.
     */
    fun createCollage(
        context: Context,
        sourceUris: List<String>,
        columns: Int = 3,
        backgroundColor: Int = Color.WHITE,
        maxSourceDimension: Int = 2048,
        maxCanvasDimension: Int = 4096
    ): Bitmap? {
        val contentResolver = context.contentResolver
        val n = sourceUris.size
        if (n == 0) return null
        val colCount = columns.coerceAtLeast(1)

        // First pass: read each source's size and the sample size needed to
        // decode it (kept per cell so the second pass decodes at these exact
        // dimensions, which keeps the layout seamless).
        data class Cell(val width: Int, val height: Int, val sampleSize: Int)
        val cells = ArrayList<Cell>(n)
        sourceUris.forEach { uriString ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
            } catch (e: Exception) {
                // ignore — this cell is skipped
            }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sampleSize = 1
                while (kotlin.math.max(bounds.outWidth, bounds.outHeight) / sampleSize > maxSourceDimension) {
                    sampleSize *= 2
                }
                cells.add(Cell(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize, sampleSize))
            } else {
                cells.add(Cell(0, 0, 1))
            }
        }
        val validCells = cells.filter { it.width > 0 }
        if (validCells.isEmpty()) return null

        // Uniform grid: all cells share the largest image's size, so the empty
        // space inside a cell (letterboxing) is filled with the background color.
        val cellW = validCells.maxOf { it.width }
        val cellH = validCells.maxOf { it.height }
        val rows = kotlin.math.ceil(n.toDouble() / colCount).toInt()

        val canvasWidth = cellW * colCount
        val canvasHeight = cellH * rows

        // Keep the output bitmap bounded — very wide/tall layouts would otherwise OOM.
        val scale = minOf(
            1f,
            maxCanvasDimension.toFloat() / canvasWidth.coerceAtLeast(1),
            maxCanvasDimension.toFloat() / canvasHeight.coerceAtLeast(1)
        )
        val outWidth = (canvasWidth * scale).toInt().coerceAtLeast(1)
        val outHeight = (canvasHeight * scale).toInt().coerceAtLeast(1)

        val outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)
        canvas.drawColor(backgroundColor)
        val paint = Paint().apply { isFilterBitmap = true }

        // Second pass: decode each image and draw it centered, scaled to fit its
        // cell; leftover space shows the background color.
        cells.forEachIndexed { index, cell ->
            if (cell.width == 0) return@forEachIndexed
            val opts = BitmapFactory.Options().apply { inSampleSize = cell.sampleSize }
            val src = try {
                contentResolver.openInputStream(Uri.parse(sourceUris[index]))?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            } catch (e: Exception) {
                null
            } ?: return@forEachIndexed

            val row = index / colCount
            val col = index % colCount
            val fitScale = minOf(cellW.toFloat() / cell.width, cellH.toFloat() / cell.height)
            val drawW = cell.width * fitScale
            val drawH = cell.height * fitScale
            val left = (col * cellW + (cellW - drawW) / 2f) * scale
            val top = (row * cellH + (cellH - drawH) / 2f) * scale
            canvas.drawBitmap(
                src,
                null,
                RectF(left, top, left + drawW * scale, top + drawH * scale),
                paint
            )
            src.recycle()
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