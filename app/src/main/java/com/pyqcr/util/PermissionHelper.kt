package com.pyqcr.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Helper for checking and handling permissions.
 */
object PermissionHelper {

    private const val PREFS_NAME = "pyqcr_permissions"
    // The same flag the first-run prompt in AlbumScreen sets: Android's All
    // files access is offered exactly once per install, by whichever route
    // gets there first.
    private const val KEY_ALL_FILES_ASKED = "asked_all_files_access"

    /**
     * True once the user granted the "All files access" special permission
     * (API 30+). Below Android 11 the legacy READ/WRITE permissions already
     * cover arbitrary folders, so the helper reports true.
     */
    fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
    }

    /**
     * True while the app may still send the user to the "All files access"
     * page. The grant itself is permanent and remembered by the system, and it
     * is what keeps later deletes silent, so the page is offered exactly once
     * per install — the first-run prompt and the delete flow share this flag.
     * After it, a delete that still needs permission answers the platform's
     * own "move to trash?" prompt instead of going back to Settings.
     */
    fun shouldAskAllFiles(context: Context): Boolean {
        return !appPrefs(context).getBoolean(KEY_ALL_FILES_ASKED, false)
    }

    /** Records that the app has opened the All files access page. */
    fun markAllFilesAsked(context: Context) {
        appPrefs(context).edit().putBoolean(KEY_ALL_FILES_ASKED, true).apply()
    }

    private fun appPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Settings screen where the user can toggle "All files access" for this
     * app. Falls back to the app-details page on ROMs that do not expose the
     * per-app all-files screen directly.
     */
    fun allFilesAccessIntent(context: Context): Intent {
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        return if (perApp.resolveActivity(context.packageManager) != null) {
            perApp
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    /**
     * True when the app runs as a secondary Android user — an HONOR Parallel
     * Space / dual-app clone, a work profile, or another multi-user profile.
     *
     * A clone has its own MediaStore and its own copy of the permission state,
     * so "All files access" reported here can be inherited from the main
     * profile yet still refuse writes. Callers use this to ask for the grant
     * again instead of trusting the flag.
     */
    fun isCloneProfile(): Boolean {
        return try {
            android.os.Process.myUid() / 100000 != 0
        } catch (e: Exception) {
            false
        }
    }

    fun hasMediaPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}