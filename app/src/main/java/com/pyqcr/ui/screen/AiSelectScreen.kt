package com.pyqcr.ui.screen

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail

/**
 * Screen for selecting images to be used in AI rating.
 * Selected images will appear in the "AI Sel" tab on the AlbumScreen.
 * Navigates to AiRatingScreen with the selected URIs.
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

    // Load previously selected AI URIs from shared prefs
    LaunchedEffect(Unit) {
        aiSelectedUris = loadAiSelectedUris(context)
    }

    if (showAiRatingScreen) {
        // Inline AI rating flow with selected URIs
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
                    // Go to AI Rating with selected images
                    IconButton(
                        onClick = {
                            if (aiSelectedUris.isEmpty()) {
                                Toast.makeText(context, "Select some images first", Toast.LENGTH_SHORT).show()
                            } else {
                                showAiRatingScreen = true
                            }
                        }
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "AI Rate")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Selected images preview region (top strip)
            if (aiSelectedUris.isNotEmpty()) {
                Text(
                    text = "Selected for AI: ${aiSelectedUris.size} images",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                // Horizontal scroll of selected images
                val selectedImages = allImages.filter { it.uri in aiSelectedUris }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(selectedImages, key = { it.uri }) { image ->
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clickable {
                                    // Remove from selection on tap
                                    aiSelectedUris = aiSelectedUris - image.uri
                                    saveAiSelectedUris(context, aiSelectedUris)
                                }
                        ) {
                            ImageThumbnail(
                                imageUri = image.uri,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✕", color = Color.White)
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Tap images below to select them for AI rating.\nThen tap ✨ to start rating.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // All images grid for selection
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                gridItems(allImages, key = { it.uri }) { image ->
                    val isSelected = image.uri in aiSelectedUris
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                aiSelectedUris = if (isSelected)
                                    aiSelectedUris - image.uri
                                else
                                    aiSelectedUris + image.uri
                                saveAiSelectedUris(context, aiSelectedUris)
                            }
                    ) {
                        ImageThumbnail(
                            imageUri = image.uri,
                            modifier = Modifier.fillMaxSize(),
                            backgroundColor = Color.Black
                        )
                        if (isSelected) {
                            // Green checkmark overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(3.dp, Color.Green)
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(Color.Green)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "✓",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Load AI selected URIs from SharedPreferences.
 */
private fun loadAiSelectedUris(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
    return prefs.getStringSet("ai_selected_uris", emptySet()) ?: emptySet()
}

/**
 * Save AI selected URIs to SharedPreferences.
 */
private fun saveAiSelectedUris(context: Context, uris: Set<String>) {
    val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
    prefs.edit().putStringSet("ai_selected_uris", uris).apply()
}