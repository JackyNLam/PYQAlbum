package com.pyqcr.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pyqcr.PyqCrApp
import com.pyqcr.data.db.TagEntity
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.*
import com.pyqcr.ui.viewmodel.AlbumViewModel
import kotlinx.coroutines.launch

/**
 * Main album screen.
 *
 * Browse modes: Folder / Tag / Rating (3 options in left drawer)
 * View layouts: Grid / Waterfall / Justified (toolbar toggles)
 *
 * Long-press any image → opens a dialog to assign rating, add tag,
 * or select for AI ranking.
 *
 * Multi-select mode: bottom action bar with batch operations.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(
    onImageClick: (String) -> Unit,
    onNavigateToAiSelection: () -> Unit,
    viewModel: AlbumViewModel = viewModel()
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }
    val tagDao = app.database.tagDao()

    val images by viewModel.images.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Drawer state
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var selectedBrowseMode by remember { mutableStateOf(BrowseMode.FOLDER) }
    var selectedViewLayout by remember { mutableStateOf(ViewLayout.GRID) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedTag by remember { mutableStateOf<TagEntity?>(null) }

    // Multi-select state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedImageUris by remember { mutableStateOf(setOf<String>()) }

    // AI rating selection image URIs
    var aiSelectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Long-press dialog state
    var longPressedImageUri by remember { mutableStateOf<String?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }

    // Tag mode local state
    var tags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var tagImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }

    // Rating mode local state
    var ratingImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var sortMode by remember { mutableStateOf(SortMode.USER_RATING_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Load AI selected URIs
    LaunchedEffect(Unit) {
        aiSelectedUris = loadAiSelectedUris(context)
    }

    // Permission
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    Manifest.permission.READ_MEDIA_IMAGES
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.refreshImages()
    }

    LaunchedEffect(Unit) {
        if (hasPermission) viewModel.refreshImages()
    }

    if (!hasPermission) {
        PermissionRequestScreen(
            onRequestPermission = {
                permissionLauncher.launch(
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        Manifest.permission.READ_MEDIA_IMAGES
                    else
                        Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
        )
        return
    }

    // Load tags for Tag mode
    LaunchedEffect(selectedBrowseMode) {
        if (selectedBrowseMode == BrowseMode.TAG) {
            tagDao.getAllTags().collect { tagList ->
                tags = tagList
            }
        }
    }

    // Load tag images when a tag is selected
    LaunchedEffect(selectedTag) {
        selectedTag?.let { tag ->
            repository.getImagesByTag(tag.name).collect { imageList ->
                tagImages = imageList
            }
        }
    }

    // Load rating images when sort mode changes
    LaunchedEffect(sortMode) {
        val flow = when (sortMode) {
            SortMode.USER_RATING_DESC -> repository.getImagesSortedByUserRatingDesc()
            SortMode.AI_SCORE_DESC -> repository.getImagesSortedByAiScoreDesc()
        }
        flow.collect { imageList ->
            ratingImages = imageList
        }
    }

    // Long-press action dialog
    if (showActionDialog && longPressedImageUri != null) {
        val imageUri = longPressedImageUri!!
        LongPressActionDialog(
            imageUri = imageUri,
            currentAiSelected = imageUri in aiSelectedUris,
            onDismiss = {
                showActionDialog = false
                longPressedImageUri = null
            },
            onAssignRating = { rating ->
                viewModel.updateRating(imageUri, rating)
                showActionDialog = false
                longPressedImageUri = null
            },
            onSelectForAi = { select ->
                aiSelectedUris = if (select)
                    aiSelectedUris + imageUri
                else
                    aiSelectedUris - imageUri
                saveAiSelectedUris(context, aiSelectedUris)
                showActionDialog = false
                longPressedImageUri = null
            },
            onAddTag = { tagName ->
                viewModel.addTagToImage(imageUri, tagName)
                showActionDialog = false
                longPressedImageUri = null
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "pyqAlbum",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )

                HorizontalDivider()

                DrawerItem(
                    icon = Icons.Default.Folder,
                    label = "Folder",
                    selected = selectedBrowseMode == BrowseMode.FOLDER,
                    onClick = {
                        selectedBrowseMode = BrowseMode.FOLDER
                        selectedTag = null
                        scope.launch { drawerState.close() }
                    }
                )

                DrawerItem(
                    icon = Icons.Default.Label,
                    label = "Tag",
                    selected = selectedBrowseMode == BrowseMode.TAG,
                    onClick = {
                        selectedBrowseMode = BrowseMode.TAG
                        selectedTag = null
                        scope.launch { drawerState.close() }
                    }
                )

                DrawerItem(
                    icon = Icons.Default.Star,
                    label = "Rating",
                    selected = selectedBrowseMode == BrowseMode.RATING,
                    onClick = {
                        selectedBrowseMode = BrowseMode.RATING
                        scope.launch { drawerState.close() }
                    }
                )

                Spacer(Modifier.weight(1f))

                HorizontalDivider()

                DrawerItem(
                    icon = Icons.Default.AutoAwesome,
                    label = "AI Rating",
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToAiSelection()
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    title = {
                        when (selectedBrowseMode) {
                            BrowseMode.FOLDER -> Text(
                                if (selectedFolder != null) selectedFolder!! else "pyqAlbum"
                            )
                            BrowseMode.TAG -> Text(selectedTag?.name ?: "Tags")
                            BrowseMode.RATING -> Text("Rating")
                            BrowseMode.AI_SELECTED -> Text("AI Selected")
                        }
                    },
                    actions = {
                        // View layout toggle buttons (only for FOLDER mode)
                        if (selectedBrowseMode == BrowseMode.FOLDER && selectedFolder != null) {
                            IconButton(onClick = { selectedViewLayout = ViewLayout.GRID }) {
                                Icon(
                                    Icons.Default.GridView,
                                    contentDescription = "Grid",
                                    tint = if (selectedViewLayout == ViewLayout.GRID)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { selectedViewLayout = ViewLayout.WATERFALL }) {
                                Text(
                                    "🌊",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selectedViewLayout == ViewLayout.WATERFALL)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { selectedViewLayout = ViewLayout.JUSTIFIED }) {
                                Text(
                                    "▭",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selectedViewLayout == ViewLayout.JUSTIFIED)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Sort button for Rating mode
                        if (selectedBrowseMode == BrowseMode.RATING) {
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
                    }
                )
            },
            bottomBar = {
                if (isMultiSelectMode) {
                    BatchMultiSelectBar(
                        selectedCount = selectedImageUris.size,
                        onCancel = {
                            isMultiSelectMode = false
                            selectedImageUris = emptySet()
                        },
                        onAddTag = {
                            // Batch add tag — use ViewModel
                            isMultiSelectMode = false
                            selectedImageUris = emptySet()
                        },
                        onRate = {
                            // Batch rate — use ViewModel
                            isMultiSelectMode = false
                            selectedImageUris = emptySet()
                        },
                        onRemoveTags = {
                            isMultiSelectMode = false
                            selectedImageUris = emptySet()
                        },
                        onSelectForAi = {
                            val current = aiSelectedUris.toMutableSet()
                            current.addAll(selectedImageUris)
                            aiSelectedUris = current
                            saveAiSelectedUris(context, current)
                            isMultiSelectMode = false
                            selectedImageUris = emptySet()
                        }
                    )
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (selectedBrowseMode) {
                    BrowseMode.FOLDER -> {
                        if (selectedFolder == null) {
                            // Step 1: Show only folder list — no images
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(folders) { folder ->
                                    ElevatedCard(
                                        onClick = {
                                            selectedFolder = folder
                                            viewModel.loadImagesByFolder(folder)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                Icons.Default.Folder,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                text = folder,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Step 2: Show images for selected folder with back button
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Back button row
                                TextButton(
                                    onClick = {
                                        selectedFolder = null
                                        viewModel.refreshImages()
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        contentDescription = "Back",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("All Folders")
                                }

                                if (isLoading) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                } else if (images.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No images in this folder",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    ImageGridView(
                                        images = images,
                                        viewLayout = selectedViewLayout,
                                        isMultiSelectMode = isMultiSelectMode,
                                        selectedImageUris = selectedImageUris,
                                        onImageClick = { uri ->
                                            if (isMultiSelectMode) {
                                                selectedImageUris = if (uri in selectedImageUris)
                                                    selectedImageUris - uri
                                                else
                                                    selectedImageUris + uri
                                            } else {
                                                onImageClick(uri)
                                            }
                                        },
                                        onLongPress = { uri ->
                                            if (isMultiSelectMode) {
                                                selectedImageUris = if (uri in selectedImageUris)
                                                    selectedImageUris - uri
                                                else
                                                    selectedImageUris + uri
                                            } else {
                                                longPressedImageUri = uri
                                                showActionDialog = true
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    BrowseMode.TAG -> {
                        if (selectedTag == null) {
                            // Show tag list as a grid
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
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
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Back button row
                                TextButton(
                                    onClick = { selectedTag = null },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        contentDescription = "Back",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("All Tags")
                                }

                                if (tagImages.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No images for this tag",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(3),
                                        contentPadding = PaddingValues(2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black)
                                    ) {
                                        items(tagImages, key = { it.uri }) { image ->
                                            Box(
                                                modifier = Modifier
                                                    .aspectRatio(1f)
                                                    .combinedClickable(
                                                        onClick = { onImageClick(image.uri) },
                                                        onLongClick = {
                                                            longPressedImageUri = image.uri
                                                            showActionDialog = true
                                                        }
                                                    )
                                            ) {
                                                ImageThumbnail(
                                                    imageUri = image.uri,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                                    backgroundColor = Color.Black
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    BrowseMode.RATING -> {
                        if (ratingImages.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "No rated images yet",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Long-press an image to assign a rating",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(ratingImages, key = { it.uri }) { image ->
                                    ElevatedCard(
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = { onImageClick(image.uri) },
                                                    onLongClick = {
                                                        longPressedImageUri = image.uri
                                                        showActionDialog = true
                                                    }
                                                )
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

                    BrowseMode.AI_SELECTED -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "AI Selected view removed.\nUse toolbar ✨ to select images.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single row item for the navigation drawer.
 */
@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (selected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onSurface

    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

/**
 * Long-press action dialog — assign rating, add tag, or select for AI ranking.
 * Uses a state machine: "main" → "rating" | "tag" | done.
 */
@Composable
private fun LongPressActionDialog(
    imageUri: String,
    currentAiSelected: Boolean,
    onDismiss: () -> Unit,
    onAssignRating: (Float) -> Unit,
    onSelectForAi: (Boolean) -> Unit,
    onAddTag: (String) -> Unit
) {
    var dialogStep by remember { mutableStateOf<DialogStep>(DialogStep.Main) }
    var customTagName by remember { mutableStateOf("") }
    var selectedRating by remember { mutableFloatStateOf(0f) }

    when (dialogStep) {
        DialogStep.Main -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Image Actions") },
                text = {
                    Column {
                        Text(
                            text = "What would you like to do with this image?",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Spacer(Modifier.height(4.dp))

                        OutlinedButton(
                            onClick = { dialogStep = DialogStep.Rating },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Assign Rating")
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { dialogStep = DialogStep.Tag },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add Tag")
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                onSelectForAi(!currentAiSelected)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (currentAiSelected) "Remove from AI Selection" else "Select for AI Ranking")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        }

        DialogStep.Rating -> {
            AlertDialog(
                onDismissRequest = { dialogStep = DialogStep.Main },
                title = { Text("Assign Rating") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Select a rating for this image:")
                        Spacer(Modifier.height(8.dp))
                        RatingBar(
                            rating = selectedRating,
                            onRatingChange = { selectedRating = it }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = String.format("%.1f stars", selectedRating),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onAssignRating(selectedRating)
                    }) {
                        Text("Assign")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialogStep = DialogStep.Main }) {
                        Text("Back")
                    }
                }
            )
        }

        DialogStep.Tag -> {
            AlertDialog(
                onDismissRequest = { dialogStep = DialogStep.Main },
                title = { Text("Add Tag") },
                text = {
                    OutlinedTextField(
                        value = customTagName,
                        onValueChange = { customTagName = it },
                        label = { Text("Tag name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (customTagName.isNotBlank()) {
                            onAddTag(customTagName.trim())
                        }
                        customTagName = ""
                    }) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        customTagName = ""
                        dialogStep = DialogStep.Main
                    }) {
                        Text("Back")
                    }
                }
            )
        }
    }
}

private enum class DialogStep { Main, Rating, Tag }

/**
 * Batch multi-select bottom bar.
 */
@Composable
private fun BatchMultiSelectBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onAddTag: () -> Unit,
    onRate: () -> Unit,
    onRemoveTags: () -> Unit,
    onSelectForAi: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedButton(onClick = onAddTag, modifier = Modifier.weight(1f)) {
                    Text("Tag", maxLines = 1)
                }
                OutlinedButton(onClick = onRate, modifier = Modifier.weight(1f)) {
                    Text("Rate", maxLines = 1)
                }
                OutlinedButton(onClick = onRemoveTags, modifier = Modifier.weight(1f)) {
                    Text("Rm Tag", maxLines = 1)
                }
                Button(onClick = onSelectForAi, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("AI", maxLines = 1)
                }
            }
        }
    }
}

@Composable
@Composable
private fun PermissionRequestScreen(
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Need permission to access photos",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGridView(
    images: List<ImageItem>,
    viewLayout: ViewLayout,
    isMultiSelectMode: Boolean,
    selectedImageUris: Set<String>,
    onImageClick: (String) -> Unit,
    onLongPress: (String) -> Unit
) {
    when (viewLayout) {
        ViewLayout.GRID -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                items(images, key = { it.uri }) { image ->
                    val isSelected = image.uri in selectedImageUris
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .combinedClickable(
                                onClick = { onImageClick(image.uri) },
                                onLongClick = { onLongPress(image.uri) }
                            )
                    ) {
                        ImageThumbnail(
                            imageUri = image.uri,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            backgroundColor = Color.Black
                        )
                        if (isMultiSelectMode && isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x80000000))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(4.dp)
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

        ViewLayout.WATERFALL -> {
            WaterfallGrid(
                images = images,
                columns = 3,
                isMultiSelectMode = isMultiSelectMode,
                selectedImageUris = selectedImageUris,
                onImageClick = onImageClick,
                onLongPress = onLongPress,
                modifier = Modifier.fillMaxSize()
            )
        }

        ViewLayout.JUSTIFIED -> {
            JustifiedGrid(
                images = images,
                isMultiSelectMode = isMultiSelectMode,
                selectedImageUris = selectedImageUris,
                onImageClick = onImageClick,
                onLongPress = onLongPress,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

enum class BrowseMode { FOLDER, TAG, RATING, AI_SELECTED }
private enum class ViewLayout { GRID, WATERFALL, JUSTIFIED }
enum class SortMode { USER_RATING_DESC, AI_SCORE_DESC }

// ---------- SharedPreferences helpers for AI selection ----------
private fun loadAiSelectedUris(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
    return prefs.getStringSet("ai_selected_uris", emptySet()) ?: emptySet()
}

private fun saveAiSelectedUris(context: Context, uris: Set<String>) {
    val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
    prefs.edit().putStringSet("ai_selected_uris", uris).apply()
}