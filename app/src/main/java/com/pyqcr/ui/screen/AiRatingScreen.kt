package com.pyqcr.ui.screen

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pyqcr.PyqCrApp
import com.pyqcr.ai.AiRatingService
import com.pyqcr.ai.ImageResizer
import com.pyqcr.data.model.AiRatingResult
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.RatingBar
import kotlinx.coroutines.launch
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AI Rating screen: configure API Key/Model, select images, run AI rating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiRatingScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }

    // API key & model (persisted with EncryptedSharedPreferences)
    var apiKey by remember { mutableStateOf(loadApiKey(context)) }
    var modelName by remember { mutableStateOf("qwen-vl-plus") }
    var showApiKey by remember { mutableStateOf(false) }

    // State
    var allImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var selectedImages by remember { mutableStateOf<Set<String>>(emptySet()) }
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

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Rating") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            // API Configuration section
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
                    }
                }
            }

            // Select mode / select all
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Images (${selectedImages.size} selected)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    TextButton(onClick = {
                        selectedImages = if (selectedImages.size == allImages.size)
                            emptySet()
                        else
                            allImages.map { it.uri }.toSet()
                    }) {
                        Text(if (selectedImages.size == allImages.size) "Deselect All" else "Select All")
                    }
                }
            }

            // Image selection grid
            val selectedUris = selectedImages
            items(allImages) { image ->
                val isSelected = image.uri in selectedUris
                ElevatedCard(
                    onClick = {
                        selectedImages = if (isSelected)
                            selectedImages - image.uri
                        else
                            selectedImages + image.uri
                    },
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = image.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isSelected) {
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }

            // Run button
            item {
                Button(
                    onClick = {
                        if (apiKey.isBlank()) {
                            Toast.makeText(context, "Please enter API Key", Toast.LENGTH_SHORT).show()
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

                            // Step 1: Resize images
                            val resizedPaths = selectedImages.mapNotNull { uriString ->
                                currentStatus = "Resizing: ${uriString.substringAfterLast('/')}"
                                resizer.resizeForAi(Uri.parse(uriString))
                            }

                            if (resizedPaths.isEmpty()) {
                                currentStatus = "Failed to resize any images"
                                isRunning = false
                                return@launch
                            }

                            // Step 2: Save API key
                            saveApiKey(context, apiKey)

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
                                // Find original URI for this result
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
                    Text(if (isRunning) "Running..." else "Run AI Rating")
                }
            }

            // Progress indicator
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

            // Results section
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
                items(sortedResults) { result ->
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
        }
    }
}

private fun loadApiKey(context: Context): String {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            "pyqcr_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.getString("dashscope_api_key", "") ?: ""
    } catch (e: Exception) {
        ""
    }
}

private fun saveApiKey(context: Context, key: String) {
    try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            "pyqcr_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.edit().putString("dashscope_api_key", key).apply()
    } catch (_: Exception) {}
}