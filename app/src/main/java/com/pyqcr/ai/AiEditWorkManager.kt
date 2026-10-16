package com.pyqcr.ai

import android.content.Context
import androidx.work.*
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Helper to enqueue and observe background AI edit work.
 */
object AiEditWorkManager {

    private const val UNIQUE_WORK_NAME = "background_ai_edit"
    private val gson = Gson()

    /**
     * Schedule a background AI edit job.
     *
     * The per-image selections (crop rect / brush mask) are written to a cache
     * JSON file instead of the WorkRequest input data, because WorkManager's
     * [Data] payload is capped at 10 KiB — brush masks with thousands of
     * points would overflow it. The worker reads (and deletes) the file.
     *
     * @param apiKey DashScope API key
     * @param modelName model name (e.g. qwen-image-2.0)
     * @param prompt the edit prompt
     * @param targetUri optional second reference image URI
     * @param thirdUri optional third reference image URI
     * @param items source image URIs with their edit selections
     */
    fun enqueue(
        context: Context,
        apiKey: String,
        modelName: String,
        prompt: String,
        targetUri: String?,
        thirdUri: String?,
        items: List<AiEditJobItem>
    ) {
        val jobsDir = File(context.cacheDir, "ai_edit_jobs").apply { mkdirs() }
        val selectionsFile = File(jobsDir, "selections_${System.currentTimeMillis()}.json")
        selectionsFile.writeText(gson.toJson(items))

        val inputData = workDataOf(
            "apiKey" to apiKey,
            "modelName" to modelName,
            "prompt" to prompt,
            "targetUri" to (targetUri ?: ""),
            "thirdUri" to (thirdUri ?: ""),
            "selectionsFile" to selectionsFile.absolutePath
        )

        val workRequest = OneTimeWorkRequestBuilder<AiEditWorker>()
            .setInputData(inputData)
            .addTag("ai_edit")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
    }

    /**
     * Cancel any ongoing background AI edit.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * Check if a background edit job is currently running or enqueued.
     */
    fun isRunning(context: Context): Boolean {
        val future = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
        val workInfos = try {
            future.get(2, TimeUnit.SECONDS)
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