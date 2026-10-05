package com.pyqcr.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AI Edit Service that calls the Wan2.7 Image Edit API via DashScope.
 *
 * This service sends source image(s) + a reference target image + a custom prompt
 * to the Wan2.7-image-pro multimodal model, receives edited images back,
 * and saves them to local storage.
 */
class AiEditService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Edit images using the Wan2.7 Image Edit API.
     *
     * @param apiKey DashScope API key
     * @param modelName Model name (e.g. wan2.7-image-pro, wan2.7-image)
     * @param sourceImagePaths Local file paths of source images to edit
     * @param targetImagePath Local file path of the reference/target image
     * @param prompt Custom prompt describing the edit
     * @param outputDir Directory to save output images
     * @param onProgress Callback with (current, total)
     * @param onDebug Callback for debug log messages
     * @param onFatalError Callback for fatal errors
     * @return List of saved output file paths
     */
    suspend fun editImages(
        apiKey: String,
        modelName: String,
        sourceImagePaths: List<String>,
        targetImagePath: String?,
        prompt: String,
        outputDir: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDebug: (String) -> Unit = {},
        onFatalError: (String) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val outputPaths = mutableListOf<String>()
        val total = sourceImagePaths.size
        var completed = 0

        for ((index, sourcePath) in sourceImagePaths.withIndex()) {
            coroutineContext.ensureActive()

            onDebug("▶️ Editing image ${index + 1}/$total: ${File(sourcePath).name}")

            val sourceBase64 = encodeImageToBase64(sourcePath)
            if (sourceBase64 == null) {
                onDebug("❌ Failed to encode source image: $sourcePath")
                completed++
                onProgress(completed, total)
                continue
            }

            val targetBase64 = if (targetImagePath != null) {
                encodeImageToBase64(targetImagePath)
            } else null

            // Build content array: source image, optional target reference, prompt
            val content = mutableListOf<Map<String, Any>>()
            content.add(mapOf(
                "image" to "data:image/jpeg;base64,$sourceBase64"
            ))
            if (targetBase64 != null) {
                content.add(mapOf(
                    "image" to "data:image/jpeg;base64,$targetBase64"
                ))
            }
            content.add(mapOf("text" to prompt))

            val requestBody = mapOf(
                "model" to modelName,
                "input" to mapOf(
                    "messages" to listOf(
                        mapOf(
                            "role" to "user",
                            "content" to content
                        )
                    )
                ),
                "parameters" to mapOf(
                    "size" to "2K",
                    "watermark" to false,
                    "n" to 1
                )
            )

            val jsonBody = gson.toJson(requestBody)
            onDebug("Request body: ${jsonBody.take(500)}...")

            val request = Request.Builder()
                .url("https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
                .header("Authorization", "Bearer $apiKey")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val maxRetries = 3
            var retryCount = 0
            var imageSaved = false

            while (!imageSaved && retryCount < maxRetries) {
                coroutineContext.ensureActive()
                retryCount++
                try {
                    onDebug("  Sending API request (attempt $retryCount/$maxRetries)...")
                    val response = executeCancellable(request)
                    val responseCode = response.code
                    val rawBody = response.body?.string() ?: "<NULL BODY>"

                    onDebug("  Response HTTP $responseCode, body length: ${rawBody.length} chars")
                    if (rawBody.length > 2000) {
                        onDebug(rawBody.take(2000) + "\n... [${rawBody.length - 2000} more chars truncated]")
                    } else {
                        onDebug(rawBody)
                    }

                    if (!response.isSuccessful) {
                        val fatal = classifyApiError(responseCode, rawBody)
                        if (fatal != null) {
                            onDebug("🛑 FATAL API error: $fatal")
                            onFatalError(fatal)
                            return@withContext outputPaths
                        }
                        onDebug("❌ HTTP $responseCode, retrying...")
                        continue
                    }

                    // Parse the response to extract image URLs
                    val imageUrl = extractImageUrl(rawBody)
                    if (imageUrl == null) {
                        onDebug("❌ No image URL found in response — retrying (attempt $retryCount/$maxRetries)")
                        continue
                    }

                    onDebug("  Image URL received, downloading...")

                    // Download and save the image
                    val outputFileName = "ai_edit_${System.currentTimeMillis()}_${index}.jpg"
                    val outputFile = File(outputDir, outputFileName)
                    downloadImage(imageUrl, outputFile)
                    outputPaths.add(outputFile.absolutePath)
                    imageSaved = true
                    onDebug("✅ Saved: ${outputFile.absolutePath}")

                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onDebug("❌ Exception (attempt $retryCount/$maxRetries): ${e::class.simpleName}: ${e.message}")
                    if (retryCount >= maxRetries) {
                        onDebug("❌ Gave up on image ${index + 1} after $maxRetries attempts")
                    }
                }
            }

            if (!imageSaved) {
                onDebug("⚠️ Failed to edit image ${index + 1} — skipping")
            }

            completed++
            onProgress(completed, total)
        }

        outputPaths
    }

    /**
     * Try the chat-completions compatible endpoint as a fallback.
     * Some models support both endpoints.
     */
    suspend fun editImagesChatCompatible(
        apiKey: String,
        modelName: String,
        sourceImagePaths: List<String>,
        targetImagePath: String?,
        prompt: String,
        outputDir: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDebug: (String) -> Unit = {},
        onFatalError: (String) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val outputPaths = mutableListOf<String>()
        val total = sourceImagePaths.size
        var completed = 0

        for ((index, sourcePath) in sourceImagePaths.withIndex()) {
            coroutineContext.ensureActive()
            onDebug("▶️ Editing image ${index + 1}/$total (chat-compatible endpoint): ${File(sourcePath).name}")

            val sourceBase64 = encodeImageToBase64(sourcePath)
            if (sourceBase64 == null) {
                completed++
                onProgress(completed, total)
                continue
            }

            val content = mutableListOf<Map<String, Any>>()
            content.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:image/jpeg;base64,$sourceBase64")
            ))
            if (targetImagePath != null) {
                val tb64 = encodeImageToBase64(targetImagePath)
                if (tb64 != null) {
                    content.add(mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:image/jpeg;base64,$tb64")
                    ))
                }
            }
            content.add(mapOf("type" to "text", "text" to prompt))

            val messages = listOf(
                mapOf("role" to "user", "content" to content)
            )

            val requestBody = mapOf(
                "model" to modelName,
                "messages" to messages,
                "max_tokens" to 4096
            )

            val request = Request.Builder()
                .url("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(gson.toJson(requestBody).toRequestBody(jsonMediaType))
                .build()

            try {
                val response = executeCancellable(request)
                val rawBody = response.body?.string() ?: ""
                onDebug("  Response HTTP ${response.code}")

                if (response.isSuccessful) {
                    val jsonResponse = JsonParser.parseString(rawBody).asJsonObject
                    val choices = jsonResponse.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val messageContent = choices[0].asJsonObject
                            .getAsJsonObject("message")
                            ?.get("content")?.asString ?: ""
                        onDebug("  Content: ${messageContent.take(300)}")

                        // Try to extract an image URL from the response content
                        val imgUrlMatch = Regex("""(https?://[^\s"']+\.(?:jpg|jpeg|png|webp))""")
                            .find(messageContent)
                        if (imgUrlMatch != null) {
                            val imageUrl = imgUrlMatch.value
                            val outputFileName = "ai_edit_${System.currentTimeMillis()}_${index}.jpg"
                            val outputFile = File(outputDir, outputFileName)
                            downloadImage(imageUrl, outputFile)
                            outputPaths.add(outputFile.absolutePath)
                            onDebug("✅ Saved from URL: ${outputFile.absolutePath}")
                        } else {
                            onDebug("❌ No image URL in chat-compatible response")
                        }
                    }
                } else {
                    onDebug("❌ Chat-compatible endpoint returned HTTP ${response.code}")
                }
            } catch (e: Exception) {
                onDebug("❌ Chat-compatible fallback failed: ${e.message}")
            }

            completed++
            onProgress(completed, total)
        }

        outputPaths
    }

    /**
     * Extract the first image URL from the Wan2.7 API response JSON.
     *
     * Response format expected:
     * {
     *   "output": {
     *     "choices": [
     *       {
     *         "message": {
     *           "content": [
     *             {"type": "image", "image": {"url": "https://..."}}
     *           ]
     *         }
     *       }
     *     ]
     *   }
     * }
     */
    private fun extractImageUrl(rawBody: String): String? {
        return try {
            val root = JsonParser.parseString(rawBody).asJsonObject
            // Try "output.choices[].message.content[].image.url" format
            val output = root.getAsJsonObject("output") ?: return null
            val choices = output.getAsJsonArray("choices") ?: return null
            if (choices.size() == 0) return null
            val message = choices[0].asJsonObject
                .getAsJsonObject("message") ?: return null
            val content = message.getAsJsonArray("content") ?: return null
            for (item in content) {
                val obj = item.asJsonObject
                val type = obj.get("type")?.asString
                if (type == "image") {
                    val imageObj = obj.getAsJsonObject("image")
                    val url = imageObj?.get("url")?.asString
                    if (url != null) return url

                    // Also try direct "url" field
                    val directUrl = obj.get("url")?.asString
                    if (directUrl != null) return directUrl
                }
            }
            // Fallback: try "result_url" or direct url field in output
            val resultUrl = output.get("result_url")?.asString
            if (resultUrl != null) return resultUrl

            // Fallback: check output for "image_url"
            val outputImageUrl = output.get("image_url")?.asString
            if (outputImageUrl != null) return outputImageUrl

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadImage(imageUrl: String, outputFile: File) {
        val connection = URL(imageUrl).openConnection()
        connection.setRequestProperty("User-Agent", "PYQAlbum/1.0")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.getInputStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    private fun classifyApiError(code: Int, body: String): String? {
        val b = body.lowercase()
        return when {
            b.contains("allocationquota") || b.contains("free quota") || b.contains("free tier") ->
                "API quota exhausted — your DashScope free tier is used up."
            code == 401 || b.contains("invalid_api_key") || b.contains("incorrect api key") ->
                "Invalid API key."
            code == 403 && b.contains("access_denied") ->
                "Access denied — this model isn't enabled for your account/key."
            b.contains("arrearage") || b.contains("insufficient") || b.contains("overdue") ->
                "Account arrears/insufficient balance."
            else -> null
        }
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

    companion object {
        private const val TAG = "AiEditService"
    }
}