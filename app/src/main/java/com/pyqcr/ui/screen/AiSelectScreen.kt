package com.pyqcr.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail

/**
 * Screen for managing images selected for AI rating.
 * Only shows the selected images as thumbnails — tap to deselect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSelectScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }

    var allImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var aiSelectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAiRatingScreen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images ->
            allImages = images
        }
    }

    LaunchedEffect(Unit) {
        val saved = loadAiSelectedUris(context)
        aiSelectedUris = saved
    }

    if (showAiRatingScreen) {
        AiRatingScreen(
            initialSelectedUris = aiSelectedUris,
            onBack = { showAiRatingScreen = false },
            onUrisChanged = { uris ->
                aiSelectedUris = uris
                saveAiSelectedUris(context, uris)
            }
        )
        return
    }

    val selectedImages = allImages.filter { it.uri in aiSelectedUris }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Select Photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (aiSelectedUris.isNotEmpty()) {
                        TextButton(onClick = {
                            aiSelectedUris = emptySet()
                            saveAiSelectedUris(context, emptySet())
                        }) {
                            Text("Clear All", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (aiSelectedUris.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 8.dp,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${selectedImages.size} selected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Button(
                            onClick = {
                                if (aiSelectedUris.isEmpty()) {
                                    Toast.makeText(context, "Select some images first", Toast.LENGTH_SHORT).show()
                                } else {
                                    showAiRatingScreen = true
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Submit & Rate")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (selectedImages.isEmpty()) {
                // Empty state — show info and all images for selection
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
            } else {
                // Show only selected images in a grid — tap to deselect
                Text(
                    text = "Tap any image to remove it from AI selection:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    gridItems(selectedImages, key = { it.uri }) { image ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clickable {
                                    aiSelectedUris = aiSelectedUris - image.uri
                                    saveAiSelectedUris(context, aiSelectedUris)
                                }
                        ) {
                            ImageThumbnail(
                                imageUri = image.uri,
                                modifier = Modifier.fillMaxSize(),
                                backgroundColor = Color.Black
                            )
                            // Green border overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(3.dp, Color.Green)
                            )
                            // X overlay on hover
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
        }
    }
}

private fun loadAiSelectedUris(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
    return prefs.getStringSet("ai_selected_uris", emptySet()) ?: emptySet()
}

private fun saveAiSelectedUris(context: Context, uris: Set<String>) {
    val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
    prefs.edit().putStringSet("ai_selected_uris", uris).apply()
}