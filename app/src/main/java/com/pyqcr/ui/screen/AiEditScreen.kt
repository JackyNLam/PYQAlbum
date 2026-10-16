package com.pyqcr.ui.screen

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pyqcr.ai.AiEditBackgroundDebugLog
import com.pyqcr.ai.AiEditBackgroundProgress
import com.pyqcr.ai.AiEditJobItem
import com.pyqcr.ai.AiEditService
import com.pyqcr.ai.AiEditWorkManager
import com.pyqcr.ai.CropRect
import com.pyqcr.ai.EditSelection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI Edit screen: use Wan2.7 AI to edit/swap images with reference to a
 * second image.
 *
 * Layout mirrors AI Rating screen:
 *   - API Configuration card (API Key, Model Name, saved-prompt dropdown,
 *     prompt input, Save Config)
 *   - First image grid (multi-select from device gallery, per-image
 *     rectangle or brush selection)
 *   - Second image (single-select from device gallery)
 *   - Run AI Edit in Background button (WorkManager) with progress and a
 *     Stop button while it runs
 *   - Results display (polled from the worker's results file)
 *
 * First/second images marked in the album's batch select menu are imported
 * once when the screen opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiEditScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }

    // API key & model (persisted with EncryptedSharedPreferences)
    var apiKey by remember { mutableStateOf(loadAiEditApiKey(context)) }
    var modelName by remember { mutableStateOf(loadAiEditModelName(context)) }
    var showApiKey by remember { mutableStateOf(false) }

    // Custom prompt + saved prompt library (max 10)
    var customPrompt by remember { mutableStateOf(loadAiEditPrompt(context)) }
    var savedPrompts by remember { mutableStateOf(loadAiEditSavedPrompts(context)) }
    var selectedSavedPromptIdx by remember { mutableIntStateOf(-1) }
    var promptMenuExpanded by remember { mutableStateOf(false) }

    // Image data
    var allImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var selectedSourceAreas by remember { mutableStateOf<Map<String, EditSelection>>(emptyMap()) }
    var selectedTargetUri by remember { mutableStateOf<String?>(null) }

    // Area selection dialog state
    var areaDialogUri by remember { mutableStateOf<String?>(null) }

    // Background job state (WorkManager) — mirrors AiRatingScreen's polling
    var bgRunning by remember { mutableStateOf(false) }
    var bgPhase by remember { mutableStateOf(AiEditBackgroundProgress.PHASE_IDLE) }
    var bgCurrent by remember { mutableIntStateOf(0) }
    var bgTotal by remember { mutableIntStateOf(0) }
    var bgLabel by remember { mutableStateOf("") }
    var bgDebugLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputPaths by remember { mutableStateOf<List<String>>(emptyList()) }

    // Load all images
    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images ->
            allImages = images
        }
    }

    // One-shot import of source/target images marked in the album's batch
    // select menu (pyqcr_ai_edit_select prefs). Sources start with a
    // full-image selection; tap one to refine the area/brush.
    LaunchedEffect(Unit) {
        val sources = loadAiEditSourceUris(context)
        if (sources.isNotEmpty()) {
            selectedSourceAreas = sources.associateWith {
                EditSelection(cropRect = CropRect(0f, 0f, 1f, 1f))
            }
        }
        val target = loadAiEditTargetUris(context).firstOrNull()
        if (target != null) {
            selectedTargetUri = target
        }
    }

    // Poll the background job status / progress / log / saved results.
    LaunchedEffect(Unit) {
        while (true) {
            bgRunning = AiEditWorkManager.isRunning(context)
            bgDebugLog = AiEditBackgroundDebugLog.lines
            bgPhase = AiEditBackgroundProgress.phase
            bgCurrent = AiEditBackgroundProgress.current
            bgTotal = AiEditBackgroundProgress.total
            bgLabel = AiEditBackgroundProgress.label
            outputPaths = try {
                File(context.cacheDir, "ai_edit_results.txt")
                    .readLines()
                    .filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
            kotlinx.coroutines.delay(2000)
        }
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
                            bgDebugLog = emptyList()
                            saveAiEditSourceUris(context, emptySet())
                            saveAiEditTargetUris(context, emptySet())
                            clearAiEditSelections(context)
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
                            placeholder = { Text("qwen-image-2.0") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = "e.g. qwen-image-2.0-pro, qwen-image-2.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(12.dp))

                        // Saved prompt selector — dropdown above the prompt box.
                        // Selecting a saved prompt loads it into the box so the
                        // user can overwrite that slot with "Save Prompt".
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = if (selectedSavedPromptIdx in savedPrompts.indices)
                                    "Prompt ${selectedSavedPromptIdx + 1} · " +
                                        savedPrompts[selectedSavedPromptIdx].replace("\n", " ")
                                else "",
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                label = { Text("Saved Prompt") },
                                placeholder = { Text("Select Prompt 1-10 (${savedPrompts.size}/10 saved)") },
                                trailingIcon = {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Open saved prompts")
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            // ReadOnly text fields consume taps for cursor
                            // placement, so the field itself cannot reliably
                            // open the menu — intercept the tap with a
                            // full-size overlay instead.
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable { promptMenuExpanded = true }
                            )
                        }
                        DropdownMenu(
                            expanded = promptMenuExpanded,
                            onDismissRequest = { promptMenuExpanded = false }
                        ) {
                            if (savedPrompts.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No saved prompts yet") },
                                    onClick = { promptMenuExpanded = false }
                                )
                            }
                            savedPrompts.forEachIndexed { index, prompt ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = "Prompt ${index + 1}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = prompt.replace("\n", " "),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    },
                                    leadingIcon = if (selectedSavedPromptIdx == index) {
                                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                                    } else null,
                                    onClick = {
                                        customPrompt = prompt
                                        selectedSavedPromptIdx = index
                                        promptMenuExpanded = false
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            savedPrompts = savedPrompts.filterIndexed { i, _ -> i != index }
                                            saveAiEditSavedPrompts(context, savedPrompts)
                                            if (selectedSavedPromptIdx == index) selectedSavedPromptIdx = -1
                                            else if (selectedSavedPromptIdx > index) selectedSavedPromptIdx--
                                        }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete saved prompt",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Prompt box — moved above the Save Config button
                        OutlinedTextField(
                            value = customPrompt,
                            onValueChange = { customPrompt = it },
                            label = { Text("Describe the edit") },
                            placeholder = {
                                Text("e.g. Apply the style from the second image to the first image")
                            },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Save/update the current prompt in the saved list (max 10)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val text = customPrompt.trim()
                                    if (text.isEmpty()) {
                                        Toast.makeText(context, "Prompt cannot be empty", Toast.LENGTH_SHORT).show()
                                        return@TextButton
                                    }
                                    if (selectedSavedPromptIdx in savedPrompts.indices) {
                                        // Overwrite the selected slot
                                        savedPrompts = savedPrompts.toMutableList().also { it[selectedSavedPromptIdx] = text }
                                        saveAiEditSavedPrompts(context, savedPrompts)
                                        Toast.makeText(context, "Prompt ${selectedSavedPromptIdx + 1} overwritten", Toast.LENGTH_SHORT).show()
                                    } else if (savedPrompts.contains(text)) {
                                        selectedSavedPromptIdx = savedPrompts.indexOf(text)
                                        Toast.makeText(context, "Already saved as Prompt ${selectedSavedPromptIdx + 1}", Toast.LENGTH_SHORT).show()
                                    } else if (savedPrompts.size >= MAX_SAVED_PROMPTS) {
                                        Toast.makeText(context, "All 10 slots full — select a slot to overwrite", Toast.LENGTH_SHORT).show()
                                    } else {
                                        savedPrompts = savedPrompts + text
                                        selectedSavedPromptIdx = savedPrompts.lastIndex
                                        saveAiEditSavedPrompts(context, savedPrompts)
                                        Toast.makeText(context, "Saved as Prompt ${selectedSavedPromptIdx + 1}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Save Prompt")
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${savedPrompts.size}/10 saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // Save Config button (saves API key, model name, AND prompt)
                        var configSaved by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                if (apiKey.isBlank()) {
                                    Toast.makeText(context, "API Key cannot be empty", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                saveAiEditApiKey(context, apiKey)
                                saveAiEditModelName(context, modelName)
                                saveAiEditPrompt(context, customPrompt)
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

            // ======== First image section ========
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "First Image (tap to set area)",
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
                                Text("Select First Image")
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
                                                // Open the area selection dialog to set or refine the area
                                                areaDialogUri = image.uri
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
                                Text("Done selecting first images")
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
                                    val sel = selectedSourceAreas[image.uri]
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clickable {
                                                // Refine the selection (re-open area dialog)
                                                areaDialogUri = image.uri
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
                                        // Crop area overlay — correctly maps normalized
                                        // coords through ContentScale.Fit letterboxing
                                        if (sel?.hasCrop == true) {
                                            ThumbnailCropOverlay(
                                                imageUri = image.uri,
                                                cropRect = sel.cropRect!!
                                            )
                                        }
                                        // Brush mask badge
                                        if (sel?.hasMask == true) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .background(Color(0xCC000000), RoundedCornerShape(2.dp))
                                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    "🖌 brush",
                                                    color = Color(0xFF90EE90),
                                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                                )
                                            }
                                        }
                                        // Remove button — clickable, propagates before tile
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .background(Color.Red, RoundedCornerShape(50))
                                                .padding(2.dp)
                                                .clickable {
                                                    selectedSourceAreas = selectedSourceAreas - image.uri
                                                    saveAiEditSourceUris(context, selectedSourceAreas.keys)
                                                }
                                        ) {
                                            Text("✕", color = Color.White, fontSize = MaterialTheme.typography.labelSmall.fontSize)
                                        }
                                        // Rect area label
                                        if (sel?.hasCrop == true) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .background(Color(0xCC000000), RoundedCornerShape(2.dp))
                                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                                            ) {
                                                val cr = sel.cropRect!!
                                                val pctH = ((cr.bottom - cr.top) * 100).toInt()
                                                val pctW = ((cr.right - cr.left) * 100).toInt()
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

            // ======== Second image section ========
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Second Image (Reference)",
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
                                Text("Select Second Image")
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
                                                saveAiEditTargetUris(context, setOf(image.uri))
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
                                            saveAiEditTargetUris(context, emptySet())
                                            showTargetPicker = true
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(28.dp)
                                            .background(Color.Red, RoundedCornerShape(50))
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove second image",
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
                            Toast.makeText(context, "Please select at least one first image", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (customPrompt.isBlank()) {
                            Toast.makeText(context, "Please enter an edit prompt", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        // Save config
                        saveAiEditApiKey(context, apiKey)
                        saveAiEditModelName(context, modelName)

                        val items = selectedSourceAreas.map { (uri, sel) ->
                            AiEditJobItem(uri = uri, selection = sel)
                        }
                        AiEditWorkManager.enqueue(
                            context = context,
                            apiKey = apiKey,
                            modelName = modelName,
                            prompt = customPrompt,
                            targetUri = selectedTargetUri,
                            items = items
                        )
                        Toast.makeText(context, "✅ AI edit started in background", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !bgRunning && apiKey.isNotBlank() && selectedSourceAreas.isNotEmpty() && customPrompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (bgRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Background edit running...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Run AI Edit in Background")
                    }
                }

                // Progress bar while the background job runs
                if (bgRunning) {
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val bgProgressActive = bgPhase != AiEditBackgroundProgress.PHASE_IDLE
                        if (bgProgressActive && bgTotal > 0) {
                            LinearProgressIndicator(
                                progress = { bgCurrent.toFloat() / bgTotal.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "$bgCurrent / $bgTotal images done",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${bgCurrent * 100 / bgTotal.coerceAtLeast(1)}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = bgLabel.ifBlank { "Background edit running..." },
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
                            AiEditWorkManager.cancel(context)
                            Toast.makeText(context, "🛑 Stopping background AI edit...", Toast.LENGTH_SHORT).show()
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
                        Text("Stop Background Edit")
                    }
                }
            }

            // ======== Debug log ========
            if (bgDebugLog.isNotEmpty()) {
                item {
                    TextButton(onClick = { AiEditBackgroundDebugLog.clear() }) {
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
                                SelectionContainer {
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
            initialSelection = selectedSourceAreas[uri],
            onConfirm = { selection ->
                selectedSourceAreas = selectedSourceAreas + (uri to selection)
                saveAiEditSourceUris(context, selectedSourceAreas.keys)
                areaDialogUri = null
            },
            onDismiss = {
                areaDialogUri = null
            }
        )
    }
}

/**
 * Overlay composable that draws the [CropRect] on a square thumbnail,
 * correctly accounting for [ContentScale.Fit] letterboxing.
 *
 * The thumbnail box is a square; the image inside may not fill it.
 * We load the intrinsic image dimensions and compute the actual
 * displayed image bounds so the crop rect is positioned correctly.
 */
@Composable
private fun ThumbnailCropOverlay(
    imageUri: String,
    cropRect: CropRect
) {
    val context = LocalContext.current
    var imageDims by remember(imageUri) { mutableStateOf<Pair<Int, Int>?>(null) }
    var boxSize by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(imageUri) {
        imageDims = loadBitmapDimensions(context, imageUri)
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = Size(it.width.toFloat(), it.height.toFloat()) }
    ) {
        if (boxSize.width <= 0 || boxSize.height <= 0) return@Canvas
        val dims = imageDims

        val displayLeft: Float
        val displayTop: Float
        val displayRight: Float
        val displayBottom: Float
        if (dims != null) {
            val imgW = dims.first.toFloat()
            val imgH = dims.second.toFloat()
            val scale = minOf(boxSize.width / imgW, boxSize.height / imgH)
            val displayW = imgW * scale
            val displayH = imgH * scale
            val offsetX = (boxSize.width - displayW) / 2f
            val offsetY = (boxSize.height - displayH) / 2f
            displayLeft = cropRect.left * displayW + offsetX
            displayTop = cropRect.top * displayH + offsetY
            displayRight = cropRect.right * displayW + offsetX
            displayBottom = cropRect.bottom * displayH + offsetY
        } else {
            displayLeft = cropRect.left * boxSize.width
            displayTop = cropRect.top * boxSize.height
            displayRight = cropRect.right * boxSize.width
            displayBottom = cropRect.bottom * boxSize.height
        }

        drawRect(
            color = Color.White,
            topLeft = Offset(displayLeft, displayTop),
            size = Size(
                displayRight - displayLeft,
                displayBottom - displayTop
            ),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

// ---------- Persistence helpers (separate from AiRatingScreen's prefs) ----------

private const val AI_EDIT_PREFS = "pyqcr_ai_edit_prefs"
private const val KEY_API_KEY = "ai_edit_api_key"
private const val KEY_MODEL_NAME = "ai_edit_model_name"
private const val KEY_SAVED_PROMPT = "ai_edit_saved_prompt"
private const val DEFAULT_EDIT_MODEL = "qwen-image-2.0"

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

private const val KEY_SAVED_PROMPTS = "ai_edit_saved_prompts"
private const val MAX_SAVED_PROMPTS = 10

/** Load the saved prompt library (max 10), oldest first. */
private fun loadAiEditSavedPrompts(context: Context): List<String> {
    val prefs = context.getSharedPreferences("pyqcr_ai_edit_config", Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_SAVED_PROMPTS, null) ?: return emptyList()
    return try {
        com.google.gson.Gson().fromJson(raw, Array<String>::class.java)?.toList() ?: emptyList()
    } catch (e: Exception) { emptyList() }
}

/** Persist the saved prompt library, capped at [MAX_SAVED_PROMPTS]. */
private fun saveAiEditSavedPrompts(context: Context, prompts: List<String>) {
    val limited = prompts.take(MAX_SAVED_PROMPTS)
    val prefs = context.getSharedPreferences("pyqcr_ai_edit_config", Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_SAVED_PROMPTS, com.google.gson.Gson().toJson(limited)).apply()
}

/**
 * Load just the pixel dimensions of an image without decoding the full bitmap.
 * Also accounts for EXIF orientation so the returned dimensions match how
 * [coil3.compose.AsyncImage] displays the image (which applies EXIF rotation
 * automatically).
 * Supports content:// URIs and file paths.
 */
private fun loadBitmapDimensions(context: Context, uriStr: String): Pair<Int, Int>? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (uriStr.startsWith("content://")) {
            val uri = Uri.parse(uriStr)
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        } else {
            BitmapFactory.decodeFile(uriStr, opts)
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        var w = opts.outWidth
        var h = opts.outHeight

        // Check EXIF orientation — Coil applies it, so the displayed
        // dimensions may be swapped relative to the raw header values.
        try {
            val exif = if (uriStr.startsWith("content://")) {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ExifInterface(input)
                }
            } else {
                ExifInterface(uriStr)
            }
            if (exif != null) {
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE
                ) {
                    w = opts.outHeight
                    h = opts.outWidth
                }
            }
        } catch (_: Exception) {
            // EXIF not available or readable — use raw dimensions
        }

        Pair(w, h)
    } catch (e: Exception) {
        null
    }
}

// ---------- AI Edit batch-selection prefs (imported once on screen entry) ----------

private const val AI_EDIT_SELECT_PREFS = "pyqcr_ai_edit_select"
private const val KEY_SOURCE_URIS = "ai_edit_source_uris"
private const val KEY_TARGET_URIS = "ai_edit_target_uris"

private fun loadAiEditSourceUris(context: Context): Set<String> {
    val prefs = context.getSharedPreferences(AI_EDIT_SELECT_PREFS, Context.MODE_PRIVATE)
    return prefs.getStringSet(KEY_SOURCE_URIS, emptySet()) ?: emptySet()
}

private fun saveAiEditSourceUris(context: Context, uris: Set<String>) {
    context.getSharedPreferences(AI_EDIT_SELECT_PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_SOURCE_URIS, uris).apply()
}

private fun loadAiEditTargetUris(context: Context): Set<String> {
    val prefs = context.getSharedPreferences(AI_EDIT_SELECT_PREFS, Context.MODE_PRIVATE)
    return prefs.getStringSet(KEY_TARGET_URIS, emptySet()) ?: emptySet()
}

private fun saveAiEditTargetUris(context: Context, uris: Set<String>) {
    context.getSharedPreferences(AI_EDIT_SELECT_PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_TARGET_URIS, uris).apply()
}

private fun clearAiEditSelections(context: Context) {
    context.getSharedPreferences(AI_EDIT_SELECT_PREFS, Context.MODE_PRIVATE)
        .edit().remove(KEY_SOURCE_URIS).remove(KEY_TARGET_URIS).apply()
}