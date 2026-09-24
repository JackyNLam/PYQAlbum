package com.pyqcr.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pyqcr.PyqCrApp
import com.pyqcr.data.db.TagEntity
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tag browsing mode screen.
 * Shows a list of tags, and when a tag is selected, shows images for that tag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    onImageClick: (String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val tagDao = app.database.tagDao()
    val repository = remember {
        AlbumRepository(context, app.database)
    }

    var tags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var selectedTag by remember { mutableStateOf<TagEntity?>(null) }
    var tagImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        tagDao.getAllTags().collect { tagList ->
            tags = tagList
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedTag?.name ?: "Tags") },
                navigationIcon = {
                    if (selectedTag != null) {
                        IconButton(onClick = { selectedTag = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (selectedTag == null) {
            // Show tag list
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(tags) { tag ->
                    ElevatedCard(
                        onClick = { selectedTag = tag },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = tag.name,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        } else {
            // Show images for selected tag
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(tagImages, key = { it.uri }) { image ->
                    ImageThumbnail(
                        imageUri = image.uri,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            LaunchedEffect(selectedTag) {
                selectedTag?.let { tag ->
                    repository.getImagesByTag(tag.name).collect { images ->
                        tagImages = images
                    }
                }
            }
        }
    }
}