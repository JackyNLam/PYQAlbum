package com.pyqcr.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.pyqcr.data.db.FolderInfo
import com.pyqcr.ui.util.FileOperationHelper
import com.pyqcr.ui.viewmodel.AlbumViewModel
import com.pyqcr.ui.viewmodel.FolderSortMode
import com.pyqcr.ui.viewmodel.FolderListSortMode
import com.pyqcr.ui.viewmodel.GroupByMode
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
    onImageClick: (String, imageList: List<String>) -> Unit,
    onNavigateToAiSelection: () -> Unit,
    viewModel: AlbumViewModel = viewModel()
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }
    val tagDao = app.database.tagDao()

    val images by viewModel.images.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val folderInfos by viewModel.folderInfos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Drawer state
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var selectedBrowseMode by rememberSaveable { mutableStateOf(BrowseMode.FOLDER) }
    var selectedViewLayout by rememberSaveable { mutableStateOf(ViewLayout.GRID) }
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTagName by rememberSaveable { mutableStateOf<String?>(null) }

    // Multi-select state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedImageUris by remember { mutableStateOf(setOf<String>()) }

    // AI rating selection image URIs
    var aiSelectedUris by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    // Long-press directly enters multi-select mode

    // Tag mode local state
    var allTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var tagImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }

    // Folder sort state — sort images inside a folder
    var folderSortMode by remember { mutableStateOf(FolderSortMode.MODIFIED_DATE_DESC) }
    var groupByMode by remember { mutableStateOf(GroupByMode.NONE) }

    // Copy/move operation state
    var copyMoveMessage by remember { mutableStateOf("") }
    var pendingOperation by remember { mutableStateOf<String?>(null) } // "copy" or "move"
    val snackbarHostState = remember { SnackbarHostState() }

    // Default target folder (Pictures/PYQAlbum/)
    var targetFolderForOperation by remember {
        mutableStateOf<File?>(FileUtils.getDefaultMoveDir(context))
    }

    /** Launch a copy or move operation on selected URIs. */
    val launchCopyMove: (String, List<String>, File?) -> Unit = { operation, uris, destDir ->
        if (destDir == null) {
            copyMoveMessage = "Destination folder not available"
            scope.launch { snackbarHostState.showSnackbar(copyMoveMessage) }
        } else {
            scope.launch {
                try {
                    val successCount = if (operation == "copy") {
                        com.pyqcr.ui.util.FileOperationHelper.batchCopyViaFiles(context, uris, destDir)
                            .count { it.second != null }
                    } else {
                        com.pyqcr.ui.util.FileOperationHelper.batchMoveViaFiles(context, uris, destDir)
                            .count { it.second != null }
                    }
                    val label = if (operation == "copy") "Copied" else "Moved"
                    copyMoveMessage = "$label $successCount/${uris.size} images to ${destDir.name}"
                    snackbarHostState.showSnackbar(copyMoveMessage)
                    // Reset multi-select after operation
                    if (operation == "move") {
                        // For moves, keep only URIs that still exist
                        selectedImageUris = selectedImageUris.filter { uri ->
                            val path = com.pyqcr.ui.util.FileOperationHelper.resolveFilePath(context, uri)
                            path != null && File(path).exists()
                        }.toSet()
                    }
                    isMultiSelectMode = false
                    selectedImageUris = emptySet()
                } catch (e: Exception) {
                    copyMoveMessage = "Operation failed: ${e.message}"
                    snackbarHostState.showSnackbar(copyMoveMessage)
                }
            }
        }
    }

    // SAF folder picker launcher — lets user choose any directory on the phone
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { treeUri ->
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            // Resolve the tree URI to a real directory path
            targetFolderForOperation = resolveTreeUriToPath(treeUri)
            pendingOperation?.let { op ->
                pendingOperation = null
                launchCopyMove(op, selectedImageUris.toList(), targetFolderForOperation)
            }
        }
    }

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

    // Load all tags (needed for tag assignment dialog in any browse mode)
    LaunchedEffect(Unit) {
        tagDao.getAllTags().collect { tagList ->
            allTags = tagList
        }
    }

    // Derive selectedTag from selectedTagName
    val selectedTag: TagEntity? = remember(selectedTagName, allTags) {
        allTags.find { it.name == selectedTagName }
    }

    // Load tag images when a tag is selected
    LaunchedEffect(selectedTag) {
        selectedTag?.let { tag ->
            repository.getImagesByTag(tag.name).collect { imageList ->
                tagImages = imageList
            }
        }
    }

    // Re-sort folder images when folderSortMode changes
    LaunchedEffect(folderSortMode, selectedFolder) {
        if (selectedFolder != null) {
            viewModel.loadImagesByFolderSorted(
                folderName = selectedFolder!!,
                sortByUserRating = folderSortMode == FolderSortMode.USER_RATING_DESC,
                sortByAiScore = folderSortMode == FolderSortMode.AI_SCORE_DESC,
                sortByName = folderSortMode == FolderSortMode.NAME_ASC
            )
        }
    }

    // Long-press directly enters multi-select mode
    // (dialog removed — shortcut for batch select)

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
                        selectedTagName = null
                        scope.launch { drawerState.close() }
                    }
                )

                DrawerItem(
                    icon = Icons.Default.Label,
                    label = "Tag",
                    selected = selectedBrowseMode == BrowseMode.TAG,
                    onClick = {
                        selectedBrowseMode = BrowseMode.TAG
                        selectedTagName = null
                        scope.launch { drawerState.close() }
                    }
                )

                // Rating tab removed — sorting by rating is now done via the Sort button
                // inside a folder's image grid view.

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
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            BrowseMode.TAG -> Text(selectedTagName ?: "Tags")
                        }
                    },
                    actions = {
                        // Folder list sort button (when showing all folders)
                        if (selectedBrowseMode == BrowseMode.FOLDER && selectedFolder == null) {
                            Box {
                                var showFolderSortMenu by remember { mutableStateOf(false) }
                                IconButton(onClick = { showFolderSortMenu = true }) {
                                    Icon(Icons.Default.Sort, contentDescription = "Sort folders")
                                }
                                DropdownMenu(
                                    expanded = showFolderSortMenu,
                                    onDismissRequest = { showFolderSortMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("↓Modified date", modifier = Modifier.weight(1f))
                                                if (viewModel.folderSortMode == FolderListSortMode.LAST_MODIFIED_DESC) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.updateFolderSortMode(FolderListSortMode.LAST_MODIFIED_DESC)
                                            showFolderSortMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("↓Count", modifier = Modifier.weight(1f))
                                                if (viewModel.folderSortMode == FolderListSortMode.COUNT_DESC) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.updateFolderSortMode(FolderListSortMode.COUNT_DESC)
                                            showFolderSortMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("↑Name", modifier = Modifier.weight(1f))
                                                if (viewModel.folderSortMode == FolderListSortMode.NAME_ASC) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.updateFolderSortMode(FolderListSortMode.NAME_ASC)
                                            showFolderSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // View layout selector drop-down + sort button (only for FOLDER mode, inside a folder)
                        if (selectedBrowseMode == BrowseMode.FOLDER && selectedFolder != null) {
                            // Layout selector button
                            Box {
                                var showLayoutMenu by remember { mutableStateOf(false) }
                                IconButton(onClick = { showLayoutMenu = true }) {
                                    when (selectedViewLayout) {
                                        ViewLayout.GRID -> Icon(
                                            Icons.Default.GridView,
                                            contentDescription = "Layout",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        ViewLayout.WATERFALL -> Text(
                                            "🌊",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        ViewLayout.JUSTIFIED -> Text(
                                            "▭",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showLayoutMenu,
                                    onDismissRequest = { showLayoutMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Grid") },
                                        onClick = {
                                            selectedViewLayout = ViewLayout.GRID
                                            showLayoutMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Waterfall") },
                                        onClick = {
                                            selectedViewLayout = ViewLayout.WATERFALL
                                            showLayoutMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Justified") },
                                        onClick = {
                                            selectedViewLayout = ViewLayout.JUSTIFIED
                                            showLayoutMenu = false
                                        }
                                    )
                                }
                            }

                            // Sort button — sort images in the folder
                            Box {
                                var showSortMenu by remember { mutableStateOf(false) }
                                var showGroupSubmenu by remember { mutableStateOf(false) }
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.Default.Sort, contentDescription = "Sort")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = {
                                        showSortMenu = false
                                        showGroupSubmenu = false
                                    }
                                ) {
                                    if (!showGroupSubmenu) {
                                        // Sort options
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("↓Modified date", modifier = Modifier.weight(1f))
                                                    if (folderSortMode == FolderSortMode.MODIFIED_DATE_DESC) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                folderSortMode = FolderSortMode.MODIFIED_DATE_DESC
                                                showSortMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("↑Name", modifier = Modifier.weight(1f))
                                                    if (folderSortMode == FolderSortMode.NAME_ASC) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                folderSortMode = FolderSortMode.NAME_ASC
                                                showSortMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Rating ↓", modifier = Modifier.weight(1f))
                                                    if (folderSortMode == FolderSortMode.USER_RATING_DESC) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                folderSortMode = FolderSortMode.USER_RATING_DESC
                                                showSortMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("AI Score ↓", modifier = Modifier.weight(1f))
                                                    if (folderSortMode == FolderSortMode.AI_SCORE_DESC) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                folderSortMode = FolderSortMode.AI_SCORE_DESC
                                                showSortMenu = false
                                            }
                                        )
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                        // Group by submenu entry
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Group by", modifier = Modifier.weight(1f))
                                                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            },
                                            onClick = {
                                                showGroupSubmenu = true
                                            }
                                        )
                                    } else {
                                        // Group by submenu
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Sort options", modifier = Modifier.weight(1f))
                                                }
                                            },
                                            onClick = { showGroupSubmenu = false }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("None", modifier = Modifier.weight(1f))
                                                    if (groupByMode == GroupByMode.NONE) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                groupByMode = GroupByMode.NONE
                                                showSortMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Day", modifier = Modifier.weight(1f))
                                                    if (groupByMode == GroupByMode.DAY) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                groupByMode = GroupByMode.DAY
                                                showSortMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Month", modifier = Modifier.weight(1f))
                                                    if (groupByMode == GroupByMode.MONTH) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                groupByMode = GroupByMode.MONTH
                                                showSortMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Year", modifier = Modifier.weight(1f))
                                                    if (groupByMode == GroupByMode.YEAR) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                groupByMode = GroupByMode.YEAR
                                                showSortMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (isMultiSelectMode) {
                    val urisSnapshot = selectedImageUris.toList()
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
                        },
                        onCopyTo = {
                            pendingOperation = "copy"
                            folderPickerLauncher.launch(null)
                        },
                        onMoveTo = {
                            pendingOperation = "move"
                            folderPickerLauncher.launch(null)
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
                                items(folderInfos) { folderInfo ->
                                    ElevatedCard(
                                        onClick = {
                                            selectedFolder = folderInfo.folderName
                                            folderSortMode = FolderSortMode.MODIFIED_DATE_DESC
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                Icons.Default.Folder,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = folderInfo.folderName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "${folderInfo.imageCount} images",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                        aiSelectedUris = aiSelectedUris,
                                        groupByMode = groupByMode,
                                        onImageClick = { uri, _ ->
                                            if (isMultiSelectMode) {
                                                selectedImageUris = if (uri in selectedImageUris)
                                                    selectedImageUris - uri
                                                else
                                                    selectedImageUris + uri
                                            } else {
                                                onImageClick(uri, images.map { it.uri })
                                            }
                                        },
                                        onMultiSelectImageToggle = { uri ->
                                            selectedImageUris = if (uri in selectedImageUris)
                                                selectedImageUris - uri
                                            else
                                                selectedImageUris + uri
                                        },
                                        onLongPress = { uri ->
                                            if (isMultiSelectMode) {
                                                selectedImageUris = if (uri in selectedImageUris)
                                                    selectedImageUris - uri
                                                else
                                                    selectedImageUris + uri
                                            } else {
                                                isMultiSelectMode = true
                                                selectedImageUris = setOf(uri)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    BrowseMode.TAG -> {
                        if (selectedTagName == null) {
                            // Show tag list as a grid
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(allTags) { tag ->
                                    ElevatedCard(
                                        onClick = { selectedTagName = tag.name },
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
                                    onClick = { selectedTagName = null },
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

                                val tagImageUris = remember(tagImages) { tagImages.map { it.uri } }

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
                                            .background(Color.White)
                                    ) {
                                        items(tagImages, key = { it.uri }) { image ->
                                            Box(
                                                modifier = Modifier
                                                    .aspectRatio(1f)
                                                    .combinedClickable(
                                                        onClick = { onImageClick(image.uri, tagImageUris) },
                                                        onLongClick = {
                                                            isMultiSelectMode = true
                                                            selectedImageUris = setOf(image.uri)
                                                        }
                                                    )
                                            ) {
                                                ImageThumbnail(
                                                    imageUri = image.uri,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                                    backgroundColor = Color.White,
                                                    rating = image.rating
                                                )
                                                // AI selection indicator
                                                if (image.uri in aiSelectedUris) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .background(
                                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                                                shape = MaterialTheme.shapes.small
                                                            )
                                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.AutoAwesome,
                                                            contentDescription = "AI Selected",
                                                            tint = Color.White,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
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

@Composable
private fun BatchMultiSelectBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onAddTag: () -> Unit,
    onRate: () -> Unit,
    onRemoveTags: () -> Unit,
    onSelectForAi: () -> Unit,
    onCopyTo: () -> Unit,
    onMoveTo: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Menu button — expands to show all actions
            Box {
                OutlinedButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.height(40.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions", modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Actions", maxLines = 1)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        onClick = { showMenu = false; onAddTag() },
                        text = { Text("Add Tag") },
                        leadingIcon = { Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        onClick = { showMenu = false; onRate() },
                        text = { Text("Rate") },
                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        onClick = { showMenu = false; onRemoveTags() },
                        text = { Text("Remove Tags") },
                        leadingIcon = { Icon(Icons.Default.LabelOff, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        onClick = { showMenu = false; onSelectForAi() },
                        text = { Text("Select for AI") },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        onClick = { showMenu = false; onCopyTo() },
                        text = { Text("Copy to…") },
                        leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        onClick = { showMenu = false; onMoveTo() },
                        text = { Text("Move to…") },
                        leadingIcon = { Icon(Icons.Default.Forward, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Selected count
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.width(12.dp))

            // Cancel button
            TextButton(onClick = onCancel, modifier = Modifier.height(40.dp)) {
                Text("Cancel")
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGridView(
    images: List<ImageItem>,
    viewLayout: ViewLayout,
    isMultiSelectMode: Boolean,
    selectedImageUris: Set<String>,
    aiSelectedUris: Set<String>,
    groupByMode: GroupByMode = GroupByMode.NONE,
    onImageClick: (String, List<String>) -> Unit,
    onLongPress: (String) -> Unit,
    onMultiSelectImageToggle: ((String) -> Unit)? = null
) {
    val imageUris = remember(images) { images.map { it.uri } }
    // Group images by date if requested
    val groups = remember(images, groupByMode) {
        if (groupByMode == GroupByMode.NONE) {
            emptyList()
        } else {
            val dateFormat = when (groupByMode) {
                GroupByMode.DAY -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                GroupByMode.MONTH -> SimpleDateFormat("yyyy-MM", Locale.getDefault())
                GroupByMode.YEAR -> SimpleDateFormat("yyyy", Locale.getDefault())
                else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            }
            images.groupBy { image ->
                image.dateAdded.let { dateFormat.format(Date(it * 1000)) }
            }.entries.sortedByDescending { it.key }
        }
    }

    when (viewLayout) {
        ViewLayout.GRID -> {
            if (groupByMode != GroupByMode.NONE && groups.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                ) {
                    groups.forEach { (header, groupImages) ->
                        // Date header spanning full width
                        item(span = { GridItemSpan(3) }) {
                            Text(
                                text = header,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF5F5F5))
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                        items(groupImages, key = { it.uri }) { image ->
                            ImageGridCell(
                                image = image,
                                imageUris = imageUris,
                                isMultiSelectMode = isMultiSelectMode,
                                selectedImageUris = selectedImageUris,
                                aiSelectedUris = aiSelectedUris,
                                onImageClick = onImageClick,
                                onLongPress = onLongPress,
                                onMultiSelectImageToggle = onMultiSelectImageToggle
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                ) {
                    items(images, key = { it.uri }) { image ->
                        ImageGridCell(
                            image = image,
                            imageUris = imageUris,
                            isMultiSelectMode = isMultiSelectMode,
                            selectedImageUris = selectedImageUris,
                            aiSelectedUris = aiSelectedUris,
                            onImageClick = onImageClick,
                            onLongPress = onLongPress,
                            onMultiSelectImageToggle = onMultiSelectImageToggle
                        )
                    }
                }
            }
        }

        ViewLayout.WATERFALL -> {
            val gridClick: (String) -> Unit = { uri -> onImageClick(uri, imageUris) }
            WaterfallGrid(
                images = images,
                columns = 3,
                isMultiSelectMode = isMultiSelectMode,
                selectedImageUris = selectedImageUris,
                aiSelectedUris = aiSelectedUris,
                onImageClick = gridClick,
                onLongPress = onLongPress,
                modifier = Modifier.fillMaxSize()
            )
        }

        ViewLayout.JUSTIFIED -> {
            val justifiedClick: (String) -> Unit = { uri -> onImageClick(uri, imageUris) }
            JustifiedGrid(
                images = images,
                isMultiSelectMode = isMultiSelectMode,
                selectedImageUris = selectedImageUris,
                aiSelectedUris = aiSelectedUris,
                onImageClick = justifiedClick,
                onLongPress = onLongPress,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// Extracted grid cell composable to avoid duplication
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGridCell(
    image: ImageItem,
    imageUris: List<String>,
    isMultiSelectMode: Boolean,
    selectedImageUris: Set<String>,
    aiSelectedUris: Set<String>,
    onImageClick: (String, List<String>) -> Unit,
    onLongPress: (String) -> Unit,
    onMultiSelectImageToggle: ((String) -> Unit)? = null
) {
    val isSelected = image.uri in selectedImageUris
    val cellModifier = if (isMultiSelectMode && onMultiSelectImageToggle != null) {
        Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = { onMultiSelectImageToggle(image.uri) },
                onLongClick = { onLongPress(image.uri) }
            )
    } else {
        Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = { onImageClick(image.uri, imageUris) },
                onLongClick = { onLongPress(image.uri) }
            )
    }
    Box(
        modifier = cellModifier
    ) {
        ImageThumbnail(
            imageUri = image.uri,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            backgroundColor = Color.White,
            rating = image.rating
        )
        // AI selection indicator (AutoAwesome icon)
        if (image.uri in aiSelectedUris) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "AI Selected",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        if (isMultiSelectMode && isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x80000000))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else if (isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RadioButtonUnchecked,
                    contentDescription = "Not selected",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

enum class BrowseMode { FOLDER, TAG }
private enum class ViewLayout { GRID, WATERFALL, JUSTIFIED }

// Used by RatingScreen.kt (still accessible via nav route)
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

// ---------- SAF tree URI to path resolution ----------

/**
 * Attempts to resolve an SAF tree URI to a real File path.
 * Works for primary storage ("primary:") tree URIs.
 * Returns null if resolution fails.
 */
private fun resolveTreeUriToPath(treeUri: Uri): File? {
    return try {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        // docId looks like "primary:DCIM/Camera" or "XXXX-XXXX:path"
        if (docId.startsWith("primary:")) {
            val relativePath = docId.removePrefix("primary:")
            File(android.os.Environment.getExternalStorageDirectory(), relativePath)
        } else {
            // SD card or other volume — hard to resolve to a real File path on modern Android
            // Fall back to default Pictures/PYQAlbum/
            null
        }
    } catch (e: Exception) {
        null
    }
}

/** File utility helpers for copy/move operations. */
internal object FileUtils {
    fun getDefaultMoveDir(context: Context): File {
        val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_PICTURES
        )
        val dir = File(picturesDir, "PYQAlbum")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}