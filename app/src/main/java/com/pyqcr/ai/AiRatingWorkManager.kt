package com.pyqcr.ai

import android.content.Context
import androidx.work.*
import com.pyqcr.PyqCrApp

/**
 * Helper to enqueue and observe background AI rating work.
 */
object AiRatingWorkManager {

    private const val UNIQUE_WORK_NAME = "background_ai_rating"

    /**
     * Schedule a background AI rating job.
     *
     * @param apiKey DashScope API key
     * @param modelName model name (e.g. qwen-vl-plus)
     * @param imageUris list of content:// URIs to rate
     */
    fun enqueue(
        context: Context,
        apiKey: String,
        modelName: String,
        imageUris: List<String>
    ) {
        val urisToRate = imageUris.take(50)

        val inputData = workDataOf(
            "apiKey" to apiKey,
            "modelName" to modelName,
            "imageUris" to urisToRate.joinToString(",")
        )

        val workRequest = OneTimeWorkRequestBuilder<AiRatingWorker>()
            .setInputData(inputData)
            .addTag("ai_rating")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
    }

    /**
     * Cancel any ongoing background AI rating.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * Check if a background rating job is currently running or enqueued.
     */
    fun isRunning(context: Context): Boolean {
        val future = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
        val workInfos = try {
            future.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
        return workInfos?.any { info ->
            info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
        } ?: false
    }

    /**
     * Observe work status with a callback. Returns a Runnable to stop observing.
     */
    fun observe(
        context: Context,
        onStateChanged: (WorkInfo?) -> Unit
    ): () -> Unit {
        val liveData = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK_NAME)

        val observer = androidx.lifecycle.Observer<List<WorkInfo>> { list ->
            onStateChanged(list.firstOrNull())
        }
        liveData.observeForever(observer)

        return { liveData.removeObserver(observer) }
    }
}