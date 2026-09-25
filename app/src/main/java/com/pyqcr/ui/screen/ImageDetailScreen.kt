package com.pyqcr.ui.screen

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.pyqcr.PyqCrApp
import com.pyqcr.data.db.ImageTagCrossRef
import com.pyqcr.data.db.TagEntity
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.RatingBar
import com.pyqcr.ui.component.TagChip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen image detail view.
 * Tap anywhere on the image to toggle the top/bottom bars (immersive view).
 * Shows tags, rating editing, and metadata below the image.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDetailScreen(
    imageUriRaw: String,
    onBack: () -> Unit
) {
    // URL-decode the URI — Navigation Compose encodes it to avoid path-segment issues
    val imageUri = Uri.decode(imageUriRaw)

    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val imageDao = app.database.imageDao()
    val tagDao = app.database.tagDao()
    val repository = remember {
        AlbumRepository(context, app.database)
    }

    var imageItem by remember { mutableStateOf<ImageItem?>(null) }
    var tags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var rating by remember { mutableFloatStateOf(0f) }
    var newTagName by remember { mutableStateOf("") }
    var showAddTag by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }  // Toggle UI overlays
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(imageUri) {
        val entity = imageDao.getImageByUri(imageUri)
        imageItem = entity?.let {
            ImageItem(
                uri = it.uri,
                displayName = it.displayName,
                rating = it.rating,
                width = it.width,
                height = it.height,
                sizeBytes = it.sizeBytes,
                dateAdded = it.dateAdded,
                folderName = it.folderName,
                aiScore = it.aiScore
            )
        }
        rating = entity?.rating ?: 0f
    }

    LaunchedEffect(imageUri) {
        tagDao.getTagsForImage(imageUri).collect { tagList ->
            tags = tagList
        }
    }

    Scaffold(
        topBar = {
            if (showControls) {
                TopAppBar(
                    title = { Text(imageItem?.displayName ?: "Image") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Full-width image with tap-to-toggle controls
            val aspectRatio = remember(imageItem) {
                val item = imageItem
                if (item != null && item.height > 0)
                    item.width.toFloat() / item.height.toFloat()
                else 1f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures { showControls = !showControls }
                    }
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUri)
                        .size(1200)
                        .crossfade(true)
                        .build(),
                    contentDescription = imageItem?.displayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio),
                    contentScale = ContentScale.Fit
                )
            }

            // Metadata section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // File info
                    Text(
                        text = imageItem?.displayName ?: "",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Folder: ${imageItem?.folderName ?: "Unknown"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (imageItem?.width != null && imageItem?.height != null) {
                        Text(
                            text = "${imageItem?.width} x ${imageItem?.height}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Rating bar
                    Text(
                        text = "Rating",
                        style = MaterialTheme.typography.titleSmall
                    )
                    RatingBar(
                        rating = rating,
                        onRatingChange = { newRating ->
                            rating = newRating
                            coroutineScope.launch {
                                repository.updateRating(imageUri, newRating)
                            }
                        }
                    )

                    // AI Score display
                    imageItem?.aiScore?.let { aiScore ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "AI Score: ${String.format("%.1f", aiScore)}/100",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Tags
                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.titleSmall
                    )

                    if (tags.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            items(tags) { tag ->
                                TagChip(
                                    text = tag.name,
                                    onRemove = {
                                        coroutineScope.launch {
                                            tagDao.removeTagFromImage(imageUri, tag.id)
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No tags",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    // Add tag button
                    if (!showAddTag) {
                        OutlinedButton(
                            onClick = { showAddTag = true },
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Add Tag")
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            OutlinedTextField(
                                value = newTagName,
                                onValueChange = { newTagName = it },
                                label = { Text("Tag name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (newTagName.isNotBlank()) {
                                        coroutineScope.launch {
                                            var tag = tagDao.getTagByName(newTagName.trim())
                                            val tagId = if (tag != null) {
                                                tag.id
                                            } else {
                                                tagDao.insertTag(TagEntity(name = newTagName.trim()))
                                            }
                                            if (tagDao.hasTag(imageUri, tagId) == 0) {
                                                tagDao.addTagToImage(
                                                    ImageTagCrossRef(imageUri = imageUri, tagId = tagId)
                                                )
                                            }
                                            newTagName = ""
                                            showAddTag = false
                                        }
                                    }
                                }
                            ) {
                                Text("Add")
                            }
                        }
                    }

                    // AI Select button — add to selected images list
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            // Add this image to AI selected set
                            val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
                            val current = prefs.getStringSet("ai_selected_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
                            if (imageUri in current) {
                                current.remove(imageUri)
                            } else {
                                current.add(imageUri)
                            }
                            prefs.edit().putStringSet("ai_selected_uris", current).apply()
                        }
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Toggle AI Selection")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}