package com.pyqcr.ui.util

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Helper for batch copy/move operations on image files.
 *
 * Uses up to two strategies for the destination folder:
 *   1. When the picked folder resolves to a real file path on internal storage
 *      ("primary:" tree URIs), use File I/O directly — the fast path, and it
 *      enables a true same-volume move via [File.renameTo].
 *   2. Otherwise (SD card, USB, cloud providers, or Android 11+ scoped-storage
 *      blocked paths) fall back to the SAF DocumentsContract API using the
 *      granted tree URI, which can write to any folder the user can pick.
 *
 * Every file failure is reported as a [FileOpResult] with a human-readable
 * reason so the caller can tell the user WHY a file failed (permission denied,
 * read-only, source delete failed…) instead of a bare count.
 */
object FileOperationHelper {

    internal const val TAG = "FileOpHelper"

    /**
     * Outcome of a single copy/move: destination (path or content uri) or the failure reason.
     * @param sourceNeedsDeletion True when a move copied the file successfully but could not
     *   delete the original — the caller must show the system delete-request dialog (API 30+)
     *   or delete directly (older APIs). Always false for plain copy.
     */
    data class FileOpResult(
        val success: Boolean,
        val destination: String?,
        val error: String?,
        val sourceNeedsDeletion: Boolean = false
    )

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

    // ---------- MediaStore RELATIVE_PATH (zero-permission standard collections) ----------

    /**
     * Check if [destDir] is under a standard MediaStore collection (Download,
     * Pictures, DCIM, Movies). If so, return the `RELATIVE_PATH` value that
     * MediaStore expects (e.g. `"Download/BabyName/"`). Returns null for
     * non-standard folders (arbitrary directories like Manga/Baby/BabyName).
     *
     * Only available on API 29+ — `RELATIVE_PATH` was introduced in Q.
     */
    fun resolveMediaStorePath(destDir: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val extDir = Environment.getExternalStorageDirectory().absolutePath
        val absPath = destDir.absolutePath
        if (!absPath.startsWith(extDir)) return null
        // rel = "Download/BabyName" or "Manga/Baby/BabyName"
        val rel = absPath.removePrefix(extDir).removePrefix(File.separator)
        if (rel.isBlank()) return null
        val topLevel = rel.substringBefore(File.separator).lowercase()
        val collectionRoot = when (topLevel) {
            "download" -> "Download"
            "pictures" -> "Pictures"
            "dcim" -> "DCIM"
            "movies" -> "Movies"
            else -> return null  // Non-standard folder — use SAF or file path
        }
        return if (rel.equals(collectionRoot, ignoreCase = true)) "$collectionRoot/" else "$rel/"
    }

    /** True if [destDir] is under a standard MediaStore collection (zero-permission write on API 29+). */
    fun isStandardMediaCollection(destDir: File?): Boolean {
        if (destDir == null) return false
        return resolveMediaStorePath(destDir) != null
    }

    /**
     * Extract a MediaStore `RELATIVE_PATH` from a SAF tree URI's document ID.
     * Handles URIs where [resolveTreeUriToPath] fails (e.g. user picked
     * "Downloads" from the SAF sidebar, which uses a different provider).
     * Returns null for non-standard collections or secondary volumes.
     */
    fun resolveMediaStorePathFromTreeUri(treeUri: Uri?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (treeUri == null) return null
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            if (!docId.startsWith("primary:")) return null
            val relativePath = docId.removePrefix("primary:")
            if (relativePath.isBlank()) return null
            val topLevel = relativePath.substringBefore('/').lowercase()
            val collectionRoot = when (topLevel) {
                "download" -> "Download"
                "pictures" -> "Pictures"
                "dcim" -> "DCIM"
                "movies" -> "Movies"
                else -> return null
            }
            if (relativePath.equals(collectionRoot, ignoreCase = true)) "$collectionRoot/" else "$relativePath/"
        } catch (e: Exception) {
            null
        }
    }

    /** True if the SAF tree URI points to a standard MediaStore collection. */
    fun isStandardMediaCollectionFromTreeUri(treeUri: Uri?): Boolean {
        return resolveMediaStorePathFromTreeUri(treeUri) != null
    }

    // ---------- Single-file operations ----------

    /**
     * Copy a single image into the chosen folder.
     * Tries MediaStore insert (zero-permission for Download/Pictures/DCIM/Movies),
     * then File I/O (legacy/All-files-access), then SAF (any pickable folder).
     */
    suspend fun copyImage(context: Context, sourceUri: String, destDir: File?, treeUri: Uri?): FileOpResult {
        // Strategy 1: MediaStore RELATIVE_PATH (zero-permission for standard collections).
        // Try destDir first, then tree URI — covers SAF sidebar picks where destDir is null.
        val relPath = resolveMediaStorePath(destDir) ?: resolveMediaStorePathFromTreeUri(treeUri)
        if (relPath != null) {
            val r = copyViaMediaStore(context, sourceUri, relPath)
            if (r.success) return r
            Log.w(TAG, "MediaStore copy failed for $sourceUri (${r.error}) — trying file path/SAF")
        }
        // Strategy 2: File-path fast path (legacy/All-files-access)
        if (destDir != null) {
            val r = copyViaFilePath(context, sourceUri, destDir)
            if (r.success) return r
            Log.w(TAG, "File-path copy failed for $sourceUri (${r.error}) — trying SAF")
        }
        // Strategy 3: SAF fallback (any pickable folder)
        if (treeUri != null) {
            return copyViaSaf(context, sourceUri, treeUri)
        }
        return FileOpResult(false, null, "No writable destination folder")
    }

    /**
     * Move a single image into the chosen folder.
     * Tries MediaStore insert (zero-permission), then true file rename (same-volume),
     * then SAF copy. When the copy succeeds but the original can't be deleted
     * (SAF/MediaStore path on API 30+), the result carries `sourceNeedsDeletion = true`
     * so the caller can show the system delete-request dialog.
     */
    suspend fun moveImage(context: Context, sourceUri: String, destDir: File?, treeUri: Uri?): FileOpResult {
        // Strategy 1: MediaStore RELATIVE_PATH (zero-permission for standard collections)
        val relPath = resolveMediaStorePath(destDir) ?: resolveMediaStorePathFromTreeUri(treeUri)
        if (relPath != null) {
            val copy = copyViaMediaStore(context, sourceUri, relPath)
            if (copy.success) {
                return FileOpResult(true, copy.destination, null, sourceNeedsDeletion = true)
            }
            Log.w(TAG, "MediaStore move-copy failed for $sourceUri (${copy.error}) — trying file path/SAF")
        }
        // Strategy 2: True file move (rename) or copy — moveViaFilePath handles rename deletion
        if (destDir != null) {
            val r = moveViaFilePath(context, sourceUri, destDir)
            if (r.success) return r
            Log.w(TAG, "File-path move failed for $sourceUri (${r.error}) — trying SAF")
        }
        // Strategy 3: SAF copy — source deletion deferred to caller (system delete-request dialog)
        if (treeUri != null) {
            val copy = copyViaSaf(context, sourceUri, treeUri)
            if (!copy.success) return copy
            return FileOpResult(true, copy.destination, null, sourceNeedsDeletion = true)
        }
        return FileOpResult(false, null, "No writable destination folder")
    }

    // ---------- Copy/Move via real file path (fast path) ----------

    /**
     * Copy a file using ContentResolver stream reads + File I/O writes.
     * Only works on paths the app may access directly (internal storage media
     * folders, or legacy full access on Android 10).
     */
    private suspend fun copyViaFilePath(
        context: Context,
        sourceUri: String,
        destDir: File,
        preserveLastModified: Boolean = false
    ): FileOpResult = withContext(Dispatchers.IO) {
        try {
            val contentUri = Uri.parse(sourceUri)
            val fileName = getFileName(context, sourceUri)
            if (!destDir.exists() && !destDir.mkdirs()) {
                return@withContext FileOpResult(
                    false, null,
                    "Cannot create folder \"${destDir.name}\" — write access blocked (Android 11+ scoped storage)"
                )
            }
            val destFile = resolveConflict(File(destDir, fileName))

            // Remember the source's modified time so we can restore it on the copy
            val sourceLastModified = if (preserveLastModified)
                getLastModified(context, contentUri) else null

            val input = context.contentResolver.openInputStream(contentUri)
            if (input == null) {
                return@withContext FileOpResult(false, null, "Cannot read source image (stream unavailable)")
            }
            input.use { i ->
                FileOutputStream(destFile).use { output -> i.copyTo(output) }
            }

            // Preserve the original modified date when copying (used by move fallback)
            if (sourceLastModified != null && sourceLastModified > 0L) {
                try { destFile.setLastModified(sourceLastModified) } catch (_: Exception) {}
            }

            MediaStoreUtils.scanFile(context, destFile.absolutePath)
            FileOpResult(true, destFile.absolutePath, null)
        } catch (e: Exception) {
            Log.e(TAG, "copyViaFilePath failed for $sourceUri", e)
            FileOpResult(false, null, "${e::class.simpleName}: ${e.message}")
        }
    }

    /** Move a file via real paths — a true move via [File.renameTo] when possible, else copy+delete. */
    private suspend fun moveViaFilePath(
        context: Context,
        sourceUri: String,
        destDir: File
    ): FileOpResult = withContext(Dispatchers.IO) {
        try {
            val contentUri = Uri.parse(sourceUri)
            val fileName = getFileName(context, sourceUri)
            if (!destDir.exists() && !destDir.mkdirs()) {
                return@withContext FileOpResult(
                    false, null,
                    "Cannot create folder \"${destDir.name}\" — write access blocked (Android 11+ scoped storage)"
                )
            }
            val destFile = resolveConflict(File(destDir, fileName))

            // Strategy 1: true file move via renameTo (same volume) — same inode, modified date intact
            val realPath = resolveFilePath(context, sourceUri)
            if (realPath != null) {
                val sourceFile = File(realPath)
                if (sourceFile.exists() && sourceFile.renameTo(destFile)) {
                    MediaStoreUtils.scanFile(context, destFile.absolutePath)
                    // Remove the stale MediaStore row for the old location
                    deleteSourceEntry(context, contentUri)
                    return@withContext FileOpResult(true, destFile.absolutePath, null)
                }
            }

            // Strategy 2: copy fallback (cross-volume or content-only URI).
            // Source deletion is deferred to the caller, which shows the system
            // delete-request dialog on API 30+ or deletes directly on older APIs.
            val copy = copyViaFilePath(context, sourceUri, destDir, preserveLastModified = true)
            if (!copy.success) return@withContext copy
            FileOpResult(success = true, destination = copy.destination, error = null, sourceNeedsDeletion = true)
        } catch (e: Exception) {
            Log.e(TAG, "moveViaFilePath failed for $sourceUri", e)
            FileOpResult(false, null, "${e::class.simpleName}: ${e.message}")
        }
    }

    // ---------- Copy via SAF (works for any pickable folder) ----------

    /** Copy a file through the retained SAF tree URI — works on SD/USB/cloud and scoped-storage blocked paths. */
    private suspend fun copyViaSaf(context: Context, sourceUri: String, treeUri: Uri): FileOpResult =
        withContext(Dispatchers.IO) {
            try {
                val contentUri = Uri.parse(sourceUri)
                val fileName = getFileName(context, sourceUri)
                if (fileName.isBlank()) {
                    return@withContext FileOpResult(false, null, "Missing file name for $sourceUri")
                }

                val availableName = findAvailableSafName(context, treeUri, fileName)
                val docUri = createSafDocument(context, treeUri, mimeTypeFor(fileName), availableName)
                    ?: return@withContext FileOpResult(
                        false, null,
                        "Cannot create files in the chosen folder (read-only or blocked by the ROM) — " +
                        "granting All files access from the copy/move prompt enables any folder"
                    )

                val input = context.contentResolver.openInputStream(contentUri)
                val output = context.contentResolver.openOutputStream(docUri)
                if (input == null || output == null) {
                    // Clean up the empty document we just created
                    try { DocumentsContract.deleteDocument(context.contentResolver, docUri) } catch (_: Exception) {}
                    return@withContext FileOpResult(false, null, "Cannot read the source image or write to the chosen folder")
                }
                input.use { i -> output.use { o -> i.copyTo(o) } }

                FileOpResult(true, docUri.toString(), null)
            } catch (e: Exception) {
                Log.e(TAG, "copyViaSaf failed for $sourceUri", e)
                FileOpResult(false, null, "${e::class.simpleName}: ${e.message}")
            }
        }

    // ---------- Copy via MediaStore insert (zero-permission standard collections) ----------

    /**
     * Copy a file via MediaStore insert with `RELATIVE_PATH`.
     *
     * This is the zero-permission, Play-safe way to write into standard media
     * collections (Download, Pictures, DCIM, Movies) on API 29+. The app owns
     * the files it creates, so no runtime permission is needed — exactly how
     * other gallery apps write to Download without MANAGE_EXTERNAL_STORAGE.
     */
    private suspend fun copyViaMediaStore(context: Context, sourceUri: String, relativePath: String): FileOpResult =
        withContext(Dispatchers.IO) {
            var destUri: Uri? = null
            try {
                val contentUri = Uri.parse(sourceUri)
                val fileName = getFileName(context, sourceUri)
                val mime = mimeTypeFor(fileName)

                val collection = if (relativePath.startsWith("Download", ignoreCase = true)) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    // IS_PENDING signals the system that the file is being written;
                    // other apps can't see it until we clear this to 0 after the write.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                destUri = context.contentResolver.insert(collection, values)
                    ?: return@withContext FileOpResult(
                        false, null,
                        "MediaStore insert failed — cannot create file in $relativePath"
                    )

                val input = context.contentResolver.openInputStream(contentUri)
                val output = context.contentResolver.openOutputStream(destUri)
                if (input == null || output == null) {
                    try { context.contentResolver.delete(destUri, null, null) } catch (_: Exception) {}
                    return@withContext FileOpResult(false, null, "Cannot read source or write to $relativePath")
                }
                input.use { i -> output.use { o -> i.copyTo(o) } }

                // Publish the file — clear IS_PENDING so it becomes visible to
                // the file manager, gallery, and other apps (API 29+).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val publishValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        context.contentResolver.update(destUri, publishValues, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to clear IS_PENDING for $destUri: ${e.message}")
                    }
                }

                FileOpResult(true, destUri.toString(), null)
            } catch (e: Exception) {
                Log.e(TAG, "copyViaMediaStore failed for $sourceUri", e)
                // Clean up the partial file so we don't leave orphaned pending rows
                destUri?.let { uri ->
                    try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
                FileOpResult(false, null, "${e::class.simpleName}: ${e.message}")
            }
        }

    /** Create a new document inside the tree, returning its content uri. */
    private fun createSafDocument(context: Context, treeUri: Uri, mimeType: String, displayName: String): Uri? {
        return try {
            val created = DocumentsContract.createDocument(context.contentResolver, treeUri, mimeType, displayName)
            if (created != null) return created
            // Some providers misbehave when handed a pure tree URI — retry using
            // the tree-root document URI, the classic form.
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            DocumentsContract.createDocument(context.contentResolver, rootDoc, mimeType, displayName)
        } catch (e: Exception) {
            Log.w(TAG, "createDocument failed for $displayName", e)
            null
        }
    }

    /** Find a display name that does not collide with existing children of the tree. */
    private fun findAvailableSafName(context: Context, treeUri: Uri, fileName: String): String {
        val existing = querySafChildNames(context, treeUri)
        var candidate = fileName
        var counter = 1
        while (candidate in existing) {
            val dot = fileName.lastIndexOf('.')
            val base = if (dot >= 0) fileName.substring(0, dot) else fileName
            val ext = if (dot >= 0) fileName.substring(dot) else ""
            candidate = "${base}_$counter$ext"
            counter++
        }
        return candidate
    }

    /** List display names of the tree's immediate children. */
    private fun querySafChildNames(context: Context, treeUri: Uri): Set<String> {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val names = mutableSetOf<String>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { names += it }
                }
            }
            names
        } catch (e: Exception) {
            Log.w(TAG, "querySafChildNames failed", e)
            emptySet()
        }
    }

    /** Guess a MIME type from the file extension so the created document keeps it. */
    private fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    // ---------- Source deletion after copy ----------

    /** Get the modified time (milliseconds) of a media item, if MediaStore exposes it. */
    private fun getLastModified(context: Context, contentUri: Uri): Long? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media.DATE_MODIFIED)
            context.contentResolver.query(contentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    if (idx >= 0) cursor.getLong(idx) * 1000L else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "getLastModified failed for $contentUri: ${e.message}")
            null
        }
    }

    /** Remove the stale MediaStore row for a source URI after a true file move. */
    private fun deleteSourceEntry(context: Context, contentUri: Uri) {
        try {
            context.contentResolver.delete(contentUri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "deleteSourceEntry failed for $contentUri: ${e.message}")
        }
    }

    // Source deletion after move is handled by the caller: the caller collects
    // URIs with sourceNeedsDeletion=true and shows the system delete-request
    // dialog (createDeleteRequest via ActivityResultLauncher) on API 30+, or
    // calls deleteMediaDirect on older APIs. See batchMoveViaFiles + launchCopyMove.

    // ---------- Batch operations ----------

    /**
     * Batch copy. Each result is (sourceUri, FileOpResult).
     * @param destDir Real destination directory, or null when not resolvable.
     * @param treeUri Retained SAF tree URI of the picked folder (covers non-resolvable folders).
     * @param onFileResult Called per file with its result, so the caller can collect failures.
     */
    suspend fun batchCopyViaFiles(
        context: Context,
        sourceUris: List<String>,
        destDir: File?,
        treeUri: Uri? = null,
        onFileResult: (String, FileOpResult) -> Unit = { _, _ -> }
    ): List<Pair<String, FileOpResult>> = withContext(Dispatchers.IO) {
        sourceUris.map { uri ->
            val r = copyImage(context, uri, destDir, treeUri)
            onFileResult(uri, r)
            uri to r
        }
    }

    /** Batch move. Each result is (sourceUri, FileOpResult). See [batchCopyViaFiles]. */
    suspend fun batchMoveViaFiles(
        context: Context,
        sourceUris: List<String>,
        destDir: File?,
        treeUri: Uri? = null,
        onFileResult: (String, FileOpResult) -> Unit = { _, _ -> }
    ): List<Pair<String, FileOpResult>> = withContext(Dispatchers.IO) {
        sourceUris.map { uri ->
            val r = moveImage(context, uri, destDir, treeUri)
            onFileResult(uri, r)
            uri to r
        }
    }

    // ---------- Batch delete ----------

    /**
     * Build a single system delete request for a batch of images.
     *
     * On API 30+ this returns an [IntentSender] that the caller must launch via
     * an ActivityResultLauncher; the system shows ONE confirmation dialog and moves
     * all the selected images to the recoverable trash. Returns null on older APIs
     * (or on failure), in which case the caller should fall back to [deleteMediaDirect].
     */
    fun createDeleteRequest(context: Context, uris: List<String>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val contentUris = uris.map { Uri.parse(it) }
            MediaStore.createDeleteRequest(context.contentResolver, contentUris).intentSender
        } catch (e: Exception) {
            Log.w(TAG, "createDeleteRequest failed", e)
            null
        }
    }

    /**
     * Directly delete images on API < 30 (no system confirmation dialog available).
     * Returns the number successfully deleted.
     */
    suspend fun deleteMediaDirect(context: Context, uris: List<String>): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        for (u in uris) {
            try {
                if (context.contentResolver.delete(Uri.parse(u), null, null) > 0) deleted++
            } catch (e: Exception) {
                Log.w(TAG, "deleteMediaDirect failed for $u", e)
            }
        }
        deleted
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