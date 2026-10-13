package com.pyqcr.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Resolves a content:// (or file://) URI to a local file path that
 * BitmapFactory / OkHttp can read directly.
 *
 * content:// URIs are copied to a cache file (same input the AI Edit
 * screen used locally — shared here so the background AI Edit worker
 * can reuse the exact same resolution logic).
 */
object ContentUriResolver {

    fun resolve(context: Context, uriStr: String): String? {
        return try {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "file") {
                return uri.path
            }

            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(context.cacheDir, "ai_edit_src_${uri.hashCode()}.jpg")
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            tempFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}