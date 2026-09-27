package com.pyqcr.ai

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pyqcr.PyqCrApp
import com.pyqcr.data.repository.AlbumRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WorkManager Worker that performs AI rating in the background.
 *
 * Input data keys:
 *  - "apiKey" : the DashScope API key
 *  - "modelName" : model name (e.g. qwen-vl-plus)
 *  - "imageUris" : comma-separated list of content:// URIs to rate (limit 50)
 */
class AiRatingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val app = applicationContext as PyqCrApp
    private val repository = AlbumRepository(applicationContext, app.database)
    private val resizer = ImageResizer(applicationContext)

    override suspend fun doWork(): Result {
        val apiKey = inputData.getString("apiKey") ?: return Result.failure()
        val modelName = inputData.getString("modelName") ?: "qwen-vl-plus"
        val imageUris = (inputData.getString("imageUris") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val totalImages = imageUris.size

        if (imageUris.isEmpty()) return Result.success()

        // Start foreground service with initial notification
        var progress = 0
        var total = totalImages
        setForeground(createForegroundInfo("Preparing images...", 0, totalImages))

        val log = { msg: String ->
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            android.util.Log.d("AiRatingWorker", "[$ts] $msg")
        }

        log("=== Background AI Rating Started ===")
        log("Model: $modelName, Images: $totalImages")

        // Step 1: Resize
        val resizedPaths = mutableListOf<String>()
        val resizedToOriginalUri = mutableMapOf<String, String>()
        for ((idx, uriStr) in imageUris.withIndex()) {
            setForeground(createForegroundInfo("Resizing (${idx + 1}/$totalImages)...", idx, totalImages))
            log("Resizing [${idx + 1}/$totalImages]: ${uriStr.substringAfterLast('/')}")
            val resized = resizer.resizeForAi(Uri.parse(uriStr))
            if (resized != null) {
                resizedPaths.add(resized)
                resizedToOriginalUri[resized] = uriStr
            } else {
                log("  -> FAILED: $uriStr")
            }
            ensureActive()
        }

        if (resizedPaths.isEmpty()) {
            log("No images could be resized")
            setForeground(createFinishedNotification(0, totalImages))
            return Result.success()
        }

        // Step 2: Rate
        progress = 0
        val service = AiRatingService()
        log("Sending ${resizedPaths.size} images to DashScope API...")

        val ratingResults = service.rateImages(
            apiKey = apiKey,
            modelName = modelName,
            resizedImagePaths = resizedPaths,
            onProgress = { current, totalCount ->
                progress = current
                total = totalCount
                val label = "AI Rating — $current/$totalCount images"
                setForeground(createForegroundInfo(label, current, totalCount))
                log("Progress: $current/$totalCount")
                ensureActive()
            },
            onDebug = { msg -> log(msg) }
        )

        log("AI returned ${ratingResults.size} results")

        // Step 3: Save results
        var savedCount = 0
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
            ensureActive()
        }

        resizer.clearCache()
        log("Done: $savedCount scores saved")

        // Final notification
        setForeground(createFinishedNotification(savedCount, totalImages))

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

    private fun createFinishedNotification(succeeded: Int, total: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1001, NotificationHelper.buildFinishedNotification(applicationContext, succeeded, total))
    }
}