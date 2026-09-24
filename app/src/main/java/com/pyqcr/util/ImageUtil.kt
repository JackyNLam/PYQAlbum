package com.pyqcr.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import java.io.FileNotFoundException
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
}