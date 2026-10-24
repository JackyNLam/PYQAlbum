package com.pyqcr.ui.util

import android.content.ContentUris
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

    /**
     * Resolve the current on-device path of a MediaStore row (image OR video)
     * from its content:// URI: RELATIVE_PATH + DISPLAY_NAME on Android 10+
     * (scoped-storage safe, no DATA permission needed), falling back to the
     * DATA column on older APIs. Returns null when the row cannot be read.
     * Trashed rows are still queryable this way, so the recycle bin can show
     * where a binned file lives.
     */
    fun resolveMediaPath(context: Context, uri: String): String? {
        return try {
            val contentUri = Uri.parse(uri)
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME
                )
            } else {
                arrayOf(MediaStore.MediaColumns.DATA)
            }
            val cursor = context.contentResolver.query(contentUri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relIdx = it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val rel = if (relIdx >= 0) it.getString(relIdx) else null
                        val name = if (nameIdx >= 0) it.getString(nameIdx) else null
                        if (rel != null && name != null) return rel + name
                        if (rel != null) return rel
                    }
                    val dataIdx = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataIdx >= 0) {
                        val data = it.getString(dataIdx)
                        if (data != null) return data
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "resolveMediaPath failed for $uri: ${e.message}")
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
    fun resolveMediaStorePath(destDir: File?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (destDir == null) return null
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

                // Always insert into MediaStore.Images regardless of RELATIVE_PATH.
                // MediaStore.Downloads does NOT reliably support IS_PENDING across
                // all devices, so files inserted there may stay invisible even after
                // the IS_PENDING clear. MediaStore.Images has full IS_PENDING support
                // and RELATIVE_PATH controls the physical directory (e.g. "Download/").
                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

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
                // If the update fails (returns 0 rows), the file stays invisible
                // forever — clean up the partial row and report failure instead
                // of silently succeeding with an invisible file.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val publishValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    val updated = context.contentResolver.update(destUri, publishValues, null, null)
                    if (updated == 0) {
                        try { context.contentResolver.delete(destUri, null, null) } catch (_: Exception) {}
                        return@withContext FileOpResult(
                            false, null,
                            "Failed to publish file in $relativePath — the device may block writes here"
                        )
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
     * Build a single system TRASH request for a batch of images (API 30+). The
     * system shows ONE confirmation dialog; on confirm the images move to the
     * recoverable recycle bin (the Android "trash" folder) instead of being
     * deleted permanently. Returns null on older APIs or failure — the caller
     * falls back to [createDeleteRequest] / [deleteMediaDirect] then.
     */
    fun createTrashRequest(context: Context, uris: List<String>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            // Volume-qualify each URI (content://media/<volume>/...). With the
            // generic content://media/external/... form the system dialog appears
            // to succeed but the provider silently leaves IS_TRASHED = 0 for items
            // on secondary volumes (e.g. an SD card) — the photo vanishes from the
            // album yet never shows up in the recycle bin. getMediaUri resolves the
            // URI to the volume the item actually lives on.
            val contentUris = uris.map { raw ->
                val parsed = Uri.parse(raw)
                try {
                    MediaStore.getMediaUri(context, parsed)
                } catch (e: Exception) {
                    parsed
                }
            }
            MediaStore.createTrashRequest(context.contentResolver, contentUris.toMutableList(), true)
                .intentSender
        } catch (e: Exception) {
            Log.w(TAG, "createTrashRequest failed", e)
            null
        }
    }

    /**
     * Restore trashed media by clearing their MediaStore IS_TRASHED flag. Tries
     * the row's own URI first (the bin supplies volume-qualified URIs for
     * secondary-volume rows), then the trash-volume address. This direct write
     * succeeds for rows this app owns or when All-files access is granted; where
     * the provider refuses to un-trash a row directly (secondary-volume rows it
     * does not own, e.g. SD cards / HONOR Parallel Space) it no-ops and the URI
     * is returned so the caller can hand it to the system recovery dialog
     * ([createBinRestoreRequest]) — exactly like permanent delete falls back to
     * [createBinPermanentDeleteRequest]. Returns the URIs that could NOT be
     * restored directly.
     *
     * Note: trashing does NOT delete the file — MediaProvider renames it to a
     * hidden ".trashed-<timestamp>-<name>" form and sets IS_TRASHED = 1. Reads
     * on a trashed row are intentionally blocked, so opening a file descriptor
     * here would wrongly report the (still present) file as gone; we therefore
     * never probe for existence — clearing IS_TRASHED is what asks the provider
     * to move the file back to its real name.
     */
    suspend fun restoreFromTrash(context: Context, uris: List<String>): List<String> = withContext(Dispatchers.IO) {
        val failed = mutableListOf<String>()
        for (u in uris) {
            var restored = false
            try {
                val parsed = Uri.parse(u)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, 0)
                }
                var n = context.contentResolver.update(parsed, values, null, null)
                if (n <= 0) {
                    for (trashUri in trashVolumeCandidates(parsed)) {
                        n = try {
                            context.contentResolver.update(trashUri, values, null, null)
                        } catch (e: Exception) {
                            0
                        }
                        if (n > 0) break
                    }
                }
                restored = n > 0
            } catch (e: Exception) {
                Log.w(TAG, "restoreFromTrash failed for $u", e)
            }
            if (!restored) failed += u
        }
        failed
    }

    /**
     * Build the system "restore" request for trashed rows (API 30+). A direct
     * IS_TRASHED = 0 update no-ops for secondary-volume rows the app does not
     * own (SD cards, HONOR Parallel Space) — the provider only un-trashes them
     * through the sanctioned recovery request, which is exactly why permanent
     * DELETE started working only once it went through
     * [createBinPermanentDeleteRequest]. The public restore dialog is built with
     * MediaStore.createTrashRequest(..., trash = false), which resolves rows
     * through their normal collection URIs (the form the bin query returns) and
     * moves the hidden ".trashed-…" file back to its real name on confirm.
     * (MediaStore.createRestoreRequest itself is a hidden @SystemApi member that
     * is not present in the compile SDK's android.jar, so it cannot be referenced
     * directly — it fails CI with "Unresolved reference".)
     * The rows' own URIs are tried first; the trash-view addresses are a fallback
     * for ROMs that only accept that form. Returns null on API < 30 or when no
     * request could be built (callers then report the failure directly).
     */
    fun createBinRestoreRequest(context: Context, uris: List<String>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val ownUris = uris.mapNotNull { raw ->
            try {
                Uri.parse(raw)
            } catch (e: Exception) {
                null
            }
        }.distinct()
        if (ownUris.isNotEmpty()) {
            val sender = try {
                // trash = false builds the sanctioned un-trash (restore) dialog;
                // like createTrashRequest(true) / createDeleteRequest it returns
                // an IntentSenderRequest, so take .intentSender.
                MediaStore.createTrashRequest(context.contentResolver, ownUris, false).intentSender
            } catch (e: Exception) {
                Log.w(TAG, "restore request rejected the rows' own URIs, retrying with trash addresses", e)
                null
            }
            if (sender != null) return sender
        }
        val trashUris = uris.flatMap { raw ->
            try {
                trashVolumeCandidates(Uri.parse(raw))
            } catch (e: Exception) {
                emptyList()
            }
        }.distinct()
        if (trashUris.isEmpty()) return null
        return try {
            MediaStore.createTrashRequest(context.contentResolver, trashUris, false).intentSender
        } catch (e: Exception) {
            Log.w(TAG, "createBinRestoreRequest failed", e)
            null
        }
    }

    /**
     * Permanently delete trashed media. A plain delete against the generic
     * external-volume URI of a trashed row silently deletes NOTHING on many
     * devices — the provider only honours the delete when the row is addressed
     * through the trash volume (content://media/trash/...) AND the caller owns
     * the row (or holds All-files access). Each URI is tried in turn:
     *   1. trash-volume addresses for the guessed collection (global AND the
     *      row's own volume-qualified form — secondary-volume rows such as SD
     *      cards do NOT resolve through the global trash view on some ROMs)
     *   2. the other collection's trash-volume addresses (id-reuse guard)
     *   3. the row's own URI (volume-qualified when the bin query supplied one)
     *   4. recover-then-delete: clear IS_TRASHED, then delete the row
     * Every failed attempt is logged with its reason so the caller can debug
     * rows that still cannot be removed from logcat.
     * Returns the URIs that could NOT be deleted directly — the caller should
     * show the system delete-request dialog ([createBinPermanentDeleteRequest])
     * for those, which permanently deletes rows this app does not own too.
     */
    suspend fun deletePermanentlyFromTrash(context: Context, uris: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val failed = mutableListOf<String>()
            for (u in uris) {
                var deleted = false
                try {
                    val parsed = Uri.parse(u)
                    for (tryUri in deleteCandidates(parsed)) {
                        val n = try {
                            context.contentResolver.delete(tryUri, null, null)
                        } catch (e: Exception) {
                            // e.g. RecoverableSecurityException on a row another
                            // app owns — the system dialog covers these later.
                            Log.w(TAG, "trash delete of $tryUri threw", e)
                            -1
                        }
                        if (n > 0) {
                            deleted = true
                            break
                        }
                        if (n < 0) {
                            Log.w(TAG, "trash delete of $tryUri threw (row $u)")
                        } else {
                            Log.w(TAG, "trash delete of $tryUri returned $n rows (row $u)")
                        }
                    }
                    if (!deleted) {
                        // Last resort: recover the row (IS_TRASHED = 0) then
                        // delete it as a normal item — works around ROMs that
                        // reject trash-volume deletes entirely. If the untrash
                        // succeeds but the delete is refused OR throws, put the
                        // row BACK in the trash — a silently restored row would
                        // leave a bin entry that every later delete (including
                        // the system dialog) no-ops against, looking exactly
                        // like a "corrupted" trash database. Each step is
                        // guarded separately so the re-trash is never skipped:
                        // the row ends either trashed or truly deleted.
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.IS_TRASHED, 0)
                            }
                            val untrashed = try {
                                context.contentResolver.update(parsed, values, null, null)
                            } catch (e: Exception) {
                                Log.w(TAG, "untrash update of $parsed threw", e)
                                -1
                            }
                            if (untrashed > 0) {
                                val n = try {
                                    context.contentResolver.delete(parsed, null, null)
                                } catch (e: Exception) {
                                    Log.w(TAG, "untrash ok but delete of $parsed threw", e)
                                    -1
                                }
                                if (n > 0) {
                                    deleted = true
                                } else {
                                    // The row is currently RESTORED (IS_TRASHED=0)
                                    // — put it straight back in the trash before
                                    // anything else can see it half-restored.
                                    Log.w(TAG, "untrash ok but delete of $parsed returned $n — re-trashing")
                                    val reTrash = ContentValues().apply {
                                        put(MediaStore.MediaColumns.IS_TRASHED, 1)
                                    }
                                    var retrashed = -1
                                    try {
                                        retrashed = context.contentResolver.update(parsed, reTrash, null, null)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "re-trash update of $parsed threw", e)
                                    }
                                    if (retrashed <= 0) {
                                        for (trashUri in trashVolumeCandidates(parsed)) {
                                            try {
                                                retrashed = context.contentResolver.update(trashUri, reTrash, null, null)
                                            } catch (e: Exception) {
                                                Log.w(TAG, "re-trash update of $trashUri threw", e)
                                            }
                                            if (retrashed > 0) break
                                        }
                                    }
                                    if (retrashed <= 0) {
                                        Log.w(TAG, "row $parsed could not be re-trashed after a refused delete — check the gallery for a restored item")
                                    }
                                }
                            } else {
                                Log.w(TAG, "untrash update of $parsed returned $untrashed rows")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "recover-then-delete failed for $parsed", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "deletePermanentlyFromTrash failed for $u", e)
                }
                if (!deleted) failed += u
            }
            failed
        }

    /**
     * Candidate URIs for permanently deleting one trashed row, best first.
     * Trash-volume id namespaces are per-collection, so trying both collections
     * is safe (an id that exists in neither table deletes 0 rows). Both the
     * global trash view and the row's own volume-qualified trash address are
     * included — rows on secondary volumes (SD cards) do not resolve through
     * the global content://media/trash/... view on some ROMs.
     */
    private fun deleteCandidates(parsed: Uri): List<Uri> {
        val candidates = mutableListOf<Uri>()
        val trash = trashVolumeCandidates(parsed)
        candidates.addAll(trash)
        for (t in trash) {
            val other = if (t.toString().contains("/images/")) {
                t.toString().replace("/images/", "/video/")
            } else {
                t.toString().replace("/video/", "/images/")
            }
            candidates.add(Uri.parse(other))
        }
        // The row's own address — volume-qualified when the bin query supplied
        // a volume name, generic otherwise.
        candidates.add(parsed)
        return candidates.distinct()
    }

    /**
     * Build the system "delete permanently" request for trashed rows — the
     * platform-sanctioned way to remove recycle-bin items this app does not
     * own without All-files access. Passes the rows' OWN content URIs
     * (content://media/<volume>/<collection>/media/<id> — the form the bin
     * query returns, volume-qualified so SD-card rows resolve) to
     * MediaStore.createDeleteRequest; the system shows ONE confirmation dialog
     * and the provider PERMANENTLY deletes those rows on confirm. This is the
     * form createDeleteRequest is designed to resolve — feeding it the synthetic
     * content://media/.../trash/... addresses instead shows a consent dialog
     * that grants but deletes NOTHING, which looked exactly like the "it keeps
     * asking permission and even after I allow it the item won't delete" bug.
     * Only if a ROM outright rejects the normal form at build time (throws, so
     * no request is produced) do we retry with the trash-view addresses.
     * Returns null on API < 30 or when no request could be built (callers then
     * report the failure directly).
     */
    fun createBinPermanentDeleteRequest(context: Context, uris: List<String>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // Primary: the rows' own (volume-qualified) collection URIs.
        val ownUris = uris.mapNotNull { raw ->
            try {
                Uri.parse(raw)
            } catch (e: Exception) {
                null
            }
        }.distinct()
        if (ownUris.isNotEmpty()) {
            val sender = try {
                MediaStore.createDeleteRequest(context.contentResolver, ownUris).intentSender
            } catch (e: Exception) {
                Log.w(TAG, "createDeleteRequest rejected the rows' own URIs, retrying with trash addresses", e)
                null
            }
            if (sender != null) return sender
        }
        // Fallback: some ROMs only accept the trash-volume URI form inside
        // createDeleteRequest; those addresses resolve to the same trashed rows.
        val trashUris = uris.flatMap { raw ->
            try {
                trashVolumeCandidates(Uri.parse(raw))
            } catch (e: Exception) {
                emptyList()
            }
        }.distinct()
        if (trashUris.isEmpty()) return null
        return try {
            MediaStore.createDeleteRequest(context.contentResolver, trashUris).intentSender
        } catch (e: Exception) {
            Log.w(TAG, "createBinPermanentDeleteRequest failed", e)
            null
        }
    }

    /**
     * Trash-volume addresses for one trashed row (API 30+): the permanent-delete
     * targets the MediaProvider actually honours. Returns the row's own
     * volume-qualified trash address (content://media/<volume>/trash/...) FIRST
     * — it addresses this row precisely — then the GLOBAL trash view
     * (content://media/trash/...), which some ROMs require but which can alias
     * another volume's row that happens to share the same numeric id. Rows on
     * secondary volumes (SD cards) do NOT resolve through the global view on
     * several ROMs. Empty on older APIs or when the row id cannot be parsed.
     * (MediaStore.VOLUME_TRASH is not exposed by this build's android.jar —
     * the literal "trash" is the same.)
     */
    fun trashVolumeCandidates(uri: Uri): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val id = ContentUris.parseId(uri)
            val collection = if (uri.toString().contains("/video/")) "video" else "images"
            val volumeQualified = try {
                // pathSegments: ["media", "<volume>", "<collection>", "media", "<id>"]
                val seg = uri.pathSegments
                if (seg.size >= 5 && seg[0] == "media" && seg[1] != "trash") {
                    Uri.parse("content://media/${seg[1]}/trash/$collection/media/$id")
                } else null
            } catch (e: Exception) {
                null
            }
            val global = Uri.parse("content://media/trash/$collection/media/$id")
            listOfNotNull(volumeQualified, global).distinct()
        } catch (e: Exception) {
            emptyList()
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

    /**
     * Save a bitmap as a PNG into the shared Pictures/PYQAlbum/Screenshots
     * folder (MediaStore insert, zero-permission on API 29+ via RELATIVE_PATH;
     * IS_PENDING hides the file until the write completes). Returns the new
     * content URI as a string, or null when the save failed.
     */
    fun savePngScreenshot(context: Context, bitmap: android.graphics.Bitmap, baseName: String): String? {
        var outUri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${baseName}_${System.currentTimeMillis()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/PYQAlbum/Screenshots"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            outUri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
            if (outUri == null) {
                Log.w(TAG, "savePngScreenshot: MediaStore insert returned null")
                return null
            }
            val output = context.contentResolver.openOutputStream(outUri)
            if (output == null) {
                try { context.contentResolver.delete(outUri, null, null) } catch (e: Exception) {}
                Log.w(TAG, "savePngScreenshot: no output stream for $outUri")
                return null
            }
            output.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                try {
                    context.contentResolver.update(outUri, publish, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "savePngScreenshot: publish failed", e)
                }
            }
            outUri.toString()
        } catch (e: Exception) {
            Log.w(TAG, "savePngScreenshot failed", e)
            try {
                outUri?.let { context.contentResolver.delete(it, null, null) }
            } catch (e2: Exception) {
            }
            null
        }
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

    /**
     * Resolve the URI that should represent [destination] in the library after a
     * copy/move, so callers can re-point tag cross-refs to the moved file:
     *   1. MediaStore content URIs pass through unchanged.
     *   2. Real file paths (true file moves) are looked up by their DATA column.
     *   3. SAF document URIs are resolved back through the tree, then by DATA.
     * Returns null when the new image cannot be located in MediaStore yet.
     */
    fun resolveNewImageUri(context: Context, destination: String, treeUri: Uri?): String? {
        if (destination.startsWith("content://media/")) return destination
        if (destination.startsWith("/")) return resolveContentUriByPath(context, destination)
        if (destination.startsWith("content://") && treeUri != null) {
            return try {
                // DocumentsContract.getDocumentPath is a hidden API, so resolve the
                // document's path under the tree manually from the doc IDs, e.g.
                // docId "primary:DCIM/A/1.jpg" under tree "primary:DCIM/A" -> "/1.jpg".
                val docId = DocumentsContract.getDocumentId(Uri.parse(destination))
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val docParts = docId.split(":", limit = 2)
                val treeParts = treeDocId.split(":", limit = 2)
                if (docParts.size != 2 || treeParts.size != 2 || docParts[0] != treeParts[0]) {
                    return null
                }
                if (!docParts[1].startsWith(treeParts[1])) return null
                val treePath = treeParts[1].trim('/')
                val relPath = docParts[1].removePrefix(treeParts[1]).trimStart('/')
                val volumeRoot = if (docParts[0].equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else {
                    resolveVolumeRoot(context, docParts[0]) ?: return null
                }
                val fullPath = buildString {
                    append(volumeRoot)
                    if (treePath.isNotEmpty()) append(File.separator).append(treePath)
                    if (relPath.isNotEmpty()) append(File.separator).append(relPath)
                }
                resolveContentUriByPath(context, fullPath)
            } catch (e: Exception) {
                Log.w(TAG, "resolveNewImageUri failed for $destination: ${e.message}")
                null
            }
        }
        return null
    }

    /** Absolute root path of a secondary storage volume (e.g. an SD card) by its UUID. */
    private fun resolveVolumeRoot(context: Context, volumeId: String): String? {
        return try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            sm.storageVolumes
                .firstOrNull {
                    // StorageVolume.isMounted is hidden; getState() is the public equivalent.
                    (it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY)
                        && it.uuid?.equals(volumeId, ignoreCase = true) == true
                }
                ?.directory?.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "resolveVolumeRoot failed for $volumeId: ${e.message}")
            null
        }
    }

    /** Look up the MediaStore content URI of an image by its real file path. */
    private fun resolveContentUriByPath(context: Context, filePath: String): String? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DATA}=?"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(filePath), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()).toString()
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveContentUriByPath failed for $filePath: ${e.message}")
            null
        }
    }

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