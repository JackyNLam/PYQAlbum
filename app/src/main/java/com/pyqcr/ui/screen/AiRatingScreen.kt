package com.pyqcr.ui.screen

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pyqcr.PyqCrApp
import com.pyqcr.ai.AiRatingService
import com.pyqcr.ai.ImageResizer
import com.pyqcr.data.model.AiRatingResult
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI Rating screen: configure API Key/Model, select images, run AI rating.
 *
 * This screen is now the main entry point for AI features.
 * It shows:
 *   - API config with Save Config button
 *   - Selected images in a square grid (tap to deselect)
 *   - Submit to AI button to run rating
 *   - Results section
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiRatingScreen(
    onBack: () -> Unit,
    initialSelectedUris: Set<String> = emptySet(),
    onUrisChanged: ((Set<String>) -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }

    // API key & model (persisted with EncryptedSharedPreferences)
    var apiKey by remember { mutableStateOf(loadApiKeyFromPrefs(context)) }
    var modelName by remember { mutableStateOf(loadModelNameFromPrefs(context)) }
    var showApiKey by remember { mutableStateOf(false) }

    // State
    var allImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var selectedImages by remember { mutableStateOf(initialSelectedUris) }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<AiRatingResult>>(emptyList()) }
    var currentStatus by remember { mutableStateOf("") }
    var debugLog by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images ->
            allImages = images
        }
    }

    // Sync initialSelectedUris changes
    LaunchedEffect(initialSelectedUris) {
        selectedImages = initialSelectedUris
    }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Rating") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Clear all selection
                    if (selectedImages.isNotEmpty()) {
                        TextButton(onClick = {
                            selectedImages = emptySet()
                            onUrisChanged?.invoke(emptySet())
                        }) {
                            Text("Clear All", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ======== API Configuration section ========
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "API Configuration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("DashScope API Key") },
                            placeholder = { Text("sk-...") },
                            visualTransformation = if (showApiKey)
                                VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Text(if (showApiKey) "🙈" else "👁️")
                                }
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = modelName,
                            onValueChange = { modelName = it },
                            label = { Text("Model Name") },
                            placeholder = { Text("qwen-vl-plus") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "e.g. qwen-vl-plus, qwen-vl-max",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(12.dp))

                        // Save Config button
                        var configSaved by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                if (apiKey.isBlank()) {
                                    Toast.makeText(context, "API Key cannot be empty", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                saveApiKeyToPrefs(context, apiKey)
                                saveModelNameToPrefs(context, modelName)
                                configSaved = true
                                Toast.makeText(context, "Configuration saved", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Save Config")
                        }

                        if (configSaved) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "✓ Configuration saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ======== Select / Deselect controls ========
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Selected: ${selectedImages.size} images",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = {
                        val newSet = if (selectedImages.size == allImages.size)
                            emptySet()
                        else
                            allImages.map { it.uri }.toSet()
                        selectedImages = newSet
                        onUrisChanged?.invoke(newSet)
                    }) {
                        Text(if (selectedImages.size == allImages.size) "Deselect All" else "Select All")
                    }
                }
            }

            // ======== Selected images square grid view ========
            if (selectedImages.isNotEmpty()) {
                item {
                    val selImgs = allImages.filter { it.uri in selectedImages }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 600.dp)
                            .background(Color.Black)
                    ) {
                        gridItems(selImgs, key = { it.uri }) { image ->
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clickable {
                                        selectedImages = selectedImages - image.uri
                                        onUrisChanged?.invoke(selectedImages)
                                    }
                            ) {
                                ImageThumbnail(
                                    imageUri = image.uri,
                                    modifier = Modifier.fillMaxSize(),
                                    backgroundColor = Color.Black
                                )
                                // Green border
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .border(3.dp, Color.Green)
                                )
                                // ✕ overlay
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0x40000000)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✕", color = Color.White, fontSize = MaterialTheme.typography.headlineLarge.fontSize)
                                }
                            }
                        }
                    }
                }
            } else {
                // Empty state
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No images selected yet.",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Go to Album, long-press an image, and choose\nSelect for AI Ranking to add images here.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ======== Submit to AI button ========
            item {
                Button(
                    onClick = {
                        if (apiKey.isBlank()) {
                            Toast.makeText(context, "Please enter and save API Key first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (selectedImages.isEmpty()) {
                            Toast.makeText(context, "Please select images", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        scope.launch {
                            isRunning = true
                            results = emptyList()
                            debugLog = emptyList()
                            currentStatus = "Resizing images..."
                            progress = 0
                            totalCount = selectedImages.size

                            fun log(msg: String) {
                                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                debugLog = debugLog + "[$ts] $msg"
                            }

                            log("=== AI Rating Session Started ===")
                            log("Model: $modelName")
                            log("Selected ${selectedImages.size} images")

                            val resizer = ImageResizer(context)
                            val service = AiRatingService()

                            // Step 1: Save config
                            saveApiKeyToPrefs(context, apiKey)
                            saveModelNameToPrefs(context, modelName)
                            log("API config saved")

                            // Step 2: Resize images
                            val totalSelected = selectedImages.size
                            val resizedPaths = mutableListOf<String>()
                            for ((idx, uriString) in selectedImages.withIndex()) {
                                currentStatus = "Resizing (${idx + 1}/$totalSelected): ${uriString.substringAfterLast('/')}"
                                log("Resizing [${idx + 1}/$totalSelected]: ${uriString.substringAfterLast('/')}")
                                val resized = resizer.resizeForAi(Uri.parse(uriString))
                                if (resized != null) {
                                    resizedPaths.add(resized)
                                    log("  -> OK: $resized (${File(resized).length()} bytes)")
                                } else {
                                    log("  -> FAILED: $uriString")
                                }
                                progress = idx + 1
                            }

                            if (resizedPaths.isEmpty()) {
                                currentStatus = "❌ Failed to resize any images"
                                log("❌ FAILED: No images could be resized")
                                isRunning = false
                                Toast.makeText(context, "Failed to resize any images. Check permissions.", Toast.LENGTH_LONG).show()
                                return@launch
                            }

                            log("Resize complete: ${resizedPaths.size}/$totalSelected resized OK")

                            // Step 3: Rate images
                            progress = 0
                            currentStatus = "Sending ${resizedPaths.size} images to AI for rating..."
                            log("Sending ${resizedPaths.size} images to DashScope API...")
                            log("API endpoint: https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions")

                            val ratingResults = service.rateImages(
                                apiKey = apiKey,
                                modelName = modelName,
                                resizedImagePaths = resizedPaths,
                                onProgress = { current, total ->
                                    progress = current
                                    totalCount = total
                                    val passNum = (current / 10) + 1
                                    val totalPasses = (total + 9) / 10
                                    currentStatus = "AI Rating — $current/$total images rated ($passNum/$totalPasses passes)"
                                    log("Progress: $current/$total successes ($passNum/$totalPasses passes)")
                                },
                                onDebug = { msg -> log(msg) }
                            )

                            log("AI returned ${ratingResults.size} results")

                            // Step 4: Save results to DB
                            if (ratingResults.isNotEmpty()) {
                                currentStatus = "Saving ${ratingResults.size} scores to database..."
                                log("Saving results to database...")
                                var savedCount = 0
                                for ((idx, result) in ratingResults.withIndex()) {
                                    log("  Result #${idx + 1}: name=${result.imageName}, score=${result.score}, reason=${result.reason}")
                                    val origUri = allImages.find { img ->
                                        result.imageName == img.displayName ||
                                                resizedPaths.indexOfFirst { it.endsWith(result.imageName) } >= 0
                                    }?.uri
                                    if (origUri != null) {
                                        repository.updateAiScore(origUri, result.score)
                                        repository.updateAiReason(origUri, result.reason)
                                        savedCount++
                                        log("  -> Saved to DB: $origUri (score=${result.score}, reason=${result.reason})")
                                    } else {
                                        log("  -> WARN: Could not find original image for ${result.imageName}")
                                    }
                                }
                                results = ratingResults
                                resizer.clearCache()
                                currentStatus = "✅ Completed! ${ratingResults.size} images rated, $savedCount scores saved."
                                log("✅ DONE: $savedCount scores saved")
                            } else {
                                currentStatus = "❌ AI returned no results. Check your API key and try again."
                                log("❌ AI returned 0 results — API key or network issue?")
                            }
                            isRunning = false

                            if (ratingResults.isEmpty()) {
                                Toast.makeText(context, "❌ No results from AI. Check API key and network.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "✅ Rated ${ratingResults.size} images",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = !isRunning && apiKey.isNotBlank() && selectedImages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRunning) currentStatus.take(50) + if (currentStatus.length > 50) "…" else "" else "Submit to AI Rating")
                }
            }

            // ======== Progress indicator ========
            if (isRunning) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (totalCount > 0) {
                            LinearProgressIndicator(
                                progress = { progress.toFloat() / totalCount.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = currentStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ======== Debug Log section (always visible during/after run) ========
            if (debugLog.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(onClick = { debugLog = emptyList() }) {
                        Text("Clear Debug Log", color = MaterialTheme.colorScheme.error)
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1E1E2E)
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            debugLog.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFCDD6F4),    // light text on dark bg
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                )
                            }
                        }
                    }
                }
            }

            // ======== Results section ========
            if (results.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "Results (sorted by score)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                val sortedResults = results.sortedByDescending { it.score }
                items(sortedResults, key = { it.imageUri + it.score }) { result ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = result.imageName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = String.format("%.1f", result.score),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = result.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Bottom spacer
            item {
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ---------- Persistence helpers ----------

private const val PREFS_NAME = "pyqcr_secure_prefs"
private const val KEY_API_KEY = "dashscope_api_key"
private const val KEY_MODEL_NAME = "dashscope_model_name"

private fun getEncryptedPrefs(context: Context): android.content.SharedPreferences? {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }
}

private fun loadApiKeyFromPrefs(context: Context): String {
    return try {
        getEncryptedPrefs(context)?.getString(KEY_API_KEY, "") ?: ""
    } catch (e: Exception) { "" }
}

private fun saveApiKeyToPrefs(context: Context, key: String) {
    try {
        getEncryptedPrefs(context)?.edit()?.putString(KEY_API_KEY, key)?.apply()
    } catch (_: Exception) {}
}

private fun loadModelNameFromPrefs(context: Context): String {
    val prefs = context.getSharedPreferences("pyqcr_ai_config", Context.MODE_PRIVATE)
    return prefs.getString(KEY_MODEL_NAME, "qwen-vl-plus") ?: "qwen-vl-plus"
}

private fun saveModelNameToPrefs(context: Context, model: String) {
    val prefs = context.getSharedPreferences("pyqcr_ai_config", Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_MODEL_NAME, model).apply()
}