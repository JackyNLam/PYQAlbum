package com.pyqcr.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
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
 * Screen for selecting images to be used in AI rating.
 * Selected images are saved to SharedPreferences.
 * Tap the Submit button at the bottom to go to the AI Rating screen.
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Select Photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            // Submit button bar — always visible
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
                            text = "${aiSelectedUris.size} selected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Clear all
                            OutlinedButton(
                                onClick = {
                                    aiSelectedUris = emptySet()
                                    saveAiSelectedUris(context, emptySet())
                                }
                            ) {
                                Text("Clear")
                            }

                            // Submit button
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
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Selected images preview strip
            if (aiSelectedUris.isNotEmpty()) {
                Text(
                    text = "Selected for AI: ${aiSelectedUris.size} images",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

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
                                Text("✕", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Tap images below to select them for AI rating.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Then tap Submit & Rate at the bottom.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

            // Bottom spacer so bottomBar content doesn't overlap grid
            if (aiSelectedUris.isNotEmpty()) {
                Spacer(Modifier.height(80.dp))
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