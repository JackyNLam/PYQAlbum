package com.pyqcr.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail
import com.pyqcr.ui.component.RatingBar

/**
 * Rating browsing mode screen.
 * Shows images sorted/filtered by user rating and AI score.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingScreen(
    onImageClick: (String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember {
        AlbumRepository(context, app.database)
    }

    var images by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var sortMode by remember { mutableStateOf(SortMode.USER_RATING_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(sortMode) {
        val flow = when (sortMode) {
            SortMode.USER_RATING_DESC -> repository.getImagesSortedByUserRatingDesc()
            SortMode.AI_SCORE_DESC -> repository.getImagesSortedByAiScoreDesc()
        }
        flow.collect { imageList ->
            images = imageList
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rating") },
                navigationIcon = {
                    IconButton(onClick = { /* back handled by nav controller */ }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("User Rating ↓") },
                                onClick = {
                                    sortMode = SortMode.USER_RATING_DESC
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("AI Score ↓") },
                                onClick = {
                                    sortMode = SortMode.AI_SCORE_DESC
                                    showSortMenu = false
                                }
                            )
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
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(images, key = { it.uri }) { image ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onImageClick(image.uri) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ImageThumbnail(
                            imageUri = image.uri,
                            modifier = Modifier.size(72.dp)
                        )

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = image.displayName,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = image.folderName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⭐ ", style = MaterialTheme.typography.bodySmall)
                                RatingBar(
                                    rating = image.rating,
                                    starSize = 24.dp,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            image.aiScore?.let { aiScore ->
                                Text(
                                    text = "AI: ${String.format("%.1f", aiScore)}/100",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (image.rating > 0) {
                            Text(
                                text = String.format("%.1f", image.rating),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
