package com.pyqcr.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * Remembers the SAF folder grants the user has already given, so a copy/move
 * that has to use SAF never shows the system folder picker twice for the same
 * tree.
 *
 * The system already keeps these grants (`takePersistableUriPermission` puts
 * them in [android.content.ContentResolver.getPersistedUriPermissions]); this
 * object just answers "which grant covers this destination folder?" and
 * "which folder should the picker open on so one grant covers a whole tree?".
 *
 * One prompt is issued per top-level folder (e.g. `pixiv/`), not per
 * destination, so every subfolder below it writes silently from then on.
 */
object SafGrantStore {

    private const val TAG = "SafGrantStore"

    /** Real path of a tree URI's folder, or null when the volume is unknown. */
    fun pathForTreeUri(treeUri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            if (docId.startsWith("primary:")) {
                File(
                    Environment.getExternalStorageDirectory(),
                    docId.removePrefix("primary:")
                )
            } else {
                val colon = docId.indexOf(':')
                if (colon > 0) {
                    File("/storage/${docId.substring(0, colon)}", docId.substring(colon + 1))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The remembered write grant whose tree contains [dir], preferring the
     * deepest one. Null when no grant covers the folder yet.
     */
    fun findGrant(context: Context, dir: File): Uri? {
        val target = canonical(dir)
        var best: Uri? = null
        var bestDepth = -1
        try {
            for (permission in context.contentResolver.persistedUriPermissions) {
                if (!permission.isWritePermission) continue
                val uri = permission.uri
                val treeDir = pathForTreeUri(uri) ?: continue
                val base = canonical(treeDir)
                val covers = target == base ||
                    target.startsWith(base.trimEnd(File.separatorChar).toString() + File.separator)
                if (!covers) continue
                val depth = base.count { it == File.separatorChar }
                if (depth > bestDepth) {
                    bestDepth = depth
                    best = uri
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findGrant failed for $dir", e)
        }
        return best
    }

    /**
     * Document URI of the top-level folder [dir] lives in (e.g. `primary:pixiv`).
     * Used as EXTRA_INITIAL_URI so the user confirms that whole folder once and
     * every destination below it is covered by a single grant.
     */
    fun topLevelDocumentUri(dir: File): Uri {
        val rootPath = canonical(Environment.getExternalStorageDirectory())
        val target = canonical(dir)
        val docId = when {
            target == rootPath -> "primary:"
            target.startsWith(rootPath.trimEnd(File.separatorChar).toString() + File.separator) -> {
                val rest = target.removePrefix(rootPath.trimEnd(File.separatorChar).toString() + File.separator)
                "primary:" + rest.substringBefore(File.separatorChar)
            }
            target.startsWith("/storage/") -> {
                val after = target.removePrefix("/storage/")
                val volume = after.substringBefore(File.separatorChar)
                val rest = after.substringAfter(File.separatorChar, "")
                if (volume.isBlank()) {
                    "primary:"
                } else if (volume == "emulated") {
                    // /storage/emulated/<user>/<rest> — the provider is per-user.
                    "primary:" + rest.substringAfter(File.separatorChar, "")
                        .substringBefore(File.separatorChar)
                } else {
                    "$volume:" + rest.substringBefore(File.separatorChar)
                }
            }
            else -> "primary:"
        }
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", docId
        )
    }

    private fun canonical(file: File): String = try {
        file.canonicalPath
    } catch (e: Exception) {
        file.absolutePath
    }
}