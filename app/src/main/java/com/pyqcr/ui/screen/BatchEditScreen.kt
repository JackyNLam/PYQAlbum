package com.pyqcr.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pyqcr.PyqCrApp
import com.pyqcr.data.db.TagEntity
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail
import com.pyqcr.ui.component.RatingBar
import com.pyqcr.ui.component.TagChip
import com.pyqcr.util.ImageUtil
import kotlinx.coroutines.launch

/**
 * Batch Edit screen for performing operations on multiple images.
 * Mode: "tag" (add/remove tags), "rate" (batch rating), "resize" (batch resize/rescale).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchEditScreen(
    mode: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val imageDao = app.database.imageDao()
    val tagDao = app.database.tagDao()
    val repository = remember { AlbumRepository(context, app.database) }

    var allImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }

    // Tag mode state
    var selectedTag by remember { mutableStateOf<TagEntity?>(null) }
    var newTagName by remember { mutableStateOf("") }

    // Rate mode state
    var batchRating by remember { mutableFloatStateOf(0f) }

    // Resize mode state
    var resizeMode by remember { mutableStateOf("50%") } // "50%" or "square"

    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images ->
            allImages = images
        }
        tagDao.getAllTags().collect { tags ->
            allTags = tags
        }
    }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (mode) {
                            "tag" -> "Batch Tag"
                            "rate" -> "Batch Rate"
                            "resize" -> "Batch Resize"
                            else -> "Batch Edit"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            // Select all / deselect
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Images (${selectedUris.size} selected)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    TextButton(onClick = {
                        selectedUris = if (selectedUris.size == allImages.size)
                            emptySet()
                        else
                            allImages.map { it.uri }.toSet()
                    }) {
                        Text(if (selectedUris.size == allImages.size) "Deselect All" else "Select All")
                    }
                }
            }

            // Image list
            items(allImages) { image ->
                val isSelected = image.uri in selectedUris
                ElevatedCard(
                    onClick = {
                        selectedUris = if (isSelected)
                            selectedUris - image.uri
                        else
                            selectedUris + image.uri
                    },
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ImageThumbnail(
                            imageUri = image.uri,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = image.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isSelected) {
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }

            // Mode-specific action section
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            when (mode) {
                "tag" -> {
                    // Select existing tag
                    item {
                        Text(
                            text = "Add Tag to Selected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (allTags.isNotEmpty()) {
                        item {
                            LazyColumn {
                                items(allTags) { tag ->
                                    FilterChip(
                                        selected = selectedTag?.id == tag.id,
                                        onClick = { selectedTag = tag },
                                        label = { Text(tag.name) },
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // New tag input
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newTagName,
                                onValueChange = { newTagName = it },
                                label = { Text("New tag name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val tag = selectedTag
                                    if (tag != null && selectedUris.isNotEmpty()) {
                                        scope.launch {
                                            val crossRefs = selectedUris.map { uri ->
                                                com.pyqcr.data.db.ImageTagCrossRef(
                                                    imageUri = uri,
                                                    tagId = tag.id
                                                )
                                            }
                                            tagDao.addTagToImages(crossRefs)
                                            Toast.makeText(context, "Tag added to ${selectedUris.size} images", Toast.LENGTH_SHORT).show()
                                        }
                                    } else if (newTagName.isNotBlank() && selectedUris.isNotEmpty()) {
                                        scope.launch {
                                            var tag = tagDao.getTagByName(newTagName.trim())
                                            val tagId = if (tag != null) tag.id
                                            else tagDao.insertTag(TagEntity(name = newTagName.trim()))
                                            val crossRefs = selectedUris.map { uri ->
                                                com.pyqcr.data.db.ImageTagCrossRef(imageUri = uri, tagId = tagId)
                                            }
                                            tagDao.addTagToImages(crossRefs)
                                            newTagName = ""
                                            Toast.makeText(context, "Tag added to ${selectedUris.size} images", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Select images and a tag", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = selectedUris.isNotEmpty()
                            ) {
                                Text("Apply")
                            }
                        }
                    }
                }

                "rate" -> {
                    item {
                        Text(
                            text = "Set Rating for Selected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RatingBar(
                                rating = batchRating,
                                onRatingChange = { newRating -> batchRating = newRating }
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                if (selectedUris.isNotEmpty()) {
                                    scope.launch {
                                        repository.updateRatings(selectedUris.toList(), batchRating)
                                        Toast.makeText(context, "Rating set for ${selectedUris.size} images", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Select images first", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = selectedUris.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Apply Rating ${String.format("%.1f", batchRating)}⭐")
                        }
                    }
                }

                "resize" -> {
                    item {
                        Text(
                            text = "Resize/Rescale Selected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = resizeMode == "50%",
                                onClick = { resizeMode = "50%" },
                                label = { Text("Resize 50%") }
                            )
                            FilterChip(
                                selected = resizeMode == "square",
                                onClick = { resizeMode = "square" },
                                label = { Text("Rescale to Square") }
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                if (selectedUris.isEmpty()) {
                                    Toast.makeText(context, "Select images first", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                scope.launch {
                                    val contentResolver = context.contentResolver
                                    selectedUris.forEach { uriString ->
                                        val sourceUri = Uri.parse(uriString)
                                        if (resizeMode == "50%") {
                                            ImageUtil.resize50Percent(contentResolver, sourceUri, sourceUri)
                                        } else {
                                            ImageUtil.rescaleToSquare(contentResolver, sourceUri, sourceUri)
                                        }
                                    }
                                    Toast.makeText(context, "Processed ${selectedUris.size} images", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = selectedUris.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (resizeMode == "50%") "Resize 50%" else "Rescale to Square")
                        }
                    }
                }
            }
        }
    }
}