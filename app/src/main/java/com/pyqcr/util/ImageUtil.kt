package com.pyqcr.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
     * Build a collage bitmap from multiple image URIs.
     *
     * Images are placed at their natural (original) size, packed tightly in a
     * grid with no padding and no background fill: each image keeps its own
     * aspect ratio and the output bitmap is exactly large enough to hold them
     * all. Very large images are downsampled (power-of-two) so the longest side
     * of each input is at most [maxSourceDimension], keeping memory bounded.
     *
     * @return the collage bitmap, or null if none of the sources could be decoded.
     */
    fun createCollage(
        context: Context,
        sourceUris: List<String>,
        maxSourceDimension: Int = 2048
    ): Bitmap? {
        val contentResolver = context.contentResolver
        val n = sourceUris.size
        if (n == 0) return null

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
        if (cells.none { it.width > 0 }) return null

        val columns = when {
            cells.size <= 3 -> cells.size
            else -> minOf(3, kotlin.math.ceil(kotlin.math.sqrt(cells.size.toDouble())).toInt())
        }
        val rows = kotlin.math.ceil(cells.size.toDouble() / columns).toInt()

        // Layout: images flow left-to-right; each row is as tall as its tallest
        // image and each row's width is the sum of its images' widths, so the
        // canvas exactly matches the images with nothing left over.
        val rowHeights = IntArray(rows)
        val xOffsets = IntArray(cells.size)
        val rowWidths = IntArray(rows)
        var running = 0
        cells.forEachIndexed { index, cell ->
            val row = index / columns
            if (index % columns == 0) running = 0
            xOffsets[index] = running
            running += cell.width
            rowWidths[row] = running
            rowHeights[row] = maxOf(rowHeights[row], cell.height)
        }
        val yOffsets = IntArray(rows)
        for (row in 1 until rows) {
            yOffsets[row] = yOffsets[row - 1] + rowHeights[row - 1]
        }
        val totalWidth = rowWidths.maxOrNull() ?: 0
        val totalHeight = rowHeights.sum()

        val outBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)
        val paint = Paint().apply { isFilterBitmap = true }

        // Second pass: decode each image and draw it at its natural size.
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
            canvas.drawBitmap(
                src,
                xOffsets[index].toFloat(),
                yOffsets[index / columns].toFloat(),
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