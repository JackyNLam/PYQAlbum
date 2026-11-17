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
 * A move tries, in order:
 *   1. A true MediaStore move (update `RELATIVE_PATH`) for indexed media rows —
 *      the provider moves the file and keeps the row, so nothing is deleted.
 *   2. MediaStore insert into any media folder (zero-permission for the four
 *      standard collections), then direct file I/O (All files access), then
 *      SAF for anything the File API cannot reach.
 *
 * A destination is always read back before the source is handed to the
 * recycle bin; there is no permanent-delete path in a move.
 *
 * Every file failure is reported as a [FileOpResult] with a human-readable
 * reason so the caller can tell the user WHY a file failed (permission denied,
 * read-only, destination unreadable…) instead of a bare count.
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
     * from its content:// URI: the storage ROOT (volume) + RELATIVE_PATH +
     * DISPLAY_NAME on Android 10+ (scoped-storage safe, no DATA permission
     * needed), falling back to the DATA column on older APIs. Returns null when
     * the row cannot be read. Trashed rows are still queryable this way, so the
     * recycle bin can show where a binned file lives.
     *
     * The result is always an ABSOLUTE path: `RELATIVE_PATH` alone is relative
     * to the volume ("DCIM/Camera/"), and callers delete the real file and look
     * up SAF grants by it — a relative path silently matched neither, which is
     * why deletes inside HONOR Parallel Space always failed.
     */
    fun resolveMediaPath(context: Context, uri: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // VOLUME_NAME is the precise answer (SD cards live elsewhere), but
            // it is not accepted by every provider/ROM — query it separately so
            // a rejected column degrades to the primary root instead of
            // resolving nothing at all.
            resolveProjectedPath(context, uri, withVolume = true)?.let { return it }
            resolveProjectedPath(context, uri, withVolume = false)?.let { return it }
        }
        return resolveDataPath(context, uri)
    }

    /** RELATIVE_PATH (+ optionally VOLUME_NAME) → absolute path, or null. */
    private fun resolveProjectedPath(context: Context, uri: String, withVolume: Boolean): String? {
        return try {
            val projection = if (withVolume) {
                arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.VOLUME_NAME
                )
            } else {
                arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME
                )
            }
            var path: String? = null
            context.contentResolver.query(Uri.parse(uri), projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val volumeIdx = if (withVolume) {
                        cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                    } else {
                        -1
                    }
                    val rel = if (relIdx >= 0) cursor.getString(relIdx) else null
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    if (rel != null) {
                        val volume = if (volumeIdx >= 0) cursor.getString(volumeIdx) else null
                        path = volumeRoot(volume) + File.separator +
                            rel.trimStart('/') + (name ?: "")
                    }
                }
            }
            path
        } catch (e: Exception) {
            Log.w(TAG, "resolveProjectedPath(${withVolume}) failed for $uri: ${e.message}")
            null
        }
    }

    /** The DATA column (absolute already) — legacy API, or a ROM that still fills it. */
    private fun resolveDataPath(context: Context, uri: String): String? {
        return try {
            var path: String? = null
            context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(MediaStore.MediaColumns.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) path = cursor.getString(idx)
                }
            }
            path
        } catch (e: Exception) {
            Log.w(TAG, "resolveDataPath failed for $uri: ${e.message}")
            null
        }
    }

    /**
     * Filesystem root of a MediaStore volume. `RELATIVE_PATH` is relative to
     * it, so an absolute path needs the volume too (an SD card is
     * `/storage/XXXX-XXXX/`, not the primary shared folder).
     */
    private fun volumeRoot(volume: String?): String {
        return if (volume == null ||
            volume == MediaStore.VOLUME_EXTERNAL_PRIMARY ||
            volume == "external"
        ) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
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
     * Map a real destination directory on primary storage to the `RELATIVE_PATH`
     * value MediaStore expects (e.g. `"Download/BabyName/"`). Returns null when
     * the directory is not on primary external storage (or on API < 29).
     *
     * Every folder is mapped, not just Download/Pictures/DCIM/Movies: the
     * provider may still accept the write for other media folders, and trying
     * is what gives copy/move access to all of them. When it refuses, the
     * caller falls back to file I/O (All files access) and then SAF.
     */
    fun resolveMediaStorePath(destDir: File?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (destDir == null) return null
        val extDir = Environment.getExternalStorageDirectory().absolutePath
        // Canonical paths so "/sdcard/..." and "/storage/emulated/0/..." resolve
        // the same way instead of silently falling through to the slow path.
        val absPath = try {
            destDir.canonicalPath
        } catch (e: Exception) {
            destDir.absolutePath
        }.trimEnd(File.separatorChar)
        val root = try {
            File(extDir).canonicalPath
        } catch (e: Exception) {
            extDir
        }.trimEnd(File.separatorChar)
        if (absPath == root || !absPath.startsWith("$root${File.separator}")) return null
        // rel = "Download/BabyName" or "Manga/Baby/BabyName"
        val rel = absPath.removePrefix(root).removePrefix(File.separator)
        if (rel.isBlank()) return null
        val parts = rel.split(File.separatorChar).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        // Normalise the top-level collection name (providers are not consistent
        // about case). ANY media folder is passed through — MediaStore accepts
        // RELATIVE_PATH writes inside the standard collections and rejects the
        // rest, in which case the caller falls back to file I/O / SAF. Trying
        // is what lets copy/move reach every folder, not just four roots.
        val canonicalTop = when (parts.first().lowercase()) {
            "download" -> "Download"
            "pictures" -> "Pictures"
            "dcim" -> "DCIM"
            "movies" -> "Movies"
            "music" -> "Music"
            "documents" -> "Documents"
            else -> parts.first()
        }
        val rest = parts.drop(1)
        return if (rest.isEmpty()) "$canonicalTop/" else "$canonicalTop/${rest.joinToString("/")}/"
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
            val relativePath = docId.removePrefix("primary:").trim('/')
            if (relativePath.isBlank()) return null
            val parts = relativePath.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            val canonicalTop = when (parts.first().lowercase()) {
                "download" -> "Download"
                "pictures" -> "Pictures"
                "dcim" -> "DCIM"
                "movies" -> "Movies"
                "music" -> "Music"
                "documents" -> "Documents"
                else -> parts.first()
            }
            val rest = parts.drop(1)
            if (rest.isEmpty()) "$canonicalTop/" else "$canonicalTop/${rest.joinToString("/")}/"
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
        // Never touch a source we cannot actually read: reporting a copy (and,
        // for a move, deleting the original) after an unreadable source would
        // be exactly the "the app deleted my photo" data-loss case.
        if (!isSourceReadable(context, sourceUri)) {
            return FileOpResult(false, null, "Cannot read the source image — nothing was changed")
        }
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
            return copyViaSaf(context, sourceUri, treeUri, destDir)
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
        if (!isSourceReadable(context, sourceUri)) {
            return FileOpResult(false, null, "Cannot read the source image — nothing was changed")
        }
        val relPath = resolveMediaStorePath(destDir) ?: resolveMediaStorePathFromTreeUri(treeUri)

        // Strategy 0: TRUE MediaStore move — update the row's RELATIVE_PATH.
        // MediaProvider moves the physical file and keeps the same row (same
        // content URI), so there is no copy to lose and no source to delete:
        // the operation either moves the file or reports failure. This is the
        // silent, prompt-free path for media folders the app may write.
        if (relPath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val moved = moveWithinMediaStore(context, sourceUri, relPath)
            if (moved.success) return moved
            Log.w(TAG, "In-place MediaStore move failed for $sourceUri (${moved.error}) — trying copy")
        }

        // Strategy 1: MediaStore copy (zero-permission for media collections)
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
            val copy = copyViaSaf(context, sourceUri, treeUri, destDir)
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
            val sourcePath = resolveFilePath(context, sourceUri)
            if (sourcePath != null && File(sourcePath).absolutePath == destFile.absolutePath) {
                return@withContext FileOpResult(
                    false, null,
                    "Source and destination are the same file — nothing was changed"
                )
            }
            // Expected byte count, so a truncated write is detected before a
            // move ever deletes the original.
            val expectedSize = sizeOf(context, contentUri)

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

            // Read the written file back (length must match the source) — a
            // short/failed write must never be reported as a successful copy.
            val written = destFile.length()
            if (written <= 0L ||
                (expectedSize != null && expectedSize > 0L && written != expectedSize)
            ) {
                destFile.delete()
                return@withContext FileOpResult(
                    false, null,
                    "The copy is incomplete (${written}B of ${expectedSize ?: "?"}B) — original kept"
                )
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

    /**
     * Move a file via real paths — always a verified copy whose source deletion
     * is deferred to the caller (recycle bin). There is deliberately no
     * renameTo path: it was the only irreversible branch, and a phantom rename
     * on virtualised storage plus the row delete lost user photos.
     */
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
            val sourcePath = resolveFilePath(context, sourceUri)
            if (sourcePath != null &&
                try { File(sourcePath).canonicalPath == destFile.canonicalPath } catch (e: Exception) { false }
            ) {
                return@withContext FileOpResult(
                    false, null,
                    "Source and destination are the same file — nothing was changed"
                )
            }

            // Always COPY + verify here, then let the caller send the original
            // to the recycle bin. A raw File.renameTo followed by a MediaStore
            // row delete was the one irreversible move path: virtualised
            // filesystems (HONOR Parallel Space) can acknowledge a rename that
            // leaves nothing usable behind, and the row delete then erases the
            // only reference — real data loss. The deferred-trash path keeps
            // the original recoverable until the copy is proven readable.
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
    private suspend fun copyViaSaf(
        context: Context,
        sourceUri: String,
        treeUri: Uri,
        destDir: File? = null
    ): FileOpResult =
        withContext(Dispatchers.IO) {
            try {
                val contentUri = Uri.parse(sourceUri)
                val fileName = getFileName(context, sourceUri)
                if (fileName.isBlank()) {
                    return@withContext FileOpResult(false, null, "Missing file name for $sourceUri")
                }

                // The grant may cover a whole tree (one prompt per top-level
                // folder), so walk down to the actual destination folder instead
                // of always writing into the tree root.
                val dirDocId = resolveSafDirDocumentId(context, treeUri, destDir)
                if (dirDocId.isBlank()) {
                    return@withContext FileOpResult(
                        false, null, "The granted folder could not be opened — nothing was moved"
                    )
                }
                val dirDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId)
                val availableName = findAvailableSafName(context, treeUri, dirDocId, fileName)
                val folderName = dirDocId.substringAfterLast(':')
                    .substringAfterLast('/')
                    .ifBlank { "this folder" }
                val docUri = createSafDocument(
                    context, treeUri, dirDocUri, mimeTypeFor(fileName), availableName
                )
                    ?: return@withContext FileOpResult(
                        false, null,
                        "Cannot create files in \"$folderName\" — the system file provider " +
                        "refused the write (ROM / Parallel Space restriction). Nothing was moved"
                    )

                val input = context.contentResolver.openInputStream(contentUri)
                val output = context.contentResolver.openOutputStream(docUri)
                if (input == null || output == null) {
                    // Clean up the empty document we just created
                    try { DocumentsContract.deleteDocument(context.contentResolver, docUri) } catch (_: Exception) {}
                    return@withContext FileOpResult(false, null, "Cannot read the source image or write to the chosen folder")
                }
                input.use { i -> output.use { o -> i.copyTo(o) } }

                // Verify the new document is real before any source deletion is
                // queued (see isReadableCopy) — a phantom SAF copy plus a
                // confirmed delete dialog would silently lose the image.
                val expected = sizeOf(context, contentUri)
                if (!isReadableCopy(context, docUri, expected)) {
                    try { DocumentsContract.deleteDocument(context.contentResolver, docUri) } catch (_: Exception) {}
                    return@withContext FileOpResult(
                        false, null,
                        "The copied file could not be read back from the chosen folder — original kept"
                    )
                }

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
                val expectedSize = sizeOf(context, contentUri)

                // Pick the collection that matches the file's media type: a
                // video inserted into the Images collection is rejected (or
                // stored as a broken row) on many providers. RELATIVE_PATH
                // controls the physical directory either way.
                val collection = collectionForFileName(fileName)

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

                // Safety: some providers resolve an insert into the SAME row /
                // on-disk file as the source (typically when the destination
                // folder already holds that file name). Writing the output
                // stream would then truncate the source itself, and deleting
                // the "source" afterwards would destroy the only copy.
                if (destUri == contentUri || sameMediaPath(context, destUri, contentUri)) {
                    return@withContext FileOpResult(
                        false, null,
                        "Source and destination are the same file — nothing was changed"
                    )
                }

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

                // Notify the media scanner so other apps (WeChat's chat picker,
                // file browsers) can see the new image immediately.
                destUri?.let { MediaStoreUtils.publishImage(context, it) }

                // Read the published copy BACK before claiming success. A move
                // deletes the original only after this returns true — virtualised
                // providers (HONOR Parallel Space) have been observed to publish
                // rows that resolve to an empty file, and deleting the source
                // after such a "successful" copy is how a move eats the data.
                if (!isReadableCopy(context, destUri, expectedSize)) {
                    try { context.contentResolver.delete(destUri, null, null) } catch (_: Exception) {}
                    return@withContext FileOpResult(
                        false, null,
                        "The copied file could not be read back from $relativePath — original kept"
                    )
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

    /** Create a new document inside [dirDocUri], returning its content uri. */
    private fun createSafDocument(
        context: Context,
        treeUri: Uri,
        dirDocUri: Uri,
        mimeType: String,
        displayName: String
    ): Uri? {
        // Providers are inconsistent about what they accept. Some only work with
        // a document URI, some only with the tree URI itself, and a few reject a
        // typed MIME (creating nothing for "image/jpeg") while accepting
        // octet-stream. Inside a clone space (HONOR Parallel Space) the first
        // combination often fails even though a later one succeeds, so try every
        // combination before declaring the folder unwritable.
        val treeDoc = try {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
        } catch (e: Exception) {
            null
        }
        val targets = listOfNotNull(dirDocUri, treeUri, treeDoc).distinct()
        val mimes = if (mimeType == "application/octet-stream") {
            listOf(mimeType)
        } else {
            listOf(mimeType, "application/octet-stream")
        }
        for (target in targets) {
            for (mime in mimes) {
                try {
                    val created = DocumentsContract.createDocument(
                        context.contentResolver, target, mime, displayName
                    )
                    if (created != null) return created
                } catch (e: Exception) {
                    Log.w(TAG, "createDocument failed ($mime) for $displayName", e)
                }
            }
        }
        return null
    }

    /**
     * Document id of [destDir] inside [treeUri], creating missing intermediate
     * folders. Falls back to the tree root when the directory is not under the
     * tree (or cannot be created), which keeps a direct folder pick working.
     */
    private fun resolveSafDirDocumentId(context: Context, treeUri: Uri, destDir: File?): String {
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return ""
        }
        if (destDir == null) return treeDocId
        val treeDir = com.pyqcr.util.SafGrantStore.pathForTreeUri(treeUri) ?: return treeDocId
        val base = canonicalPath(treeDir)
        val target = canonicalPath(destDir)
        val baseWithSep = base.trimEnd(File.separatorChar).toString() + File.separator
        if (!target.startsWith(baseWithSep)) return treeDocId
        var docId = treeDocId
        for (segment in target.removePrefix(baseWithSep).split(File.separatorChar)) {
            if (segment.isBlank()) continue
            docId = findSafChildDocumentId(context, treeUri, docId, segment)
                ?: createSafChildDir(context, treeUri, docId, segment)
                ?: return docId
        }
        return docId
    }

    private fun canonicalPath(file: File): String = try {
        file.canonicalPath
    } catch (e: Exception) {
        file.absolutePath
    }

    /** Document id of the child of [parentDocId] named [name], or null. */
    private fun findSafChildDocumentId(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        name: String
    ): String? {
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0 && idIdx >= 0 &&
                        cursor.getString(nameIdx) == name
                    ) {
                        return cursor.getString(idIdx)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "findSafChildDocumentId failed for $name", e)
            null
        }
    }

    /** Create sub-directory [name] under [parentDocId], returning its document id. */
    private fun createSafChildDir(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        name: String
    ): String? {
        return try {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            val created = DocumentsContract.createDocument(
                context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
            ) ?: return null
            DocumentsContract.getDocumentId(created)
        } catch (e: Exception) {
            Log.w(TAG, "createSafChildDir failed for $name", e)
            null
        }
    }

    /** Find a display name that does not collide with existing children of [dirDocId]. */
    private fun findAvailableSafName(
        context: Context,
        treeUri: Uri,
        dirDocId: String,
        fileName: String
    ): String {
        val existing = querySafChildNames(context, treeUri, dirDocId)
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

    /** List display names of [dirDocId]'s immediate children. */
    private fun querySafChildNames(context: Context, treeUri: Uri, dirDocId: String): Set<String> {
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
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
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        else -> "image/jpeg"
    }

    private fun isVideoFileName(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in
            setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi")

    /** MediaStore collection matching the file's type (images vs videos). */
    private fun collectionForFileName(fileName: String): Uri =
        if (isVideoFileName(fileName)) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    // ---------- Destination verification (move must never eat the source) ----------

    /**
     * True when both content URIs resolve to the same on-disk file. Guards
     * against a provider aliasing a fresh insert to the source row — writing
     * that destination would overwrite the original.
     */
    private fun sameMediaPath(context: Context, a: Uri, b: Uri): Boolean {
        if (a == b) return true
        val pathA = mediaDataPath(context, a) ?: return false
        val pathB = mediaDataPath(context, b) ?: return false
        return pathA == pathB
    }

    private fun mediaDataPath(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Query the SIZE column of a content URI (null when unreadable). */
    private fun sizeOf(context: Context, uri: Uri): Long? {
        return try {
            val cursor = context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
            )
            if (cursor == null) return null
            var value: Long? = null
            try {
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) value = cursor.getLong(idx)
                }
            } finally {
                cursor.close()
            }
            value
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verify a freshly written destination is REAL — openable and the expected
     * number of bytes. Strict/virtualised providers (confirmed on HONOR
     * Parallel Space) can publish a row that looks successful yet resolves to
     * an empty or unreadable file. A move deletes the original only after this
     * check passes, so a phantom copy can never turn into data loss.
     * [expectedSize] is the source's size when known; a positive-but-shorter
     * result means a truncated write.
     */
    private fun isReadableCopy(context: Context, uri: Uri, expectedSize: Long?): Boolean {
        val fd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        if (fd == null) return false
        var ok = false
        try {
            val stat = fd.statSize
            ok = if (expectedSize != null && expectedSize > 0L) {
                stat >= expectedSize ||
                    (stat < 0L && (sizeOf(context, uri) ?: -1L) >= expectedSize)
            } else {
                stat > 0L || (sizeOf(context, uri) ?: -1L) > 0L
            }
        } catch (e: Exception) {
            ok = false
        } finally {
            try { fd.close() } catch (_: Exception) {}
        }
        return ok
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

    /** Read (RELATIVE_PATH, SIZE) of a MediaStore row; null when it is gone. */
    private fun mediaLocation(context: Context, uri: Uri): Pair<String?, Long?>? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val rel = if (relIdx >= 0 && !cursor.isNull(relIdx)) cursor.getString(relIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                rel to size
            }
        } catch (e: Exception) {
            Log.w(TAG, "mediaLocation failed for $uri: ${e.message}")
            null
        }
    }

    /**
     * Move a MediaStore row (and its physical file) by updating `RELATIVE_PATH`.
     *
     * This is the safest possible move: the provider moves the bytes and keeps
     * the SAME row, so the content URI — and every tag / rating / favourite
     * keyed by it — stays valid, and there is no copy that could be lost or
     * source that could be deleted. The move is verified by reading the row's
     * new folder + size and opening the file before reporting success; on a
     * failed verification the row is put back where it was.
     *
     * Fails (so the caller falls back to copy) when the row is not indexed,
     * already lives in the destination, or the provider refuses the update
     * (foreign row without write access, non-media folder, SD card).
     */
    private suspend fun moveWithinMediaStore(
        context: Context,
        sourceUri: String,
        relativePath: String
    ): FileOpResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext FileOpResult(false, null, "In-place move needs Android 10+")
        }
        val uri = try {
            Uri.parse(sourceUri)
        } catch (e: Exception) {
            return@withContext FileOpResult(false, null, "Invalid source URI")
        }
        val before = mediaLocation(context, uri)
            ?: return@withContext FileOpResult(false, null, "The source is not indexed by MediaStore")
        val expectedSize = before.second
        val originalPath = before.first
        if (originalPath != null && originalPath.equals(relativePath, ignoreCase = true)) {
            // Already there — a no-op, but a successful one.
            return@withContext FileOpResult(true, sourceUri, null)
        }

        val updated = try {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                },
                null, null
            )
        } catch (e: Exception) {
            Log.w(TAG, "RELATIVE_PATH update rejected for $sourceUri -> $relativePath: ${e.message}")
            0
        }
        if (updated <= 0) {
            return@withContext FileOpResult(
                false, null,
                "The device did not allow moving this file in place"
            )
        }

        val after = mediaLocation(context, uri)
        val landed = after != null &&
            after.first != null && after.first.equals(relativePath, ignoreCase = true) &&
            (expectedSize == null || expectedSize <= 0L || (after.second ?: -1L) == expectedSize) &&
            isReadableCopy(context, uri, expectedSize)
        if (!landed) {
            // Put the row back so a half-applied move cannot leave the photo
            // unreachable at neither the old nor the new path.
            if (originalPath != null) {
                try {
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, originalPath)
                        },
                        null, null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not restore $sourceUri to $originalPath: ${e.message}")
                }
            }
            return@withContext FileOpResult(
                false, null,
                "The destination did not accept the move — original kept"
            )
        }

        MediaStoreUtils.publishImage(context, uri)
        FileOpResult(true, uri.toString(), null)
    }

    /**
     * True when the source can actually be opened for reading. Every copy/move
     * checks this first, so an unreadable/vanished source is reported instead
     * of producing an empty destination and deleting the original.
     */
    fun isSourceReadable(context: Context, uriStr: String): Boolean {
        return try {
            val fd = context.contentResolver.openFileDescriptor(Uri.parse(uriStr), "r")
            fd?.use { it.statSize != 0L } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Quick pre-delete check for a move destination: a content URI must be
     * openable and non-empty, a real path must exist with bytes. Callers run
     * this immediately before sending the original to the recycle bin, so a
     * destination that disappeared (or was never real) keeps the original.
     */
    fun isVerifiedDestination(context: Context, destination: String): Boolean {
        if (destination.isBlank()) return false
        if (destination.startsWith("content://")) {
            return try {
                val uri = Uri.parse(destination)
                val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
                fd.use {
                    it.statSize > 0L || (sizeOf(context, uri) ?: 0L) > 0L
                }
            } catch (e: Exception) {
                false
            }
        }
        if (destination.startsWith("/")) {
            val file = File(destination)
            return file.exists() && file.length() > 0L
        }
        return false
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
     * Delete the file behind [path] with NO dialog, through a persisted SAF
     * folder grant the user already approved (see
     * [com.pyqcr.util.SafGrantStore]).
     *
     * This is the one route that can remove media *silently* inside an app
     * clone / HONOR Parallel Space. There the clone owns neither the MediaStore
     * row (it was contributed by the main-space app) nor All files access, so
     * both the direct `IS_TRASHED` write and `ContentResolver.delete` are
     * refused — Android then only offers its consent dialog. A SAF write grant
     * on the *folder*, however, lets [DocumentsContract.deleteDocument] remove
     * the real file without any permission prompt, exactly like the SAF copy
     * and move paths already do.
     *
     * Returns true when the provider reported the document as deleted.
     */
    fun deleteFileViaSaf(context: Context, path: String): Boolean {
        val file = try {
            File(path)
        } catch (e: Exception) {
            return false
        }
        val dir = file.parentFile ?: return false
        val treeUri = com.pyqcr.util.SafGrantStore.findGrant(context, dir) ?: return false
        return try {
            val dirDocId = resolveSafDirDocumentId(context, treeUri, dir)
            if (dirDocId.isBlank()) return false
            val fileDocId = findSafChildDocumentId(context, treeUri, dirDocId, file.name)
                ?: return false
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
            DocumentsContract.deleteDocument(context.contentResolver, docUri)
        } catch (e: Exception) {
            Log.w(TAG, "SAF delete failed for $path", e)
            false
        }
    }

    /**
     * How far a delete got, and what the screen should ask the user next.
     *
     * Android only lets an app remove media it did not contribute through two
     * routes: All files access, or a SAF write grant on the containing folder.
     * [attemptDelete] walks them in that order so the "move to trash?" consent
     * dialog is normally never needed.
     */
    sealed class DeleteOutcome {
        /** Nothing left to do. [removed] = really gone, [trashed] = recyclable bin. */
        data class Done(val removed: List<String>, val trashed: List<String>) : DeleteOutcome()

        /** Needs the one-time All files access grant before rows can go. */
        data class NeedsAllFiles(val remaining: List<String>) : DeleteOutcome()

        /** Needs a one-time SAF write grant on [folder]. */
        data class NeedsFolderGrant(
            val folder: File,
            val remaining: List<String>
        ) : DeleteOutcome()

        /** Last resort: the platform's own trash/delete consent dialog. */
        data class NeedsConsent(val remaining: List<String>) : DeleteOutcome()
    }

    /**
     * Delete [uris] with as little prompting as possible.
     *
     * 1. [trashMediaSilently] — direct `IS_TRASHED = 1` write, i.e. the
     *    recoverable recycle bin, no dialog. Works for the app's own rows and
     *    whenever All files access is granted.
     * 2. [deleteMoveSources] — row delete, then the real file, then
     *    [deleteFileViaSaf] through a remembered folder grant.
     * 3. [forceDeleteByPath] — the real file at every address we can build
     *    (volume root, `/sdcard` alias, DATA). This is what removes foreign
     *    media with All files access, in the normal profile AND in a clone.
     * 4. Everything still left is explained back to the caller, which asks for
     *    the SAF folder grant once (first inside a clone, where that grant is
     *    the route that works), else for All files access once, and only then
     *    falls back to the platform consent dialog.
     *
     * [allowAllFilesPrompt] / [allowFolderPrompt] let the caller suppress a
     * prompt it has already shown; [declinedFolders] holds folders whose picker
     * the user cancelled, so the app does not nag about them again.
     */
    suspend fun attemptDelete(
        context: Context,
        uris: List<String>,
        allowAllFilesPrompt: Boolean = true,
        allowFolderPrompt: Boolean = true,
        declinedFolders: Set<String> = emptySet()
    ): DeleteOutcome = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext DeleteOutcome.Done(emptyList(), emptyList())

        val refused = trashMediaSilently(context, uris)
        val trashed = uris.filter { it !in refused }
        var remaining = refused
        if (remaining.isNotEmpty()) {
            val removed = deleteMoveSources(context, remaining)
            remaining = remaining.filter { it !in removed }
            if (remaining.isEmpty()) {
                return@withContext DeleteOutcome.Done(trashed + removed, trashed)
            }
        }
        if (remaining.isNotEmpty()) {
            // Every remaining row has a refused row delete. Remove the file
            // itself through each possible on-disk address before asking the
            // user for anything — with All files access this is what actually
            // deletes media this app does not own.
            val removed = forceDeleteByPath(context, remaining)
            remaining = remaining.filter { it !in removed }
            if (remaining.isEmpty()) {
                return@withContext DeleteOutcome.Done(trashed + removed, trashed)
            }
        }
        if (remaining.isNotEmpty()) {
            Log.i(
                TAG,
                "attemptDelete: ${uris.size} requested, ${trashed.size} trashed, " +
                    "${remaining.size} still left after the silent routes"
            )
        }
        if (allowFolderPrompt && com.pyqcr.util.PermissionHelper.isCloneProfile()) {
            // Inside a clone the SAF grant is the route that actually works —
            // ask for it before the All-files page, which a clone inherits but
            // cannot use.
            val folder = folderNeedingDeleteGrant(context, remaining)
            if (folder != null && folder.absolutePath !in declinedFolders) {
                return@withContext DeleteOutcome.NeedsFolderGrant(folder, remaining)
            }
        }
        if (allowAllFilesPrompt &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !com.pyqcr.util.PermissionHelper.hasAllFilesAccess()
        ) {
            return@withContext DeleteOutcome.NeedsAllFiles(remaining)
        }
        if (allowFolderPrompt) {
            val folder = folderNeedingDeleteGrant(context, remaining)
            if (folder != null && folder.absolutePath !in declinedFolders) {
                return@withContext DeleteOutcome.NeedsFolderGrant(folder, remaining)
            }
        }
        DeleteOutcome.NeedsConsent(remaining)
    }

    /**
     * Delete the real files behind [uris] at every address we can build:
     * `RELATIVE_PATH` on the primary root, the DATA column, and the `/sdcard`
     * alias some ROMs (and clone spaces) report instead of
     * `/storage/emulated/0`. A URI only counts as removed when its file is
     * really gone, so a refused delete still reaches the user honestly.
     *
     * This is the silent route that makes deletes work with All files access
     * even when MediaProvider refuses the row write (foreign media, clones).
     */
    suspend fun forceDeleteByPath(context: Context, uris: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val deleted = mutableListOf<String>()
            for (u in uris) {
                if (u.isBlank()) continue
                val candidates = LinkedHashSet<String>()
                resolveMediaPath(context, u)?.let { path ->
                    candidates += path
                    val primary = Environment.getExternalStorageDirectory().absolutePath
                    if (path.startsWith(primary + File.separator)) {
                        candidates += "/sdcard" + path.removePrefix(primary)
                    }
                }
                resolveDataPath(context, u)?.let { candidates += it }
                val goneByFile = candidates.any { path ->
                    val file = File(path)
                    val existed = try {
                        file.exists()
                    } catch (e: Exception) {
                        false
                    }
                    if (!existed) {
                        // A candidate the row does not point at — not evidence.
                        false
                    } else {
                        try {
                            file.delete()
                            !file.exists()
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
                // A clone may have no usable absolute path at all; the SAF
                // grant is matched on the row's RELATIVE_PATH instead.
                val gone = goneByFile || deleteViaSafByRelativePath(context, u)
                if (gone) {
                    deleteRowAnyWay(context, u)
                    try {
                        candidates.firstOrNull()?.let { MediaStoreUtils.scanFile(context, it) }
                    } catch (_: Exception) {
                    }
                    deleted += u
                }
            }
            deleted
        }

    /**
     * Folder to ask a one-off SAF write grant for, so [uris] can be deleted
     * silently through [deleteFileViaSaf], or null when every row already sits
     * under a grant that can actually reach it.
     *
     * A remembered grant is only trusted when the file shows up in the tree:
     * a stale/wrong grant would otherwise send the delete straight to the
     * platform consent dialog, which a Parallel Space clone always refuses.
     */
    suspend fun folderNeedingDeleteGrant(context: Context, uris: List<String>): File? =
        withContext(Dispatchers.IO) {
            for (u in uris) {
                // A grant that already reaches this row by RELATIVE_PATH needs
                // no new prompt — and works whether or not an absolute path
                // can be built for the clone's storage.
                if (canDeleteViaSafByRelativePath(context, u)) continue
                val path = resolveMediaPath(context, u)
                if (path != null) {
                    val file = File(path)
                    val dir = file.parentFile ?: continue
                    val grant = com.pyqcr.util.SafGrantStore.findGrant(context, dir)
                    if (grant != null && canDeleteViaSaf(context, grant, file)) continue
                    return@withContext dir
                }
                // No absolute path: name the folder from RELATIVE_PATH so the
                // picker can still be opened at the right place.
                val folder = relativeFolderFile(context, u)
                if (folder != null) return@withContext folder
            }
            null
        }

    /** True when [treeUri] lists [file], i.e. [deleteFileViaSaf] can remove it. */
    private fun canDeleteViaSaf(context: Context, treeUri: Uri, file: File): Boolean {
        return try {
            val dir = file.parentFile ?: return false
            val dirDocId = resolveSafDirDocumentId(context, treeUri, dir)
            if (dirDocId.isBlank()) return false
            findSafChildDocumentId(context, treeUri, dirDocId, file.name) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Folder segments + file name of a MediaStore row, as the provider reports
     * them (`RELATIVE_PATH` is volume-relative, e.g. "DCIM/Camera/"). Unlike
     * [resolveMediaPath] this needs no volume root, so it still works in an
     * app clone whose storage root (`/storage/emulated/<user>/`) does not match
     * the tree the SAF grant was taken on.
     */
    private fun rowRelativeParts(context: Context, uri: String): Pair<List<String>, String>? {
        return try {
            var folder: List<String>? = null
            var name: String? = null
            context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val rel = if (relIdx >= 0) cursor.getString(relIdx) else null
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (rel != null) folder = rel.split('/').filter { it.isNotBlank() }
                }
            }
            val folderValue = folder
            val nameValue = name
            if (folderValue != null && !nameValue.isNullOrBlank()) {
                folderValue to nameValue
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "rowRelativeParts failed for $uri: ${e.message}")
            null
        }
    }

    /** Volume-relative folder of a row as a File, built without any absolute-path query. */
    private fun relativeFolderFile(context: Context, uri: String): File? {
        val folder = rowRelativeParts(context, uri)?.first ?: return null
        if (folder.isEmpty()) return null
        return File(
            Environment.getExternalStorageDirectory(),
            folder.joinToString(File.separator)
        )
    }

    /** Path segments of a SAF tree's document id ("primary:DCIM/Camera" → ["DCIM","Camera"]). */
    private fun treeDocumentSegments(treeUri: Uri): List<String>? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val colon = docId.indexOf(':')
            val path = if (colon >= 0) docId.substring(colon + 1) else docId
            path.split('/').filter { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Document URI of a row inside any persisted SAF write grant, matched on
     * the row's `RELATIVE_PATH` instead of an absolute file path.
     *
     * This is the route that removes media inside HONOR Parallel Space: the
     * clone owns neither the MediaStore row nor All files access, and its
     * storage root can differ from the tree the grant was taken on, so
     * [deleteFileViaSaf]'s path comparison may find no grant even though one
     * covers the folder. Comparing `primary:DCIM/…` style document ids has no
     * such dependency.
     */
    private fun findSafDocUriForRow(context: Context, uri: String): Uri? {
        val parts = rowRelativeParts(context, uri) ?: return null
        val name = parts.second
        val rowSegments = parts.first + name
        val permissions = try {
            context.contentResolver.persistedUriPermissions
        } catch (e: Exception) {
            Log.w(TAG, "persistedUriPermissions unavailable", e)
            return null
        }
        for (permission in permissions) {
            if (!permission.isWritePermission) continue
            val treeUri = permission.uri
            val treeSegments = treeDocumentSegments(treeUri) ?: continue
            if (treeSegments.size >= rowSegments.size) continue
            var matches = true
            for (i in treeSegments.indices) {
                if (!treeSegments[i].equals(rowSegments[i], ignoreCase = true)) {
                    matches = false
                    break
                }
            }
            if (!matches) continue
            var docId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (e: Exception) {
                null
            } ?: continue
            var reachable = true
            for (i in treeSegments.size until rowSegments.size - 1) {
                val child = findSafChildDocumentId(context, treeUri, docId, rowSegments[i])
                if (child == null) {
                    reachable = false
                    break
                }
                docId = child
            }
            if (!reachable) continue
            val fileDocId = findSafChildDocumentId(context, treeUri, docId, name) ?: continue
            return try {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    /**
     * Delete a row's real file through a remembered SAF grant, using only its
     * volume-relative path. Returns true only when the provider confirmed the
     * document deletion; the stale MediaStore row is removed afterwards.
     */
    private fun deleteViaSafByRelativePath(context: Context, uri: String): Boolean {
        val docUri = findSafDocUriForRow(context, uri) ?: return false
        return try {
            val deleted = DocumentsContract.deleteDocument(context.contentResolver, docUri)
            if (deleted) {
                try {
                    context.contentResolver.delete(Uri.parse(uri), null, null)
                } catch (_: Exception) {
                }
                // Drop the stale row from the gallery: rescan by absolute path
                // when one is known, otherwise rebuild it from RELATIVE_PATH.
                try {
                    val scanPath = resolveMediaPath(context, uri)
                        ?: rowRelativeParts(context, uri)?.let { parts ->
                            relativeFolderFile(context, uri)
                                ?.let { File(it, parts.second).absolutePath }
                        }
                    if (scanPath != null) MediaStoreUtils.scanFile(context, scanPath)
                } catch (_: Exception) {
                }
                Log.i(TAG, "SAF relative-path delete removed $uri")
            }
            deleted
        } catch (e: Exception) {
            Log.w(TAG, "SAF relative-path delete failed for $uri", e)
            false
        }
    }

    /** True when [deleteViaSafByRelativePath] could remove [uri] without a prompt. */
    private fun canDeleteViaSafByRelativePath(context: Context, uri: String): Boolean {
        return try {
            findSafDocUriForRow(context, uri) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Every provider address this row may be writable through.
     *
     * A clone / forked MediaProvider does not always accept the same URI form:
     * the generic `content://media/external/…` address, the
     * `MediaStore.getMediaUri()` rewrite (`…/external_primary/…`) and a rebuilt
     * `…/file/<id>` entry can each hit a different internal volume. Writes on
     * the wrong form return 0 with no exception, which looks like "the clone
     * cannot delete" — so every silent route tries all of them.
     */
    private fun mediaUriCandidates(context: Context, raw: String): List<Uri> {
        val out = mutableListOf<Uri>()
        val parsed = try {
            Uri.parse(raw)
        } catch (e: Exception) {
            return out
        }
        out += parsed
        val volumeUri = try {
            MediaStore.getMediaUri(context, parsed)
        } catch (e: Exception) {
            null
        }
        if (volumeUri != null && !out.contains(volumeUri)) out += volumeUri
        val id = try {
            parsed.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
        if (id != null) {
            val volumes = mutableListOf<String>()
            uriVolumeName(parsed)?.let { volumes += it }
            volumes += MediaStore.VOLUME_EXTERNAL_PRIMARY
            volumes += MediaStore.VOLUME_EXTERNAL
            for (volume in volumes) {
                try {
                    // Built by hand instead of MediaStore.Files.getContentUri(volume)
                    // so no extra provider API is required here.
                    val filesUri = Uri.parse("content://media/$volume/file")
                    val rebuilt = ContentUris.withAppendedId(filesUri, id)
                    if (!out.contains(rebuilt)) out += rebuilt
                } catch (e: Exception) {
                }
            }
        }
        return out
    }

    /** Volume segment of a MediaStore URI (`content://media/<volume>/…`). */
    private fun uriVolumeName(parsed: Uri): String? = try {
        parsed.pathSegments.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** Remove the MediaStore row through whichever candidate address accepts it. */
    private suspend fun deleteRowAnyWay(context: Context, raw: String): Boolean =
        withContext(Dispatchers.IO) {
            for (candidate in mediaUriCandidates(context, raw)) {
                val n = try {
                    context.contentResolver.delete(candidate, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "row delete refused for $candidate", e)
                    0
                }
                if (n > 0) return@withContext true
            }
            false
        }

    /**
     * URIs from [uris] that the provider STILL lists after a delete — i.e. the
     * rows whose deletion silently failed (a clone can accept a delete/trash
     * request and then do nothing). Used to report an honest x/y count.
     */
    suspend fun survivingUris(context: Context, uris: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            uris.filter { raw ->
                try {
                    context.contentResolver.query(
                        Uri.parse(raw),
                        arrayOf(MediaStore.MediaColumns._ID),
                        null, null, null
                    )?.use { it.count > 0 } ?: false
                } catch (e: Exception) {
                    false
                }
            }
        }

    /**
     * Human-readable diagnostic of why a delete could not remove [uris].
     * Shown in the app's failure dialog so the exact clone behaviour can be
     * reported back (volume form, row owner, path resolution, SAF reach).
     */
    suspend fun describeDeleteFailure(context: Context, uris: List<String>): String =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.append("secondary user (clone): ")
                .append(com.pyqcr.util.PermissionHelper.isCloneProfile())
            sb.append("\nall files access: ")
                .append(com.pyqcr.util.PermissionHelper.hasAllFilesAccess())
            sb.append("\nSDK: ").append(Build.VERSION.SDK_INT)
            val grants = try {
                context.contentResolver.persistedUriPermissions
            } catch (e: Exception) {
                emptyList()
            }
            sb.append("\nSAF write grants: ").append(grants.count { it.isWritePermission })
            sb.append("\nrequested: ").append(uris.size).append(", still listed: ")
                .append(uris.count { u ->
                    try {
                        context.contentResolver.query(
                            Uri.parse(u), arrayOf(MediaStore.MediaColumns._ID), null, null, null
                        )?.use { it.count > 0 } ?: false
                    } catch (e: Exception) {
                        false
                    }
                })
            uris.take(3).forEach { raw ->
                sb.append("\n— ").append(raw)
                val parsed = try {
                    Uri.parse(raw)
                } catch (e: Exception) {
                    null
                }
                if (parsed != null) {
                    sb.append("\n  volume: ").append(uriVolumeName(parsed) ?: "<none>")
                    val owner = try {
                        context.contentResolver.query(
                            parsed,
                            arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                            null, null, null
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    } catch (e: Exception) {
                        "?"
                    }
                    sb.append("\n  owner: ").append(owner ?: "null")
                    val rel = rowRelativeParts(context, raw)
                    sb.append("\n  RELATIVE_PATH: ").append(rel?.first?.joinToString("/") ?: "<none>")
                    sb.append("\n  DISPLAY_NAME: ").append(rel?.second ?: "<none>")
                    val abs = resolveMediaPath(context, raw)
                    val exists = if (abs != null) {
                        try {
                            File(abs).exists()
                        } catch (e: Exception) {
                            false
                        }
                    } else {
                        false
                    }
                    sb.append("\n  path: ").append(abs ?: "<unresolved>")
                        .append(" (exists=").append(exists).append(")")
                    val mediaUri = try {
                        MediaStore.getMediaUri(context, parsed)?.toString()
                    } catch (e: Exception) {
                        "<error>"
                    }
                    sb.append("\n  mediaUri: ").append(mediaUri ?: "<null>")
                    sb.append("\n  SAF can reach file: ").append(canDeleteViaSafByRelativePath(context, raw))
                }
            }
            sb.append("\nLog: adb logcat -s FileOpHelper")
            sb.toString()
        }

    /**
     * Move [uris] into the recoverable system recycle bin WITHOUT showing the
     * platform confirmation dialog: set `IS_TRASHED = 1` directly, which is the
     * exact write MediaProvider performs when the user confirms a
     * `createTrashRequest` (it renames the file to `.trashed-<time>-<name>` and
     * flags the row). This needs the row to be one this app created or
     * All-files access; rows owned by another app raise
     * `RecoverableSecurityException`, and those URIs are returned untouched.
     *
     * Used by the Delete action so no "move to trash?" prompt is raised. Callers
     * hand the URIs returned here to [deleteMoveSources] (row / real file /
     * SAF folder grant) and only then to the platform consent request — the
     * provider refuses a direct flag write for media another app contributed.
     *
     * Returns the URIs that could NOT be trashed.
     */
    suspend fun trashMediaSilently(context: Context, uris: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext uris
            val failed = mutableListOf<String>()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 1)
            }
            for (raw in uris) {
                var trashed = false
                try {
                    val parsed = Uri.parse(raw)
                    val candidates = mediaUriCandidates(context, raw)
                    for (candidate in candidates) {
                        val n = try {
                            context.contentResolver.update(candidate, values, null, null)
                        } catch (e: Exception) {
                            // RecoverableSecurityException for a foreign row —
                            // the caller's fallback covers it.
                            Log.w(TAG, "silent trash of $candidate refused", e)
                            -1
                        }
                        if (n > 0) {
                            trashed = true
                            Log.i(TAG, "trashMediaSilently: $raw trashed via $candidate ($n row)")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "trashMediaSilently failed for $raw", e)
                }
                if (!trashed) failed += raw
            }
            failed
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
        deleteMoveSources(context, uris).size
    }

    /**
     * Delete move sources directly, with NO system confirmation dialog.
     *
     * A move must leave the file only in the destination, so the caller runs
     * this after the copy was read back and verified. Three routes, all silent:
     * the MediaStore row (allowed for rows this app owns and whenever All files
     * access is granted), then deleting the real file behind it, then a
     * [deleteFileViaSaf] delete through a remembered folder grant (the only
     * route that works inside an app clone / Parallel Space, where the app owns
     * neither the row nor All files access). The now-stale MediaStore row is
     * cleaned by re-scanning the deleted path. Returns the URIs that are really
     * gone; anything left stays in the gallery and is reported, never silently
     * lost.
     */
    suspend fun deleteMoveSources(context: Context, uris: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val deleted = mutableListOf<String>()
            for (u in uris) {
                if (u.isBlank()) continue
                try {
                    var rowDeleted = false
                    for (candidate in mediaUriCandidates(context, u)) {
                        try {
                            if (context.contentResolver.delete(candidate, null, null) > 0) {
                                rowDeleted = true
                                break
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Resolver delete refused for $candidate — trying the next form", e)
                        }
                    }
                    if (rowDeleted) {
                        deleted += u
                        continue
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Resolver delete refused for $u — trying the file path", e)
                }
                val path = resolveMediaPath(context, u)
                var removed = false
                if (path != null) {
                    val removedByFile = try {
                        File(path).let { it.exists() && it.delete() }
                    } catch (e: Exception) {
                        false
                    }
                    // Inside an app clone / Parallel Space neither the row nor
                    // the raw path is writable, but a remembered SAF grant on
                    // the folder is — that grant is what makes this silent.
                    val removedBySaf = !removedByFile && deleteFileViaSaf(context, path)
                    removed = removedByFile || removedBySaf
                }
                // Last silent route, independent of any absolute path guess:
                // match the row's RELATIVE_PATH against the persisted SAF
                // trees (a clone's storage root need not match them).
                if (!removed) removed = deleteViaSafByRelativePath(context, u)
                if (removed) {
                    deleteRowAnyWay(context, u)
                    if (path != null) {
                        try {
                            MediaStoreUtils.scanFile(context, path)
                        } catch (_: Exception) {
                        }
                    }
                    deleted += u
                }
            }
            deleted
        }

    /**
     * Probe whether the app can really create files in a destination BEFORE a
     * move starts. Copies the strategy order of [copyImage]: real file path,
     * SAF tree, then a MediaStore insert — each probe is created and removed
     * again, so nothing is left behind.
     */
    suspend fun canWriteToDestination(
        context: Context,
        destDir: File?,
        treeUri: Uri? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val probeName = ".pyqcr_write_probe_${System.nanoTime()}"
        if (destDir != null) {
            try {
                val dirExists = destDir.isDirectory || destDir.mkdirs()
                if (dirExists) {
                    val probe = File(destDir, probeName)
                    if (probe.createNewFile()) {
                        probe.delete()
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "File probe failed for $destDir", e)
            }
        }
        if (treeUri != null) {
            try {
                val dirDocId = resolveSafDirDocumentId(context, treeUri, destDir)
                if (dirDocId.isNotBlank()) {
                    val dirDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId)
                    val created = createSafDocument(
                        context, treeUri, dirDocUri, "application/octet-stream", probeName
                    )
                    if (created != null) {
                        try {
                            DocumentsContract.deleteDocument(context.contentResolver, created)
                        } catch (_: Exception) {
                        }
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SAF probe failed for $treeUri", e)
            }
        }
        // Standard media collections accept an insert even when direct file I/O
        // is blocked, so probe that too (pending rows stay invisible).
        val relPath = resolveMediaStorePath(destDir) ?: resolveMediaStorePathFromTreeUri(treeUri)
        if (relPath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var probeUri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$probeName.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                probeUri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                val target = probeUri
                val ok = target != null &&
                    context.contentResolver.openOutputStream(target)
                        ?.use { it.write(ByteArray(8)) } != null
                if (ok) return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore probe failed for $relPath", e)
            } finally {
                probeUri?.let { uri ->
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        false
    }

    /**
     * Current DISPLAY_NAME of a MediaStore row (image or video), or null when
     * the row is gone / unreadable.
     */
    fun displayNameOf(context: Context, uriStr: String): String? {
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            return null
        }
        return try {
            var name: String? = null
            val c = context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )
            if (c != null) {
                try {
                    if (c.moveToFirst()) name = c.getString(0)
                } finally {
                    c.close()
                }
            }
            name
        } catch (e: Exception) {
            Log.w(TAG, "displayNameOf failed for $uriStr", e)
            null
        }
    }

    /**
     * Rename a MediaStore image/video row in place: updates DISPLAY_NAME
     * (+ TITLE) — on API 29+ MediaProvider renames the underlying file too,
     * for rows the app may write (its own files, or anything once All-files
     * access is granted; otherwise 0 rows are affected and this returns
     * false). [newBaseName] is the name WITHOUT extension — the row's current
     * extension is kept. The content URI does not change on rename, so tags /
     * ratings / favourites keyed by URI survive. True on success (including
     * when the row already carries the requested name).
     */
    fun renameMedia(context: Context, uriStr: String, newBaseName: String): Boolean {
        val clean = newBaseName.trim().replace("/", "_").replace("\\", "_")
        if (clean.isEmpty()) return false
        val oldName = displayNameOf(context, uriStr) ?: return false
        val ext = oldName.substringAfterLast('.', "")
        val target = if (ext.isEmpty()) clean else "$clean.$ext"
        if (target == oldName) return true
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            return false
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target)
            put(MediaStore.MediaColumns.TITLE, clean)
        }
        return try {
            val n = context.contentResolver.update(uri, values, null, null)
            if (n <= 0) Log.w(TAG, "renameMedia: 0 rows updated for $uriStr -> $target")
            n > 0
        } catch (e: Exception) {
            Log.w(TAG, "renameMedia failed for $uriStr -> $target", e)
            false
        }
    }

    /**
     * Save a bitmap as a PNG into the shared Pictures/PYQAlbum/Screenshots
     * folder (MediaStore insert, zero-permission on API 29+ via RELATIVE_PATH;
     * IS_PENDING hides the file until the write completes). Returns the new
     * content URI as a string, or null when the save failed.
     */
    fun savePngScreenshot(context: Context, bitmap: android.graphics.Bitmap, baseName: String): String? {
        val fileName = "${baseName}_${System.currentTimeMillis()}.png"
        // Pre-Q: write into the public Pictures folder and scan it, otherwise
        // the file never becomes a MediaStore row and other apps cannot see it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return try {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PYQAlbum/Screenshots"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                java.io.FileOutputStream(file).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                MediaStoreUtils.scanFile(context, file.absolutePath)
                Uri.fromFile(file).toString()
            } catch (e: Exception) {
                Log.w(TAG, "savePngScreenshot (legacy) failed", e)
                null
            }
        }
        var outUri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/PYQAlbum/Screenshots"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
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
            MediaStoreUtils.publishImage(context, outUri)
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

    /**
     * Make a freshly inserted MediaStore image visible to OTHER apps (WeChat's
     * chat picker, file browsers, third-party galleries…): defensively clear
     * IS_PENDING, resolve the row's real path, and hand it to
     * MediaScannerConnection. The MediaStore insert alone is enough on most
     * devices, but several OEM providers keep the row unpublished until the
     * scanner runs — the exact reason WeChat could not see app-saved images on
     * some phones.
     */
    fun publishImage(context: Context, uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            Log.w(FileOperationHelper.TAG, "publishImage: IS_PENDING clear failed", e)
        }
        try {
            @Suppress("DEPRECATION")
            val path = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (!path.isNullOrBlank()) scanFile(context, path)
        } catch (e: Exception) {
            Log.w(FileOperationHelper.TAG, "publishImage: path lookup failed", e)
        }
        try {
            context.contentResolver.notifyChange(uri, null)
        } catch (e: Exception) {
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