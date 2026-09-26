package com.pyqcr.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Helper for batch copy/move operations on image files.
 *
 * Uses two strategies:
 *   1. If a real file path is available from the DATA column, use File I/O directly.
 *   2. Fallback to SAF DocumentProvider API for content:// URIs.
 */
object FileOperationHelper {

    internal const val TAG = "FileOpHelper"

    // ---------- File path resolution ----------

    /** Resolve the real file path from a content:// URI. Returns null if unavailable. */
    fun resolveFilePath(context: Context, uri: String): String? {
        return try {
            val contentUri = Uri.parse(uri)
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            val cursor = context.contentResolver.query(contentUri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val dataIndex = it.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (dataIndex >= 0) it.getString(dataIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveFilePath failed for $uri: ${e.message}")
            null
        }
    }

    /** Get the display name from a content:// URI. */
    fun getFileName(context: Context, uri: String): String {
        val contentUri = Uri.parse(uri)
        val cursor = context.contentResolver.query(contentUri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val name = it.getString(nameIndex)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return contentUri.lastPathSegment ?: "unknown_${System.currentTimeMillis()}"
    }

    // ---------- Copy/Move via real file path (fast path) ----------

    /** Copy a file using ContentResolver stream (works on all Android versions, incl. 10+ where DATA column is null). */
    suspend fun copyViaFilePath(
        context: Context,
        sourceUri: String,
        destDir: File
    ): String? = withContext(Dispatchers.IO) {
        try {
            val contentUri = Uri.parse(sourceUri)
            val fileName = getFileName(context, sourceUri)
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = resolveConflict(File(destDir, fileName))

            // Use ContentResolver to read the source stream — works on all Android versions
            context.contentResolver.openInputStream(contentUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            MediaStoreUtils.scanFile(context, destFile.absolutePath)
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "copyViaFilePath failed for $sourceUri", e)
            null
        }
    }

    /** Move a file using real file paths. Tries rename, falls back to copy+delete. */
    suspend fun moveViaFilePath(
        context: Context,
        sourceUri: String,
        destDir: File
    ): String? = withContext(Dispatchers.IO) {
        try {
            val contentUri = Uri.parse(sourceUri)
            val fileName = getFileName(context, sourceUri)

            // First try to get real file path for fast rename
            val sourcePath = resolveFilePath(context, sourceUri)
            if (sourcePath != null) {
                val sourceFile = File(sourcePath)
                if (sourceFile.exists()) {
                    if (!destDir.exists()) destDir.mkdirs()
                    val destFile = resolveConflict(File(destDir, sourceFile.name))
                    // Try rename (fast, same filesystem)
                    if (sourceFile.renameTo(destFile)) {
                        MediaStoreUtils.scanFile(context, destFile.absolutePath)
                        MediaStoreUtils.deleteFromMediaStore(context, sourceUri)
                        return@withContext destFile.absolutePath
                    }
                    // Fallback: copy + delete
                    sourceFile.copyTo(destFile, overwrite = false)
                    sourceFile.delete()
                    MediaStoreUtils.scanFile(context, destFile.absolutePath)
                    MediaStoreUtils.deleteFromMediaStore(context, sourceUri)
                    return@withContext destFile.absolutePath
                }
            }

            // Fallback: copy via ContentResolver then delete
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = resolveConflict(File(destDir, fileName))
            context.contentResolver.openInputStream(contentUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null
            MediaStoreUtils.scanFile(context, destFile.absolutePath)
            MediaStoreUtils.deleteFromMediaStore(context, sourceUri)
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "moveViaFilePath failed for $sourceUri", e)
            null
        }
    }

    // ---------- Batch operations ----------

    /** Batch copy using file paths. Returns list of (uri, destPathOrNull). */
    suspend fun batchCopyViaFiles(
        context: Context,
        sourceUris: List<String>,
        destDir: File
    ): List<Pair<String, String?>> = withContext(Dispatchers.IO) {
        sourceUris.map { uri -> uri to copyViaFilePath(context, uri, destDir) }
    }

    /** Batch move using file paths. Returns list of (uri, destPathOrNull). */
    suspend fun batchMoveViaFiles(
        context: Context,
        sourceUris: List<String>,
        destDir: File
    ): List<Pair<String, String?>> = withContext(Dispatchers.IO) {
        sourceUris.map { uri -> uri to moveViaFilePath(context, uri, destDir) }
    }

    /** Choose a default fallback destination for copy/move operations. */
    fun getDefaultMoveDir(context: Context): File {
        val picturesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val dir = File(picturesDir, "PYQAlbum")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ---------- Helpers ----------

    private fun resolveConflict(file: File): File {
        var f = file
        var counter = 1
        while (f.exists()) {
            val dot = file.name.lastIndexOf('.')
            val base = if (dot >= 0) file.name.substring(0, dot) else file.name
            val ext = if (dot >= 0) file.name.substring(dot) else ""
            f = File(file.parentFile, "${base}_${counter}$ext")
            counter++
        }
        return f
    }
}

// ---------- MediaStore utilities ----------

internal object MediaStoreUtils {
    fun scanFile(context: Context, filePath: String) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(filePath), null, null
            )
        } catch (e: Exception) {
            Log.w(FileOperationHelper.TAG, "scanFile failed", e)
        }
    }

    fun deleteFromMediaStore(context: Context, uri: String) {
        try {
            context.contentResolver.delete(Uri.parse(uri), null, null)
        } catch (e: Exception) {
            Log.w(FileOperationHelper.TAG, "deleteFromMediaStore failed", e)
        }
    }
}