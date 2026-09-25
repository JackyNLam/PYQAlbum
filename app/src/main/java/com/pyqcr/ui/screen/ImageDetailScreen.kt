package com.pyqcr.ui.screen

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
 *
 * - Swipe down anywhere on the image → go back to the thumbnail grid.
 * - Swipe up → reveal rating / tags / AI selection panel.
 * - Image fills available screen space on either width or height (ContentScale.Fit,
 *   constrained by parent Box) — no cropping, no overflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDetailScreen(
    imageUriRaw: String,
    onBack: () -> Unit,
    onNextImage: () -> Unit = {},
    onPreviousImage: () -> Unit = {}
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
    var allTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var rating by remember { mutableFloatStateOf(0f) }
    var newTagName by remember { mutableStateOf("") }
    var showAddTag by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }  // Toggle top/bottom bar
    var showDetailPanel by remember { mutableStateOf(false) } // Swipe-up panel
    val coroutineScope = rememberCoroutineScope()

    // Used to detect gesture directions with thresholds
    val swipeThreshold = with(LocalDensity.current) { 60.dp.toPx() }
    var dragVerticalAccum by remember { mutableFloatStateOf(0f) }
    var dragHorizontalAccum by remember { mutableFloatStateOf(0f) }

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
                aiScore = it.aiScore,
                aiReason = it.aiReason
            )
        }
        rating = entity?.rating ?: 0f
    }

    LaunchedEffect(imageUri) {
        tagDao.getTagsForImage(imageUri).collect { tagList ->
            tags = tagList
        }
    }

    LaunchedEffect(Unit) {
        tagDao.getAllTags().collect { tagList ->
            allTags = tagList
        }
    }

    // Toggle between "show image only" and "show image + detail panel"
    // Toggle between "show image only" and "show image + detail panel"
    // (threshold replaced by swipeThreshold)

    Scaffold(
        topBar = {
            if (showControls && !showDetailPanel) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ===================== IMAGE AREA =====================
            // Fill the available space: if detail panel is visible, take top half;
            // otherwise fill entire screen.
            val imageModifier = if (showDetailPanel) {
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .align(Alignment.TopCenter)
            } else {
                Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            }

            Box(
                modifier = imageModifier
                    .background(Color.Black)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        // Detect vertical swipes (down → go back, up → show panel)
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragVerticalAccum > swipeThreshold) {
                                    onBack()
                                } else if (dragVerticalAccum < -swipeThreshold) {
                                    showDetailPanel = true
                                    showControls = false
                                }
                                dragVerticalAccum = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragVerticalAccum += dragAmount
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        // Detect horizontal swipes (left → next, right → previous)
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragHorizontalAccum < -swipeThreshold) {
                                    onNextImage()
                                } else if (dragHorizontalAccum > swipeThreshold) {
                                    onPreviousImage()
                                }
                                dragHorizontalAccum = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragHorizontalAccum += dragAmount
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUri)
                        .size(1600)
                        .crossfade(true)
                        .build(),
                    contentDescription = imageItem?.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // ===================== DETAIL PANEL (swipe-up) =====================
            if (showDetailPanel) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)
                        .align(Alignment.BottomCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                ) {
                    // Handle area + pull-down hint
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragVerticalAccum > swipeThreshold) {
                                            // swipe down on panel → hide panel
                                            showDetailPanel = false
                                            showControls = true
                                        }
                                        dragVerticalAccum = 0f
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        dragVerticalAccum += dragAmount
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Drag handle indicator
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    shape = MaterialTheme.shapes.small
                                )
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // ====== Metadata ======
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

                    // ====== Rating ======
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

                    // AI Score
                    imageItem?.aiScore?.let { aiScore ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "AI Score: ${String.format("%.1f", aiScore)}/100",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        imageItem?.aiReason?.let { reason ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // ====== Tags ======
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

                    // Add tag — pick from existing or create new
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
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            // Existing tags not already applied to this image
                            val existingTagNames = tags.map { it.name }.toSet()
                            val availableTags = allTags.filter { it.name !in existingTagNames }

                            if (availableTags.isNotEmpty()) {
                                Text(
                                    text = "Select existing tag:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                // Show available tags in a wrap layout built with FlowRow-like Row
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    availableTags.chunked(3).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            row.forEach { tag ->
                                                SuggestionChip(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            if (tagDao.hasTag(imageUri, tag.id) == 0) {
                                                                tagDao.addTagToImage(
                                                                    ImageTagCrossRef(imageUri = imageUri, tagId = tag.id)
                                                                )
                                                            }
                                                            showAddTag = false
                                                        }
                                                    },
                                                    label = { Text(tag.name, style = MaterialTheme.typography.bodySmall) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Create new tag input
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newTagName,
                                    onValueChange = { newTagName = it },
                                    label = { Text("Create new tag") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (newTagName.isNotBlank()) {
                                            coroutineScope.launch {
                                                val tag = tagDao.getTagByName(newTagName.trim())
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

                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { showAddTag = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ====== AI Ranking toggle ======
                    var isSelectedForAi by remember { mutableStateOf(imageUri in getSelectedAiUris(context)) }

                    Button(
                        onClick = {
                            val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
                            val current = prefs.getStringSet("ai_selected_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
                            if (imageUri in current) {
                                current.remove(imageUri)
                                isSelectedForAi = false
                            } else {
                                current.add(imageUri)
                                isSelectedForAi = true
                            }
                            prefs.edit().putStringSet("ai_selected_uris", current).apply()
                        },
                        colors = if (isSelectedForAi)
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        else
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isSelectedForAi) "✓ AI Ranking" else "AI Ranking",
                            color = if (isSelectedForAi) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

private fun getSelectedAiUris(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
    return prefs.getStringSet("ai_selected_uris", emptySet()) ?: emptySet()
}