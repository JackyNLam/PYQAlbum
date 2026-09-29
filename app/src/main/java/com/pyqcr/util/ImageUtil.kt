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
     * Build a grid collage bitmap from multiple image URIs.
     * Images are laid out in a roughly square grid; each image is fit-centered
     * (contain) in its cell with [innerPadding] spacing on [bgColor] background.
     *
     * @return the collage bitmap, or null if none of the sources could be decoded.
     */
    fun createCollage(
        context: Context,
        sourceUris: List<String>,
        cellSize: Int = 600,
        innerPadding: Int = 4,
        bgColor: Int = Color.WHITE
    ): Bitmap? {
        val contentResolver = context.contentResolver
        val n = sourceUris.size
        if (n == 0) return null

        val columns = when {
            n <= 3 -> n
            else -> minOf(3, kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt())
        }
        val rows = kotlin.math.ceil(n.toDouble() / columns).toInt()
        val totalWidth = columns * cellSize + (columns + 1) * innerPadding
        val totalHeight = rows * cellSize + (rows + 1) * innerPadding

        val outBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)
        canvas.drawColor(bgColor)
        val paint = Paint().apply { isFilterBitmap = true }

        var anyDecoded = false
        sourceUris.forEachIndexed { index, uriString ->
            val row = index / columns
            val col = index % columns
            val cellLeft = innerPadding + col * (cellSize + innerPadding)
            val cellTop = innerPadding + row * (cellSize + innerPadding)

            // Read bounds first so we can pick a safe decode sample size
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
            } catch (e: Exception) {
                // ignore — this cell stays empty
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@forEachIndexed

            var sampleSize = 1
            while (kotlin.math.max(bounds.outWidth, bounds.outHeight) / sampleSize > cellSize * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val src = try {
                contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            } catch (e: Exception) {
                null
            } ?: return@forEachIndexed

            anyDecoded = true
            val scale = minOf(
                cellSize.toFloat() / src.width,
                cellSize.toFloat() / src.height
            )
            val width = (src.width * scale).toInt().coerceAtLeast(1)
            val height = (src.height * scale).toInt().coerceAtLeast(1)
            val left = cellLeft + (cellSize - width) / 2f
            val top = cellTop + (cellSize - height) / 2f
            val scaled = Bitmap.createScaledBitmap(src, width, height, true)
            canvas.drawBitmap(scaled, left, top, paint)
            if (scaled != src) src.recycle()
            scaled.recycle()
        }

        return if (anyDecoded) outBitmap else {
            outBitmap.recycle()
            null
        }
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