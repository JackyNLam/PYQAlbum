package com.pyqcr.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pyqcr.PyqCrApp
import com.pyqcr.data.repository.AlbumRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * WorkManager Worker that performs AI rating in the background.
 *
 * Input data keys:
 *  - "apiKey" : the DashScope API key
 *  - "modelName" : model name (e.g. qwen3.8-omni-flash)
 *  - "urisFile" : absolute path of a cache file with one content:// URI per line
 *                 (written by AiRatingWorkManager; deleted after reading).
 *
 * The URI list is passed via a file rather than the WorkRequest input data so
 * that arbitrarily large selections (>50 images) can be rated — WorkManager's
 * Data payload is capped at 10 KiB.
 *
 * NOTE: Running as a foreground worker requires the app manifest to declare
 * android:foregroundServiceType="dataSync" on WorkManager's SystemForegroundService,
 * otherwise Android 14+ (targetSdk >= 34) throws MissingForegroundServiceTypeException
 * and kills the whole process — which WorkManager then retries on the next launch,
 * causing the app to crash repeatedly at startup.
 */
class AiRatingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val app = applicationContext as PyqCrApp
    private val repository = AlbumRepository(applicationContext, app.database)
    private val resizer = ImageResizer(applicationContext)

    override suspend fun doWork(): Result {
        return try {
            runRating()
        } catch (e: CancellationException) {
            // Propagate cooperative cancellation instead of swallowing it.
            throw e
        } catch (e: Exception) {
            // Never let the worker crash the whole process; fail gracefully instead.
            Log.e(TAG, "Background AI rating failed", e)
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

    private suspend fun runRating(): Result {
        val apiKey = inputData.getString("apiKey") ?: return Result.failure()
        val modelName = inputData.getString("modelName") ?: "qwen3.8-omni-flash"
        // Read the URI list from the cache file; fall back to the legacy
        // comma-separated "imageUris" input data if no file was provided.
        val urisFile = inputData.getString("urisFile")
        val imageUris = if (urisFile != null) {
            val f = File(urisFile)
            val lines = if (f.exists()) f.readLines() else emptyList()
            f.delete()
            lines.map { it.trim() }.filter { it.isNotBlank() }
        } else {
            (inputData.getString("imageUris") ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
        val totalImages = imageUris.size

        if (imageUris.isEmpty()) return Result.success()

        // Start a fresh on-screen debug log + progress state for this session
        BackgroundDebugLog.clear()
        BackgroundProgress.reset()

        val log = { msg: String ->
            Log.d(TAG, msg)
            BackgroundDebugLog.add(msg)
        }

        log("=== Background AI Rating Started ===")
        log("Model: $modelName, Images: $totalImages")

        // Start foreground service with initial notification (best effort)
        safeSetForeground(createForegroundInfo("Preparing images...", 0, totalImages))

        // Step 1: Resize
        val resizedPaths = mutableListOf<String>()
        val resizedToOriginalUri = mutableMapOf<String, String>()
        for ((idx, uriStr) in imageUris.withIndex()) {
            BackgroundProgress.update(BackgroundProgress.PHASE_RESIZING, idx + 1, totalImages, "Resizing (${idx + 1}/$totalImages)")
            safeSetForeground(createForegroundInfo("Resizing (${idx + 1}/$totalImages)...", idx + 1, totalImages))
            log("Resizing [${idx + 1}/$totalImages]: ${uriStr.substringAfterLast('/')}")
            val resized = resizer.resizeForAi(Uri.parse(uriStr))
            if (resized != null) {
                resizedPaths.add(resized)
                resizedToOriginalUri[resized] = uriStr
            } else {
                log("  -> FAILED: $uriStr")
            }
            coroutineContext.ensureActive()
        }

        if (resizedPaths.isEmpty()) {
            log("No images could be resized")
            BackgroundProgress.update(BackgroundProgress.PHASE_DONE, 0, totalImages, "No images could be resized")
            safeSetForeground(createFinishedForegroundInfo(0, totalImages))
            return Result.success()
        }

        // Step 2: Rate
        val service = AiRatingService()
        log("Sending ${resizedPaths.size} images to DashScope API...")

        var fatalError: String? = null

        val ratingResults = service.rateImages(
            apiKey = apiKey,
            modelName = modelName,
            resizedImagePaths = resizedPaths,
            onProgress = { current, totalCount ->
                val label = "AI Rating — $current/$totalCount images"
                BackgroundProgress.update(BackgroundProgress.PHASE_RATING, current, totalCount, label)
                // Non-suspend callback: use the async variant of setForeground
                safeSetForegroundAsync(createForegroundInfo(label, current, totalCount))
                log("Progress: $current/$totalCount")
            },
            onDebug = { msg -> log(msg) },
            onFatalError = { msg ->
                fatalError = msg
                log("🛑 FATAL: $msg")
            }
        )

        log("AI returned ${ratingResults.size} results")

        // Step 3: Save results
        var savedCount = 0
        BackgroundProgress.update(BackgroundProgress.PHASE_SAVING, 0, ratingResults.size, "Saving scores...")
        for ((idx, result) in ratingResults.withIndex()) {
            val origUri = resizedToOriginalUri[result.imageUri]
                ?: resizedToOriginalUri.entries.firstOrNull { it.key.endsWith(result.imageName) }?.value

            if (origUri != null && result.score > 0f) {
                repository.updateAiScore(origUri, result.score)
                if (result.reason.isNotBlank()) {
                    repository.updateAiReason(origUri, result.reason)
                }
                savedCount++
            }
            BackgroundProgress.update(
                BackgroundProgress.PHASE_SAVING,
                idx + 1,
                ratingResults.size,
                "Saving scores... ($savedCount saved)"
            )
            coroutineContext.ensureActive()
        }

        resizer.clearCache()
        log("Done: $savedCount scores saved")
        BackgroundProgress.update(BackgroundProgress.PHASE_DONE, savedCount, totalImages, "Done: $savedCount scores saved")

        // Final notification: error if nothing was saved and we have a reason
        if (savedCount == 0) {
            val msg = fatalError ?: "No scores were saved (0/${ratingResults.size} results)."
            log("❌ Nothing saved: $msg")
            safeSetForeground(ForegroundInfo(
                /* id */ 1001,
                NotificationHelper.buildErrorNotification(applicationContext, msg),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ))
        } else {
            safeSetForeground(createFinishedForegroundInfo(savedCount, totalImages))
        }

        return Result.success()
    }

    private fun createForegroundInfo(label: String, progress: Int, total: Int): ForegroundInfo {
        val notification = NotificationHelper.buildNotification(
            applicationContext, label, progress, total
        )
        return ForegroundInfo(
            /* id */ 1001, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun createFinishedForegroundInfo(succeeded: Int, total: Int): ForegroundInfo {
        val notification = NotificationHelper.buildFinishedNotification(applicationContext, succeeded, total)
        return ForegroundInfo(
            /* id */ 1001, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        private const val TAG = "AiRatingWorker"
    }
}