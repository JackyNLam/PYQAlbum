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
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Rate images by sending them to DashScope API.
     * @param apiKey The user's DashScope API key
     * @param modelName The model name (e.g. qwen-vl-plus)
     * @param resizedImagePaths Paths to already-resized images (max 800px)
     * @param onProgress Callback with (current, total) progress
     */
    suspend fun rateImages(
        apiKey: String,
        modelName: String,
        resizedImagePaths: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<AiRatingResult> = withContext(Dispatchers.IO) {
        val batchSize = 10
        val allResults = mutableListOf<AiRatingResult>()
        var processedCount = 0
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

        while (pendingPaths.isNotEmpty()) {
            val batch = pendingPaths.take(batchSize)
            pendingPaths.removeAll(batch)

            onProgress(processedCount, resizedImagePaths.size)

            val content = mutableListOf<Map<String, Any>>()
            batch.forEach { path ->
                val base64 = encodeImageToBase64(path)
                if (base64 != null) {
                    content.add(mapOf("image" to "data:image/jpeg;base64,$base64"))
                }
            }
            content.add(mapOf("text" to prompt))

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

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    // Put back to pending for retry
                    pendingPaths.addAll(batch)
                    processedCount += batch.size
                    continue
                }

                val responseBody = response.body?.string() ?: continue
                val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
                val choices = jsonResponse.getAsJsonArray("choices")
                if (choices == null || choices.size() == 0) {
                    pendingPaths.addAll(batch)
                    processedCount += batch.size
                    continue
                }

                val messageContent = choices[0].asJsonObject
                    .getAsJsonObject("message")
                    .get("content")
                    .asString

                // Extract JSON array from response (handles potential text wrapping)
                val jsonArrayMatch = Regex("""\[[\s\S]*\]""").find(messageContent)
                if (jsonArrayMatch == null) {
                    pendingPaths.addAll(batch)
                    processedCount += batch.size
                    continue
                }

                val scoresArray = JsonParser.parseString(jsonArrayMatch.value).asJsonArray

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
                    }
                }

                // If AI returned fewer results than batch, retry the missing ones
                if (scoresArray.size() < batch.size) {
                    val missingPaths = batch.subList(scoresArray.size(), batch.size)
                    pendingPaths.addAll(missingPaths)
                }

                processedCount += batch.size

            } catch (e: Exception) {
                pendingPaths.addAll(batch)
                processedCount += batch.size
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