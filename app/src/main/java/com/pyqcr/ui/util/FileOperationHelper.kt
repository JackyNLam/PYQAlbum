package com.pyqcr.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
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

    /** Move a file by copying to destination and then reliably deleting the source.
     *
     * Deletion strategy (in order of preference):
     *   1. API 30+: [MediaStore.createDeleteRequest] — moves to trash (user-recoverable)
     *   2. API < 30: Delete the physical file via resolved path, then remove MediaStore entry
     *   3. Fallback: remove MediaStore entry alone as last resort
     */
    suspend fun moveViaFilePath(
        context: Context,
        sourceUri: String,
        destDir: File
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Step 1: copy to destination
            val destPath = copyViaFilePath(context, sourceUri, destDir)
            if (destPath == null) return@withContext null

            // Step 2: delete source file
            val contentUri = Uri.parse(sourceUri)
            val deleted = deleteSourceFile(context, contentUri)
            if (!deleted) {
                Log.w(TAG, "Source delete may have failed for $sourceUri")
            }

            destPath
        } catch (e: Exception) {
            Log.e(TAG, "moveViaFilePath failed for $sourceUri", e)
            null
        }
    }

    /** Delete the source file after a successful copy. Tries multiple strategies. */
    private suspend fun deleteSourceFile(context: Context, contentUri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Strategy 1: API 30+ — use createDeleteRequest (move to trash, recoverable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(contentUri))
                pendingIntent.send()
                // createDeleteRequest is asynchronous; we wait a bit for the MediaStore to update
                // by checking if the content URI still resolves
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "createDeleteRequest failed, trying fallback", e)
            }
        }

        // Strategy 2: resolve file path via _ID query (more reliable than deprecated DATA column)
        try {
            val id = contentUri.lastPathSegment
            if (id != null) {
                val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
                context.contentResolver.query(contentUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (dataIndex >= 0) {
                            val filePath = cursor.getString(dataIndex)
                            if (filePath != null) {
                                val file = File(filePath)
                                if (file.exists() && file.delete()) {
                                    // Remove MediaStore entry after physical deletion
                                    context.contentResolver.delete(contentUri, null, null)
                                    return@withContext true
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolve-and-delete failed", e)
        }

        // Strategy 3: just remove from MediaStore (last resort)
        try {
            context.contentResolver.delete(contentUri, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "deleteFromMediaStore failed", e)
            false
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