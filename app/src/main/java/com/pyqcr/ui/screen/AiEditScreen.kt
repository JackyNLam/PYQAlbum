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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
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
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pyqcr.ai.AiEditService
import com.pyqcr.ai.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI Edit screen: use Wan2.7 AI to edit/swap images with reference to a target image.
 *
 * Layout mirrors AI Rating screen:
 *   - API Configuration card (API Key, Model Name, Save Config)
 *   - Custom prompt input
 *   - Source images grid (multi-select from device gallery)
 *   - Target image (single-select from device gallery)
 *   - Run AI Edit button with progress
 *   - Results display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiEditScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }
    val scope = rememberCoroutineScope()

    // API key & model (persisted with EncryptedSharedPreferences)
    var apiKey by remember { mutableStateOf(loadAiEditApiKey(context)) }
    var modelName by remember { mutableStateOf(loadAiEditModelName(context)) }
    var showApiKey by remember { mutableStateOf(false) }

    // Custom prompt
    var customPrompt by remember { mutableStateOf(loadAiEditPrompt(context)) }
    var promptSaved by remember { mutableStateOf(false) }

    // Image data
    var allImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var selectedSourceAreas by remember { mutableStateOf<Map<String, CropRect>>(emptyMap()) }
    var selectedTargetUri by remember { mutableStateOf<String?>(null) }

    // Area selection dialog state
    var areaDialogUri by remember { mutableStateOf<String?>(null) }

    // Operation state
    var isRunning by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableIntStateOf(0) }
    var totalProgress by remember { mutableIntStateOf(0) }
    var currentStatus by remember { mutableStateOf("") }
    var debugLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputPaths by remember { mutableStateOf<List<String>>(emptyList()) }

    // Load all images
    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images ->
            allImages = images
        }
    }

    // Output directory for edited images
    val outputDir = remember {
        File(context.getExternalFilesDir(null), "ai_edit").also { it.mkdirs() }
    }

    fun addDebug(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        debugLog = debugLog + "[$timestamp] $msg"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Edit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedSourceAreas.isNotEmpty() || selectedTargetUri != null) {
                        TextButton(onClick = {
                            selectedSourceAreas = emptyMap()
                            selectedTargetUri = null
                            outputPaths = emptyList()
                            debugLog = emptyList()
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
                            placeholder = { Text("wan2.7-image-pro") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = "e.g. wan2.7-image-pro, wan2.7-image",
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
                                saveAiEditApiKey(context, apiKey)
                                saveAiEditModelName(context, modelName)
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

            // ======== Custom Prompt section ========
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Edit Prompt",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = customPrompt,
                            onValueChange = { customPrompt = it; promptSaved = false },
                            label = { Text("Describe the edit") },
                            placeholder = {
                                Text("e.g. Apply the style from the reference image to the source image")
                            },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        // Save Prompt button
                        Button(
                            onClick = {
                                saveAiEditPrompt(context, customPrompt)
                                promptSaved = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Save Prompt")
                        }

                        if (promptSaved) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "✓ Prompt saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ======== Source images section ========
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Source Images (tap to set area)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${selectedSourceAreas.size} selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Toggle: show image picker
                        var showSourcePicker by remember { mutableStateOf(false) }
                        if (!showSourcePicker) {
                            Button(
                                onClick = { showSourcePicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Select Source Images")
                            }
                        }

                        if (showSourcePicker) {
                            // All images grid for selection
                            val nonTargetImages = allImages.filter { it.uri != selectedTargetUri }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                contentPadding = PaddingValues(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .background(Color.Black)
                            ) {
                                gridItems(nonTargetImages, key = { it.uri }) { image ->
                                    val isSelected = image.uri in selectedSourceAreas
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clickable {
                                                if (isSelected) {
                                                    // Remove selection
                                                    selectedSourceAreas = selectedSourceAreas - image.uri
                                                } else {
                                                    // Open area selection dialog
                                                    areaDialogUri = image.uri
                                                }
                                            }
                                    ) {
                                        ImageThumbnail(
                                            imageUri = image.uri,
                                            modifier = Modifier.fillMaxSize(),
                                            backgroundColor = Color.Black
                                        )
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .border(3.dp, Color.Green)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0x40000000)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "✓",
                                                    color = Color.White,
                                                    fontSize = MaterialTheme.typography.headlineMedium.fontSize
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { showSourcePicker = false }) {
                                Text("Done selecting sources")
                            }
                        }

                        // Show selected source thumbnails with crop indicators
                        if (selectedSourceAreas.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Selected (tap ✕ to remove):",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            val selSrc = allImages.filter { it.uri in selectedSourceAreas }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                contentPadding = PaddingValues(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                            ) {
                                gridItems(selSrc, key = { it.uri }) { image ->
                                    val rect = selectedSourceAreas[image.uri]
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clickable {
                                                selectedSourceAreas = selectedSourceAreas - image.uri
                                            }
                                    ) {
                                        ImageThumbnail(
                                            imageUri = image.uri,
                                            modifier = Modifier.fillMaxSize(),
                                            backgroundColor = Color.Black
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .border(2.dp, Color.Green)
                                        )
                                        // Crop area overlay
                                        if (rect != null) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(
                                                        start = (rect.left * 100).dp / 100f,
                                                        top = (rect.top * 100).dp / 100f,
                                                        end = ((1f - rect.right) * 100).dp / 100f,
                                                        bottom = ((1f - rect.bottom) * 100).dp / 100f
                                                    )
                                                    .border(1.5.dp, Color.White)
                                            )
                                        }
                                        // Remove button
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .background(Color.Red, RoundedCornerShape(50))
                                                .padding(2.dp)
                                        ) {
                                            Text("✕", color = Color.White, fontSize = MaterialTheme.typography.labelSmall.fontSize)
                                        }
                                        // Area label
                                        if (rect != null) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .background(Color(0xCC000000), RoundedCornerShape(2.dp))
                                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                                            ) {
                                                val pctH = ((rect.bottom - rect.top) * 100).toInt()
                                                val pctW = ((rect.right - rect.left) * 100).toInt()
                                                Text(
                                                    "${pctW}×${pctH}%",
                                                    color = Color(0xFF90EE90),
                                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ======== Target image section ========
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Target Image (Reference)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(8.dp))

                        // Toggle: show target image picker
                        var showTargetPicker by remember { mutableStateOf(false) }

                        if (selectedTargetUri == null && !showTargetPicker) {
                            Button(
                                onClick = { showTargetPicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Select Target Image")
                            }
                        }

                        if (showTargetPicker) {
                            val nonSourceImages = allImages.filter { it.uri !in selectedSourceAreas }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                contentPadding = PaddingValues(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .background(Color.Black)
                            ) {
                                gridItems(nonSourceImages, key = { it.uri }) { image ->
                                    val isTarget = image.uri == selectedTargetUri
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clickable {
                                                selectedTargetUri = image.uri
                                                showTargetPicker = false
                                            }
                                    ) {
                                        ImageThumbnail(
                                            imageUri = image.uri,
                                            modifier = Modifier.fillMaxSize(),
                                            backgroundColor = Color.Black
                                        )
                                        if (isTarget) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .border(3.dp, Color(0xFFFF9800))
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { showTargetPicker = false }) {
                                Text("Cancel")
                            }
                        }

                        // Show selected target thumbnail
                        if (selectedTargetUri != null) {
                            Spacer(Modifier.height(8.dp))
                            val targetImage = allImages.find { it.uri == selectedTargetUri }
                            if (targetImage != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                ) {
                                    ImageThumbnail(
                                        imageUri = targetImage.uri,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .border(3.dp, Color(0xFFFF9800)),
                                        backgroundColor = Color.Black
                                    )
                                    IconButton(
                                        onClick = {
                                            selectedTargetUri = null
                                            showTargetPicker = true
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(28.dp)
                                            .background(Color.Red, RoundedCornerShape(50))
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove target",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ======== Run AI Edit button ========
            item {
                Button(
                    onClick = {
                        if (apiKey.isBlank()) {
                            Toast.makeText(context, "Please enter and save API Key first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (selectedSourceAreas.isEmpty()) {
                            Toast.makeText(context, "Please select at least one source image", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (customPrompt.isBlank()) {
                            Toast.makeText(context, "Please enter an edit prompt", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        // Save config
                        saveAiEditApiKey(context, apiKey)
                        saveAiEditModelName(context, modelName)

                        isRunning = true
                        currentProgress = 0
                        totalProgress = selectedSourceAreas.size
                        debugLog = emptyList()
                        outputPaths = emptyList()
                        currentStatus = "Starting AI Edit..."

                        scope.launch {
                            val service = AiEditService()
                            // Resolve all source URIs to file paths and build cropRects map
                            val sourcePaths = mutableListOf<String>()
                            val cropRectsMap = mutableMapOf<String, CropRect>()
                            for ((uri, cropRect) in selectedSourceAreas) {
                                val path = resolveContentUriToPath(context, uri)
                                if (path != null) {
                                    sourcePaths.add(path)
                                    cropRectsMap[path] = cropRect
                                }
                            }
                            val targetPath = if (selectedTargetUri != null) {
                                resolveContentUriToPath(context, selectedTargetUri!!)
                            } else null

                            addDebug("Source images: ${sourcePaths.size}")
                            if (targetPath != null) addDebug("Target image: $targetPath")
                            addDebug("Model: $modelName")
                            addDebug("Prompt: $customPrompt")

                            try {
                                val results = service.editImages(
                                    apiKey = apiKey,
                                    modelName = modelName,
                                    sourceImagePaths = sourcePaths,
                                    cropRects = cropRectsMap,
                                    targetImagePath = targetPath,
                                    prompt = customPrompt,
                                    outputDir = outputDir,
                                    context = context,
                                    onProgress = { cur, total ->
                                        currentProgress = cur
                                        totalProgress = total
                                        currentStatus = "${cur}/${total} images done"
                                    },
                                    onDebug = { msg -> addDebug(msg) },
                                    onFatalError = { err ->
                                        addDebug("🛑 $err")
                                        currentStatus = "Error: $err"
                                    }
                                )

                                // If the primary endpoint returned no results, try the chat-compatible fallback
                                if (results.isEmpty() && !customPrompt.isNullOrBlank()) {
                                    addDebug("Primary endpoint returned no results, trying chat-compatible fallback...")
                                    currentStatus = "Trying fallback endpoint..."
                                    val fallbackResults = service.editImagesChatCompatible(
                                        apiKey = apiKey,
                                        modelName = modelName,
                                        sourceImagePaths = sourcePaths,
                                        cropRects = cropRectsMap,
                                        targetImagePath = targetPath,
                                        prompt = customPrompt,
                                        outputDir = outputDir,
                                        context = context,
                                        onProgress = { cur, total ->
                                            currentProgress = cur
                                            totalProgress = total
                                        },
                                        onDebug = { msg -> addDebug(msg) },
                                        onFatalError = { err ->
                                            addDebug("🛑 $err")
                                            currentStatus = "Error: $err"
                                        }
                                    )
                                    outputPaths = fallbackResults
                                } else {
                                    outputPaths = results
                                }

                                if (outputPaths.isNotEmpty()) {
                                    currentStatus = "✅ ${outputPaths.size} images edited and saved to gallery"
                                    addDebug("✅ Completed: ${outputPaths.size} images saved to Pictures/PYQAlbum/")
                                } else {
                                    currentStatus = "No results generated"
                                    addDebug("❌ No images were generated")
                                }

                            } catch (e: Exception) {
                                addDebug("❌ Exception: ${e::class.simpleName}: ${e.message}")
                                currentStatus = "Error: ${e.message}"
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    enabled = !isRunning && apiKey.isNotBlank() && selectedSourceAreas.isNotEmpty() && customPrompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Editing in progress...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Run AI Edit")
                    }
                }

                // Progress bar
                if (isRunning && totalProgress > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { currentProgress.toFloat() / totalProgress.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = currentStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ======== Debug log ========
            if (debugLog.isNotEmpty()) {
                item {
                    TextButton(onClick = { debugLog = emptyList() }) {
                        Text("Clear Debug Log", color = MaterialTheme.colorScheme.error)
                    }
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
                                    color = Color(0xFFCDD6F4),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                )
                            }
                        }
                    }
                }
            }

            // ======== Results section ========
            if (outputPaths.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "Edited Images (${outputPaths.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                outputPaths.forEach { path ->
                    item {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val displayName = if (path.startsWith("content://")) {
                                    Uri.parse(path).lastPathSegment ?: path
                                } else {
                                    File(path).name
                                }
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Saved at: $path",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                )
                                Spacer(Modifier.height(4.dp))
                                // Show a thumbnail preview
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 150.dp)
                                ) {
                                    ImageThumbnail(
                                        imageUri = path,
                                        modifier = Modifier.fillMaxSize(),
                                        backgroundColor = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom spacer
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // Area selection dialog
    areaDialogUri?.let { uri ->
        AreaSelectionDialog(
            imageUri = uri,
            onConfirm = { cropRect ->
                selectedSourceAreas = selectedSourceAreas + (uri to cropRect)
                areaDialogUri = null
            },
            onDismiss = {
                areaDialogUri = null
            }
        )
    }
}

// ---------- Persistence helpers (separate from AiRatingScreen's prefs) ----------

private const val AI_EDIT_PREFS = "pyqcr_ai_edit_prefs"
private const val KEY_API_KEY = "ai_edit_api_key"
private const val KEY_MODEL_NAME = "ai_edit_model_name"
private const val KEY_SAVED_PROMPT = "ai_edit_saved_prompt"
private const val DEFAULT_EDIT_MODEL = "qwen-image-edit-plus"

private fun getEditEncryptedPrefs(context: Context): android.content.SharedPreferences? {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            AI_EDIT_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) { null }
}

private fun loadAiEditApiKey(context: Context): String {
    return try {
        getEditEncryptedPrefs(context)?.getString(KEY_API_KEY, "") ?: ""
    } catch (e: Exception) { "" }
}

private fun saveAiEditApiKey(context: Context, key: String) {
    try {
        getEditEncryptedPrefs(context)?.edit()?.putString(KEY_API_KEY, key)?.apply()
    } catch (_: Exception) {}
}

private fun loadAiEditModelName(context: Context): String {
    val prefs = context.getSharedPreferences("pyqcr_ai_edit_config", Context.MODE_PRIVATE)
    return prefs.getString(KEY_MODEL_NAME, DEFAULT_EDIT_MODEL) ?: DEFAULT_EDIT_MODEL
}

private fun saveAiEditModelName(context: Context, model: String) {
    val prefs = context.getSharedPreferences("pyqcr_ai_edit_config", Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_MODEL_NAME, model).apply()
}

private fun loadAiEditPrompt(context: Context): String {
    val prefs = context.getSharedPreferences("pyqcr_ai_edit_config", Context.MODE_PRIVATE)
    return prefs.getString(KEY_SAVED_PROMPT, "") ?: ""
}

private fun saveAiEditPrompt(context: Context, prompt: String) {
    val prefs = context.getSharedPreferences("pyqcr_ai_edit_config", Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_SAVED_PROMPT, prompt).apply()
}

/**
 * Resolve a content:// URI to a local file path for processing.
 * Tries to copy the file to a temp location if direct path resolution fails.
 */
private fun resolveContentUriToPath(context: Context, uriStr: String): String? {
    return try {
        val uri = Uri.parse(uriStr)
        // Try to get the path directly
        if (uri.scheme == "file") {
            return uri.path
        }

        // Copy content:// URI to a temp file
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val tempFile = File(context.cacheDir, "ai_edit_src_${uri.hashCode()}.jpg")
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        inputStream.close()
        tempFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}