package com.pyqcr.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pyqcr.data.model.ImageItem
import com.pyqcr.ui.component.*
import com.pyqcr.ui.viewmodel.AlbumViewModel

/**
 * Main album screen with:
 * - Three browse modes: Folder / Tag / Rating (bottom nav or tabs)
 * - Three view layouts: Standard Grid / Waterfall / Justified (toolbar toggle)
 * - Multi-select with bottom action bar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    onImageClick: (String) -> Unit,
    onNavigateToAiSelection: () -> Unit,
    viewModel: AlbumViewModel = viewModel()
) {
    val context = LocalContext.current
    val images by viewModel.images.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var selectedBrowseMode by remember { mutableStateOf(BrowseMode.FOLDER) }
    var selectedViewLayout by remember { mutableStateOf(ViewLayout.GRID) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }

    // Multi-select state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedImageUris by remember { mutableStateOf(setOf<String>()) }

    // AI rating selection image URIs (persistent set shown in selected region)
    var aiSelectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("pyqAlbum") },
                actions = {
                    // View layout toggle buttons (GRID / WATERFALL / JUSTIFIED — LIST removed)
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
                    // AI select mode toggle
                    IconButton(onClick = { onNavigateToAiSelection() }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "AI Select")
                    }
                }
            )
        },
        bottomBar = {
            if (isMultiSelectMode) {
                BottomActionBar(
                    selectedCount = selectedImageUris.size,
                    actions = listOf(
                        ActionBarAction("Add Tag", "🏷️") { /* Navigate to batch tag */ },
                        ActionBarAction("Rate", "⭐") { /* Navigate to batch rate */ },
                        ActionBarAction("Remove Tag", "🗑️") { /* Navigate to batch remove tag */ }
                    ),
                    onCancel = {
                        isMultiSelectMode = false
                        selectedImageUris = emptySet()
                    }
                )
            } else {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedBrowseMode == BrowseMode.FOLDER,
                        onClick = { selectedBrowseMode = BrowseMode.FOLDER },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Folder") },
                        label = { Text("Folder") }
                    )
                    NavigationBarItem(
                        selected = selectedBrowseMode == BrowseMode.TAG,
                        onClick = { selectedBrowseMode = BrowseMode.TAG },
                        icon = { Icon(Icons.Default.Label, contentDescription = "Tag") },
                        label = { Text("Tag") }
                    )
                    NavigationBarItem(
                        selected = selectedBrowseMode == BrowseMode.RATING,
                        onClick = { selectedBrowseMode = BrowseMode.RATING },
                        icon = { Icon(Icons.Default.Star, contentDescription = "Rating") },
                        label = { Text("Rating") }
                    )
                    NavigationBarItem(
                        selected = selectedBrowseMode == BrowseMode.AI_SELECTED,
                        onClick = { selectedBrowseMode = BrowseMode.AI_SELECTED },
                        icon = { Icon(Icons.Default.CheckCircle, contentDescription = "AI Selected") },
                        label = { Text("AI Sel") }
                    )
                }
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
                    // Folder selector — takes more vertical space with larger chips
                    FolderSelector(
                        folders = folders,
                        selectedFolder = selectedFolder,
                        onFolderSelected = { folder ->
                            selectedFolder = folder
                            viewModel.loadImagesByFolder(folder)
                        },
                        onAllSelected = {
                            selectedFolder = null
                            viewModel.refreshImages()
                        }
                    )

                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
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
                                if (!isMultiSelectMode) {
                                    isMultiSelectMode = true
                                    selectedImageUris = setOf(uri)
                                }
                            }
                        )
                    }
                }

                BrowseMode.TAG -> {
                    Text(
                        text = "Tag browsing will navigate to TagScreen",
                        modifier = Modifier.padding(16.dp)
                    )
                }

                BrowseMode.RATING -> {
                    Text(
                        text = "Rating browsing will navigate to RatingScreen",
                        modifier = Modifier.padding(16.dp)
                    )
                }

                BrowseMode.AI_SELECTED -> {
                    // Show only images that were selected for AI rating
                    if (aiSelectedUris.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No AI-selected images.\nGo to AI Select screen to pick images.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val aiImages = images.filter { it.uri in aiSelectedUris }
                        ImageGridView(
                            images = aiImages,
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
                                if (!isMultiSelectMode) {
                                    isMultiSelectMode = true
                                    selectedImageUris = setOf(uri)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderSelector(
    folders: List<String>,
    selectedFolder: String?,
    onFolderSelected: (String) -> Unit,
    onAllSelected: () -> Unit
) {
    // Taller, more spacious folder selector
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            FilterChip(
                selected = selectedFolder == null,
                onClick = onAllSelected,
                label = { Text("All", fontWeight = FontWeight.Bold) },
                modifier = Modifier.height(40.dp)
            )
        }
        items(folders) { folder ->
            FilterChip(
                selected = folder == selectedFolder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folder, maxLines = 1) },
                modifier = Modifier.height(40.dp)
            )
        }
    }
}

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
                    .background(Color.Black) // Black background for grid
            ) {
                items(images, key = { it.uri }) { image ->
                    ImageThumbnail(
                        imageUri = image.uri,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable { onImageClick(image.uri) },
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        backgroundColor = Color.Black
                    )
                }
            }
        }

        ViewLayout.WATERFALL -> {
            WaterfallGrid(
                images = images,
                columns = 2,
                modifier = Modifier.fillMaxSize()
            )
        }

        ViewLayout.JUSTIFIED -> {
            JustifiedGrid(
                images = images,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

enum class BrowseMode { FOLDER, TAG, RATING, AI_SELECTED }
enum class ViewLayout { GRID, WATERFALL, JUSTIFIED }