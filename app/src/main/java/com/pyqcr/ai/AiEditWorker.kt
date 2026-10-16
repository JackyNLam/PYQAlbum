package com.pyqcr.ai

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pyqcr.util.ContentUriResolver
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * WorkManager Worker that performs AI editing in the background.
 *
 * Input data keys:
 *  - "apiKey" : the DashScope API key
 *  - "modelName" : model name (e.g. qwen-image-2.0)
 *  - "prompt" : the edit prompt
 *  - "targetUri" : optional second (reference) image URI (empty when none)
 *  - "selectionsFile" : absolute path of a cache JSON file with the list of
 *    [AiEditJobItem]s (URI + crop/mask selection) — written by
 *    [AiEditWorkManager] and read (then deleted) here. Kept out of the
 *    WorkRequest data because brush masks can exceed WorkManager's 10 KiB cap.
 *
 * Progress/logging go to [AiEditBackgroundProgress] / [AiEditBackgroundDebugLog]
 * (separate from AI rating's singletons), and finished image URIs are written
 * to `cacheDir/ai_edit_results.txt` so the AI Edit screen can display them.
 *
 * NOTE: Like [AiRatingWorker], this runs as a foreground worker. The manifest
 * already declares the required foregroundServiceType (dataSync) on
 * WorkManager's SystemForegroundService.
 */
class AiEditWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val gson = Gson()

    override suspend fun doWork(): Result {
        return try {
            runEdit()
        } catch (e: CancellationException) {
            // Propagate cooperative cancellation instead of swallowing it.
            throw e
        } catch (e: Exception) {
            // Never let the worker crash the whole process; fail gracefully instead.
            Log.e(TAG, "Background AI edit failed", e)
            Result.failure()
        }
    }

    /** Update the foreground notification, ignoring failures (best effort). */
    private suspend fun safeSetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: Exception) {
            Log.w(TAG, "setForeground failed (ignored): ${e.message}")
        }
    }

    /** Async variant used from non-suspend callbacks (best effort). */
    private fun safeSetForegroundAsync(info: ForegroundInfo) {
        try {
            setForegroundAsync(info)
        } catch (e: Exception) {
            Log.w(TAG, "setForegroundAsync failed (ignored): ${e.message}")
        }
    }

    private suspend fun runEdit(): Result {
        val apiKey = inputData.getString("apiKey") ?: return Result.failure()
        val modelName = inputData.getString("modelName") ?: "qwen-image-2.0"
        val prompt = inputData.getString("prompt") ?: return Result.failure()
        val targetUri = inputData.getString("targetUri")?.takeIf { it.isNotBlank() }
        val selectionsFile = inputData.getString("selectionsFile")

        // Read the job items from the cache JSON file; delete it after reading.
        val items: List<AiEditJobItem> = if (selectionsFile != null) {
            val f = File(selectionsFile)
            val list = if (f.exists()) {
                try {
                    val type = object : TypeToken<List<AiEditJobItem>>() {}.type
                    gson.fromJson(f.readText(), type) as? List<AiEditJobItem> ?: emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse job selections", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
            f.delete()
            list
        } else {
            emptyList()
        }

        if (items.isEmpty()) return Result.success()

        // Acquire a partial wake lock so the CPU stays awake during the
        // edit — network I/O through OkHttp can stall or fail when the
        // device enters Doze mode shortly after the screen turns off.
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "pyqcr:ai_edit_worker"
        )
        wakeLock.acquire(10 * 60 * 1000L) // 10-minute timeout
        try {
            return runEditImpl(apiKey, modelName, prompt, targetUri, items)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun runEditImpl(
        apiKey: String,
        modelName: String,
        prompt: String,
        targetUri: String?,
        items: List<AiEditJobItem>
    ): Result {
        // Fresh debug log + progress state for this session
        AiEditBackgroundDebugLog.clear()
        AiEditBackgroundProgress.reset()
        // Reset the results listing shown by the AI Edit screen
        val resultsFile = File(applicationContext.cacheDir, "ai_edit_results.txt")
        resultsFile.writeText("")

        val log: (String) -> Unit = { msg ->
            Log.d(TAG, msg)
            AiEditBackgroundDebugLog.add(msg)
        }

        log("=== Background AI Edit Started ===")
        log("Model: $modelName, Images: ${items.size}")
        if (targetUri != null) log("Second image: $targetUri")

        // Ensure the notification channel exists even if the app process was
        // killed and restarted for the worker (createChannel is idempotent).
        NotificationHelper.createAiEditChannel(applicationContext)

        safeSetForeground(createForegroundInfo("Preparing images...", 0, items.size))

        // Resolve every source URI to a local file path and rebuild the
        // selections map keyed by path (the service's contract).
        val sourcePaths = mutableListOf<String>()
        val selections = mutableMapOf<String, EditSelection>()
        for ((idx, item) in items.withIndex()) {
            val path = ContentUriResolver.resolve(applicationContext, item.uri)
            if (path != null) {
                sourcePaths.add(path)
                selections[path] = item.selection
            } else {
                log("  ❌ Cannot resolve source ${item.uri.substringAfterLast('/')} — skipping")
            }
        }
        if (sourcePaths.isEmpty()) return Result.success()

        val targetPath = targetUri?.let { ContentUriResolver.resolve(applicationContext, it) }

        val baseDir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
        val outputDir = File(baseDir, "ai_edit").apply { mkdirs() }

        var fatalError: String? = null
        val service = AiEditService()

        val progressLabel: (Int, Int) -> Unit = { cur, total ->
            val label = "Editing $cur/$total images"
            AiEditBackgroundProgress.update(
                AiEditBackgroundProgress.PHASE_EDITING, cur, total, label
            )
            safeSetForegroundAsync(createForegroundInfo(label, cur, total))
        }

        log("Sending ${sourcePaths.size} sources to the API...")
        var results = service.editImages(
            apiKey = apiKey,
            modelName = modelName,
            sourceImagePaths = sourcePaths,
            selections = selections,
            targetImagePath = targetPath,
            prompt = prompt,
            outputDir = outputDir,
            context = applicationContext,
            onProgress = { cur, total ->
                progressLabel(cur, total)
                log("Progress: $cur/$total")
            },
            onDebug = { msg -> log(msg) },
            onFatalError = { msg ->
                fatalError = msg
                log("🛑 FATAL: $msg")
            }
        )

        // If the primary endpoint returned no results, try the chat-compatible fallback
        if (results.isEmpty() && prompt.isNotBlank()) {
            log("Primary endpoint returned no results, trying chat-compatible fallback...")
            results = service.editImagesChatCompatible(
                apiKey = apiKey,
                modelName = modelName,
                sourceImagePaths = sourcePaths,
                selections = selections,
                targetImagePath = targetPath,
                prompt = prompt,
                outputDir = outputDir,
                context = applicationContext,
                onProgress = { cur, total -> progressLabel(cur, total) },
                onDebug = { msg -> log(msg) },
                onFatalError = { msg ->
                    fatalError = msg
                    log("🛑 FATAL: $msg")
                }
            )
        }

        // Persist the saved URIs so the screen can show the results section
        resultsFile.writeText(results.joinToString("\n"))
        log("Done: ${results.size} images saved")

        AiEditBackgroundProgress.update(
            AiEditBackgroundProgress.PHASE_DONE,
            results.size,
            items.size,
            "Done: ${results.size} images saved"
        )

        if (results.isEmpty()) {
            val msg = fatalError ?: "No images were generated."
            log("❌ Nothing saved: $msg")
            safeSetForeground(ForegroundInfo(
                /* id */ 1002,
                NotificationHelper.buildAiEditErrorNotification(applicationContext, msg),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ))
        } else {
            safeSetForeground(createFinishedForegroundInfo(results.size, items.size))
        }

        return Result.success()
    }

    private fun createForegroundInfo(label: String, progress: Int, total: Int): ForegroundInfo {
        val notification = NotificationHelper.buildAiEditNotification(
            applicationContext, label, progress, total
        )
        return ForegroundInfo(
            /* id */ 1002, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun createFinishedForegroundInfo(succeeded: Int, total: Int): ForegroundInfo {
        val notification = NotificationHelper.buildAiEditFinishedNotification(
            applicationContext, succeeded, total
        )
        return ForegroundInfo(
            /* id */ 1002, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        private const val TAG = "AiEditWorker"
    }
}