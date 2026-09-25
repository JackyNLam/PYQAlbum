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
 * AI Select Photos screen.
 *
 * This screen now directly forwards to AiRatingScreen.
 * It acts as a bridge that loads the current AI-selected URIs
 * and immediately shows the AiRatingScreen with those selections.
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
        // Skip the selection screen — go directly to AiRatingScreen
        showAiRatingScreen = true
    }

    if (showAiRatingScreen) {
        AiRatingScreen(
            initialSelectedUris = aiSelectedUris,
            onBack = { onBack() },
            onUrisChanged = { uris ->
                aiSelectedUris = uris
                saveAiSelectedUris(context, uris)
            }
        )
        return
    }

    // Brief loading / fallback screen
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
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
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