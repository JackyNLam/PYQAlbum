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
import androidx.compose.material.icons.filled.Close
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
import com.pyqcr.data.model.AiRatingResult
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AI Rating screen: configure API Key/Model, select images, run AI rating.
 *
 * This screen is now the main entry point for AI features.
 * It shows:
 *   - API config with Save Config button
 *   - Selected images in a square grid (tap to deselect)
 *   - Run AI Rating button (WorkManager background run)
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
                            placeholder = { Text("qwen3.8-omni-flash") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "e.g. qwen3.8-omni-flash, qwen-vl-plus, qwen-vl-max",
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

            // ======== Selected count ========
            item {
                Text(
                    text = "Selected: ${selectedImages.size} images",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ======== Run AI Rating button (above the image grid) ========
            item {
                var bgRunning by remember { mutableStateOf(false) }
                // Debug log from the background worker (shown on screen like the foreground flow)
                var bgDebugLog by remember { mutableStateOf<List<String>>(emptyList()) }
                // Progress state reported by the background worker (BackgroundProgress)
                var bgPhase by remember { mutableStateOf(com.pyqcr.ai.BackgroundProgress.PHASE_IDLE) }
                var bgCurrent by remember { mutableIntStateOf(0) }
                var bgTotal by remember { mutableIntStateOf(0) }
                var bgLabel by remember { mutableStateOf("") }
                // Poll background status + debug log + progress
                LaunchedEffect(Unit) {
                    while (true) {
                        bgRunning = com.pyqcr.ai.AiRatingWorkManager.isRunning(context)
                        bgDebugLog = com.pyqcr.ai.BackgroundDebugLog.lines
                        bgPhase = com.pyqcr.ai.BackgroundProgress.phase
                        bgCurrent = com.pyqcr.ai.BackgroundProgress.current
                        bgTotal = com.pyqcr.ai.BackgroundProgress.total
                        bgLabel = com.pyqcr.ai.BackgroundProgress.label
                        kotlinx.coroutines.delay(2000)
                    }
                }

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

                        // Save config
                        saveApiKeyToPrefs(context, apiKey)
                        saveModelNameToPrefs(context, modelName)

                        com.pyqcr.ai.AiRatingWorkManager.enqueue(
                            context = context,
                            apiKey = apiKey,
                            modelName = modelName,
                            imageUris = selectedImages.toList()
                        )
                        Toast.makeText(context, "✅ AI rating started in background", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !isRunning && !bgRunning && apiKey.isNotBlank() && selectedImages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Rating in progress...")
                    } else if (bgRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Background rating running...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Run AI Rating")
                    }
                }

                // Progress bar below the button while rating is in progress
                if (isRunning || bgRunning) {
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val bgProgressActive = bgRunning &&
                                bgPhase != com.pyqcr.ai.BackgroundProgress.PHASE_IDLE
                        val showCurrent = if (isRunning) progress else bgCurrent
                        val showTotal = if (isRunning) totalCount else bgTotal
                        if ((isRunning && totalCount > 0) || bgProgressActive) {
                            LinearProgressIndicator(
                                progress = { showCurrent.toFloat() / showTotal.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "$showCurrent / $showTotal images done",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${showCurrent * 100 / showTotal.coerceAtLeast(1)}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when {
                                isRunning -> currentStatus
                                bgProgressActive -> bgLabel
                                else -> "Background rating running..."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Stop button — cancels the background WorkManager run
                if (bgRunning) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            com.pyqcr.ai.AiRatingWorkManager.cancel(context)
                            Toast.makeText(context, "🛑 Stopping background AI rating...", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Stop Background Rating")
                    }
                }

                // On-screen debug log for the background run (same style as foreground)
                if (bgDebugLog.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { com.pyqcr.ai.BackgroundDebugLog.clear() }) {
                        Text("Clear Background Debug Log", color = MaterialTheme.colorScheme.error)
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1E1E2E)
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            bgDebugLog.forEach { line ->
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
private const val DEFAULT_MODEL = "qwen3.8-omni-flash"

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
    return prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
}

private fun saveModelNameToPrefs(context: Context, model: String) {
    val prefs = context.getSharedPreferences("pyqcr_ai_config", Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_MODEL_NAME, model).apply()
}