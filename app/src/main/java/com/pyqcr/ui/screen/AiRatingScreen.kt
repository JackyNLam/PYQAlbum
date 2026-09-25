package com.pyqcr.ui.screen

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ======== API Configuration section ========
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
                }
            }

            // ======== Select / Deselect controls ========
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

            // ======== Selected images square grid view ========
            if (selectedImages.isNotEmpty()) {
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
            } else {
                // Empty state
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

            // ======== Submit to AI button ========
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
                        currentStatus = "Resizing images..."
                        progress = 0
                        totalCount = selectedImages.size

                        val resizer = ImageResizer(context)
                        val service = AiRatingService()

                        // Step 1: Save config
                        saveApiKeyToPrefs(context, apiKey)
                        saveModelNameToPrefs(context, modelName)

                        // Step 2: Resize images
                        val resizedPaths = selectedImages.mapNotNull { uriString ->
                            currentStatus = "Resizing: ${uriString.substringAfterLast('/')}"
                            resizer.resizeForAi(Uri.parse(uriString))
                        }

                        if (resizedPaths.isEmpty()) {
                            currentStatus = "Failed to resize any images"
                            isRunning = false
                            return@launch
                        }

                        // Step 3: Rate images
                        currentStatus = "Sending to AI for rating..."
                        val ratingResults = service.rateImages(
                            apiKey = apiKey,
                            modelName = modelName,
                            resizedImagePaths = resizedPaths,
                            onProgress = { current, total ->
                                progress = current
                                totalCount = total
                                currentStatus = "Rating: $current/$total"
                            }
                        )

                        // Step 4: Save results to DB
                        currentStatus = "Saving results..."
                        for (result in ratingResults) {
                            val origUri = allImages.find { img ->
                                result.imageName == img.displayName ||
                                        resizedPaths.indexOfFirst { it.endsWith(result.imageName) } >= 0
                            }?.uri
                            if (origUri != null) {
                                repository.updateAiScore(origUri, result.score)
                            }
                        }

                        results = ratingResults
                        resizer.clearCache()
                        currentStatus = "Completed: ${ratingResults.size} images rated"
                        isRunning = false

                        if (ratingResults.isEmpty()) {
                            Toast.makeText(context, "No results from AI", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Rated ${ratingResults.size} images",
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
                Text(if (isRunning) "Running..." else "Submit to AI Rating")
            }

            // ======== Progress indicator ========
            if (isRunning) {
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

            // ======== Results section ========
            if (results.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Results (sorted by score)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                val sortedResults = results.sortedByDescending { it.score }
                for (result in sortedResults) {
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

            Spacer(Modifier.height(32.dp))
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