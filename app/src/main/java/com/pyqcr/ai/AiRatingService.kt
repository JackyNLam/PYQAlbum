package com.pyqcr.ai

import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.pyqcr.data.model.AiRatingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * AI Rating Service that calls DashScope (Alibaba Cloud Bailian) API.
 * Reference: photo_rating.py (batch scoring logic) and Javaexample.txt (SDK pattern).
 */
class AiRatingService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Rate images by sending them to DashScope API.
     * @param apiKey The user's DashScope API key
     * @param modelName The model name (e.g. qwen-vl-plus)
     * @param resizedImagePaths Paths to already-resized images (max 800px)
     * @param onProgress Callback with (current, total) progress
     * @param onDebug Callback for debug log messages (full HTTP response, errors, etc.)
     */
    suspend fun rateImages(
        apiKey: String,
        modelName: String,
        resizedImagePaths: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDebug: (String) -> Unit = {}
    ): List<AiRatingResult> = withContext(Dispatchers.IO) {
        val batchSize = 10
        val allResults = mutableListOf<AiRatingResult>()
        var succeededCount = 0
        val pendingPaths = resizedImagePaths.toMutableList()

        val prompt = """你是一位資深攝影編輯和社交媒體專家。
請對以下圖片進行「朋友圈發布適合度」評分（1-100分），評估標準：
構圖與美感（光影、色彩、主體突出）
視覺衝擊力與故事感
社交媒體吸引力（是否讓人想點讚/停留）
技術品質（清晰度、曝光、白平衡）

⚠️ 嚴格要求：請以純 JSON Array 格式回覆，不要包含任何其他文字。
注意：陣列中的每個物件必須嚴格按照圖片輸入順序排列。
你不需要返回 file 欄位，只需返回 score 和 reason：
[{ "score": 85.5, "reason": "簡短理由"}, { "score": 70.0, "reason": "簡短理由"}]"""

        var batchNum = 0
        val totalBatches = (resizedImagePaths.size + batchSize - 1) / batchSize

        while (pendingPaths.isNotEmpty()) {
            val batch = pendingPaths.take(batchSize)
            pendingPaths.removeAll(batch)
            batchNum++

            // Report progress before API call
            onProgress(succeededCount, resizedImagePaths.size)

            val content = mutableListOf<Map<String, Any>>()
            batch.forEach { path ->
                val base64 = encodeImageToBase64(path)
                if (base64 != null) {
                    content.add(mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64")
                    ))
                }
            }
            content.add(mapOf("type" to "text", "text" to prompt))

            val messages = listOf(
                mapOf(
                    "role" to "user",
                    "content" to content
                )
            )

            val requestBody = mapOf(
                "model" to modelName,
                "messages" to messages,
                "max_tokens" to 8192
            )

            val jsonBody = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val maxRetries = 3
            var retryCount = 0
            var batchDone = false

            while (!batchDone && retryCount < maxRetries) {
                retryCount++
                try {
                    onDebug("▶️ Sending batch $batchNum/$totalBatches (${batch.size} images, attempt $retryCount/$maxRetries)...")
                    val startTime = System.currentTimeMillis()
                    val response = client.newCall(request).execute()
                    val elapsedMs = System.currentTimeMillis() - startTime
                    val responseCode = response.code
                    val responseHeaders = response.headers.toString()

                    onDebug("✅ Response received in ${elapsedMs}ms — HTTP $responseCode")
                    onDebug("Response headers: $responseHeaders")

                    // Read body in a separate step to ensure we capture it even on error
                    val rawBody = response.body?.string() ?: "<NULL BODY>"
                    onDebug("RAW BODY (${rawBody.length} chars):")
                    // Print the full body — chunked to avoid overwhelming the log
                    if (rawBody.length > 4000) {
                        onDebug(rawBody.take(4000) + "\n... [${rawBody.length - 4000} more chars truncated]")
                    } else {
                        onDebug(rawBody)
                    }

                    if (!response.isSuccessful) {
                        onDebug("❌ HTTP $responseCode is not success — retrying (attempt $retryCount/$maxRetries)")
                        if (retryCount >= maxRetries) {
                            onDebug("❌ Gave up on batch $batchNum after $maxRetries retries")
                        }
                        continue
                    }

                    val jsonResponse = try {
                        JsonParser.parseString(rawBody).asJsonObject
                    } catch (e: Exception) {
                        onDebug("❌ Failed to parse JSON response: ${e.message}")
                        onDebug("Body starts with: ${rawBody.take(200)}")
                        if (retryCount >= maxRetries) {
                            onDebug("❌ Gave up on batch $batchNum after $maxRetries retries")
                        }
                        continue
                    }

                    val choices = jsonResponse.getAsJsonArray("choices")
                    if (choices == null || choices.size() == 0) {
                        onDebug("❌ 'choices' is null or empty in response — retrying (attempt $retryCount/$maxRetries)")
                        if (retryCount >= maxRetries) {
                            onDebug("❌ Gave up on batch $batchNum after $maxRetries retries")
                        }
                        continue
                    }

                    val messageObj = choices[0].asJsonObject.getAsJsonObject("message")
                    if (messageObj == null) {
                        onDebug("❌ No 'message' object in choices[0] — retrying (attempt $retryCount/$maxRetries)")
                        if (retryCount >= maxRetries) {
                            onDebug("❌ Gave up on batch $batchNum after $maxRetries retries")
                        }
                        continue
                    }

                    val messageContent = messageObj.get("content")?.asString ?: ""
                    onDebug("Content from AI (first 800 chars): ${messageContent.take(800)}")

                    // Extract JSON array from response (handles potential text wrapping)
                    val jsonArrayMatch = Regex("""\[[\s\S]*\]""").find(messageContent)
                    if (jsonArrayMatch == null) {
                        onDebug("❌ Could not find JSON array in content — retrying (attempt $retryCount/$maxRetries)")
                        if (retryCount >= maxRetries) {
                            onDebug("❌ Gave up on batch $batchNum after $maxRetries retries")
                        }
                        continue
                    }

                    val scoresArray = try {
                        JsonParser.parseString(jsonArrayMatch.value).asJsonArray
                    } catch (e: Exception) {
                        onDebug("❌ Failed to parse extracted JSON array: ${e.message}")
                        onDebug("Extracted text: ${jsonArrayMatch.value.take(300)}")
                        if (retryCount >= maxRetries) {
                            onDebug("❌ Gave up on batch $batchNum after $maxRetries retries")
                        }
                        continue
                    }

                    var batchSucceeded = 0
                    for ((idx, item) in scoresArray.withIndex()) {
                        if (idx < batch.size) {
                            val obj = item.asJsonObject
                            val score = obj.get("score")?.asFloat ?: 0f
                            val reason = obj.get("reason")?.asString ?: "N/A"
                            val originalPath = batch[idx]

                            allResults.add(
                                AiRatingResult(
                                    score = score,
                                    reason = reason,
                                    imageUri = originalPath,
                                    imageName = File(originalPath).name
                                )
                            )
                            batchSucceeded++
                        }
                    }

                    // If AI returned fewer results than batch, retry the missing ones
                    if (scoresArray.size() < batch.size) {
                        val missingPaths = batch.subList(scoresArray.size(), batch.size)
                        pendingPaths.addAll(missingPaths)
                        onDebug("⚠️ Batch $batchNum: got ${scoresArray.size()}/${batch.size} results, missing ${missingPaths.size} will be retried separately")
                    }

                    succeededCount += batchSucceeded
                    onProgress(succeededCount, resizedImagePaths.size)
                    batchDone = true
                    onDebug("✅ Batch $batchNum completed — $batchSucceeded images scored successfully")

                } catch (e: Exception) {
                    onDebug("❌ EXCEPTION for batch $batchNum (attempt $retryCount/$maxRetries): ${e::class.simpleName}: ${e.message}")
                    onDebug("Stack trace: ${e.stackTraceToString().take(1000)}")
                    e.printStackTrace()
                    if (retryCount >= maxRetries) {
                        onDebug("❌ Gave up on batch $batchNum after $maxRetries retries — last error: ${e.message}")
                    }
                    break
                }
            }

            // If batch wasn't completed successfully, log and skip it (don't put back to avoid infinite loop)
            if (!batchDone) {
                onDebug("⚠️ Batch $batchNum failed after $maxRetries attempts — SKIPPING ${batch.size} images")
            }
        }

        onProgress(resizedImagePaths.size, resizedImagePaths.size)
        allResults
    }

    private fun encodeImageToBase64(imagePath: String): String? {
        return try {
            val file = File(imagePath)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            Base64.getEncoder().encodeToString(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}