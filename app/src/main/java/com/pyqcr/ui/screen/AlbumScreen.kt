package com.pyqcr.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pyqcr.PyqCrApp
import com.pyqcr.data.db.ImageEntity
import com.pyqcr.data.db.ImageTagCrossRef
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
import com.pyqcr.util.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main album screen.
 *
 * Browse modes: Folder / Tag / Rating (3 options in left drawer)
 * View layouts: Grid / Waterfall / Justified (toolbar toggles)
 *
 * Long-press any image → enters multi-select mode.
 * Multi-select mode: bottom action bar (⋮) with batch operations:
 * Rating, Tag management, AI Ranking toggle, Remove AI Info, Copy to, Move to.
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

    // Batch operation dialog states
    var showTagDialog by remember { mutableStateOf(false) }
    var showRateDialog by remember { mutableStateOf(false) }
    var showRemoveTagsDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // Tags that exist on the currently selected images (for the Remove Tags dialog)
    var selectedImageTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }

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

    // Destination for copy/move:
    //  - targetFolderForOperation: real File path — non-null only when the picked
    //    folder is on internal storage ("primary:" tree) and resolves to a path.
    //  - selectedTreeUri: the retained SAF tree URI of the picked folder; used for
    //    the actual write whenever the File path is unavailable or blocked
    //    (SD card, cloud providers, Android 11+ scoped-storage paths).
    var targetFolderForOperation by remember { mutableStateOf<File?>(null) }
    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }
    // In-app folder browser for copy/move — shows all directories under
    // external storage, with navigation into subdirectories. "Choose folder
    // (system picker)…" opens the SAF picker for inaccessible locations.
    var showDestinationDialog by remember { mutableStateOf(false) }
    var destinationDialogOp by remember { mutableStateOf("copy") }
    // Current directory for the in-app folder browser; reset to external storage
    // root each time the destination dialog opens so the user starts from the top.
    var folderBrowserDir by remember { mutableStateOf(android.os.Environment.getExternalStorageDirectory()) }
    // Offers "All files access" once (API 30+) — the file-manager-style permission
    // that lets copy/move write into ANY folder via direct paths.
    var showFullAccessDialog by remember { mutableStateOf(false) }

    // Move: stores (snackbar message, source URIs to delete) while waiting for the
    // system delete-request dialog (createDeleteRequest) to return.
    var pendingMoveResult by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    // System delete confirmation launcher for MOVE — one dialog for all source files
    // that were copied via MediaStore/SAF (API 30+). On confirm the originals are
    // trashed; on cancel the copies remain (the move becomes a copy).
    val moveDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingMoveResult ?: return@rememberLauncherForActivityResult
        pendingMoveResult = null
        val message = pending.first
        val uris = pending.second
        val finalMessage = if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.deleteImages(uris)
            message.replace("Copied", "Moved")
        } else {
            "$message — originals kept"
        }
        scope.launch { snackbarHostState.showSnackbar(finalMessage) }
        isMultiSelectMode = false
        selectedImageUris = emptySet()
        viewModel.refreshImages()
    }

    /** Launch a copy or move operation on selected URIs. */
    val launchCopyMove: (String, List<String>, File?, Uri?) -> Unit = { operation, uris, destDir, treeUri ->
        if (destDir == null && treeUri == null) {
            copyMoveMessage = "Destination folder not available"
            scope.launch { snackbarHostState.showSnackbar(copyMoveMessage) }
        } else {
            scope.launch {
                try {
                    // Collect a per-file failure reason so the user sees WHY files failed
                    val failures = mutableListOf<String>()
                    val results = if (operation == "copy") {
                        com.pyqcr.ui.util.FileOperationHelper.batchCopyViaFiles(
                            context, uris, destDir, treeUri
                        ) { _, r ->
                            if (!r.success) failures += r.error ?: "Unknown error"
                        }
                    } else {
                        com.pyqcr.ui.util.FileOperationHelper.batchMoveViaFiles(
                            context, uris, destDir, treeUri
                        ) { _, r ->
                            if (!r.success) failures += r.error ?: "Unknown error"
                        }
                    }
                    val successCount = results.count { it.second.success }
                    val folderLabel = destinationFolderLabel(destDir, treeUri)

                    // Move: collect sources copied via SAF/MediaStore that need system-delete
                    // confirmation. On API 30+ one createDeleteRequest dialog covers them all.
                    if (operation == "move") {
                        val sourcesToDelete = results
                            .filter { it.second.success && it.second.sourceNeedsDeletion }
                            .map { it.first }

                        if (sourcesToDelete.isNotEmpty()) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                val request = FileOperationHelper.createDeleteRequest(context, sourcesToDelete)
                                if (request != null) {
                                    val baseMsg = buildString {
                                        append("Copied $successCount/${uris.size} images to $folderLabel")
                                        if (failures.isNotEmpty()) {
                                            append("\n${failures.size} failed — ${failures.first()}")
                                        }
                                    }
                                    pendingMoveResult = baseMsg to sourcesToDelete
                                    moveDeleteLauncher.launch(IntentSenderRequest.Builder(request).build())
                                    return@launch
                                }
                            }
                            // API < 30 or request creation failed: delete directly
                            FileOperationHelper.deleteMediaDirect(context, sourcesToDelete)
                        }
                    }

                    val label = if (operation == "copy") "Copied" else "Moved"
                    copyMoveMessage = buildString {
                        append("$label $successCount/${uris.size} images to $folderLabel")
                        if (failures.isNotEmpty()) {
                            append("\n${failures.size} failed — ${failures.first()}")
                        }
                    }
                    snackbarHostState.showSnackbar(copyMoveMessage)
                    isMultiSelectMode = false
                    selectedImageUris = emptySet()
                    if (operation == "move") {
                        viewModel.refreshImages()
                    }
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
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (e: Exception) {
                // Some providers reject persistable permissions — the URI still
                // works for writes within this session.
                Log.w("FolderPicker", "takePersistableUriPermission failed", e)
            }
            // The SAF tree URI is the source of truth for writes; the real File
            // path is kept only when resolution succeeds (internal storage).
            selectedTreeUri = treeUri
            targetFolderForOperation = resolveTreeUriToPath(treeUri)
            val op = pendingOperation
            if (op != null) {
                // Standard media collections (Download, Pictures, DCIM, Movies) use
                // MediaStore insert with RELATIVE_PATH — zero permission needed, so
                // skip the All-files-access dialog entirely for those folders.
                val needFullAccess = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                        !android.os.Environment.isExternalStorageManager() &&
                        !FileOperationHelper.isStandardMediaCollection(targetFolderForOperation) &&
                        !FileOperationHelper.isStandardMediaCollectionFromTreeUri(selectedTreeUri)
                if (needFullAccess) {
                    // Keep pendingOperation so the copy/move resumes after the
                    // user decides in the dialog — either from the Settings page
                    // (permission granted → direct-path write, works everywhere)
                    // or via "Later" → SAF as before.
                    showFullAccessDialog = true
                } else {
                    pendingOperation = null
                    launchCopyMove(op, selectedImageUris.toList(), targetFolderForOperation, selectedTreeUri)
                }
            }
        }
    }

    // Opens the "All files access" settings page; on return the pending copy/move
    // runs automatically (with the permission it uses direct paths like a file
    // manager; without it, it falls back to SAF).
    val fullAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        pendingOperation?.let { op ->
            pendingOperation = null
            launchCopyMove(op, selectedImageUris.toList(), targetFolderForOperation, selectedTreeUri)
        }
    }

    /** Runs the pending copy/move now — used when the user declines All files access. */
    val continueCopyMoveWithoutFullAccess: () -> Unit = {
        showFullAccessDialog = false
        pendingOperation?.let { op ->
            pendingOperation = null
            launchCopyMove(op, selectedImageUris.toList(), targetFolderForOperation, selectedTreeUri)
        }
    }

    // Load AI selected URIs
    LaunchedEffect(Unit) {
        aiSelectedUris = loadAiSelectedUris(context)
    }

    // System delete confirmation launcher (API 30+) — one dialog for the whole batch
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uris = selectedImageUris.toList()
            viewModel.deleteImages(uris)
            scope.launch { snackbarHostState.showSnackbar("Deleted ${uris.size} image(s)") }
            isMultiSelectMode = false
            selectedImageUris = emptySet()
        }
    }

    /** Kick off the system delete flow for the current selection (API 30+) or delete directly (older). */
    val performDelete: () -> Unit = {
        val uris = selectedImageUris.toList()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val request = FileOperationHelper.createDeleteRequest(context, uris)
            if (request != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(request).build())
            } else {
                scope.launch {
                    val n = FileOperationHelper.deleteMediaDirect(context, uris)
                    viewModel.deleteImages(uris)
                    snackbarHostState.showSnackbar("Deleted $n/${uris.size} image(s)")
                    isMultiSelectMode = false
                    selectedImageUris = emptySet()
                }
            }
        } else {
            scope.launch {
                val n = FileOperationHelper.deleteMediaDirect(context, uris)
                viewModel.deleteImages(uris)
                snackbarHostState.showSnackbar("Deleted $n/${uris.size} image(s)")
                isMultiSelectMode = false
                selectedImageUris = emptySet()
            }
        }
    }

    // Collage creation state
    var isCreatingCollage by remember { mutableStateOf(false) }
    var showCollageDialog by remember { mutableStateOf(false) }
    var collageColumns by remember { mutableIntStateOf(3) }
    var collageBgColor by remember { mutableIntStateOf(android.graphics.Color.WHITE) }
    var collageImageOrder by remember { mutableStateOf<List<String>>(emptyList()) }

    /** Open the collage options dialog (background color / columns / image order). */
    val openCollageDialog: () -> Unit = {
        val uris = selectedImageUris.toList()
        if (uris.size < 2) {
            scope.launch { snackbarHostState.showSnackbar("Select at least 2 images to make a collage") }
        } else if (!isCreatingCollage) {
            collageImageOrder = uris
            collageColumns = minOf(3, uris.size)
            showCollageDialog = true
        }
    }

    /** Build a collage from [uris] (already in the user's chosen order); tag the
     *  input images + the collage output with a unique tag so the whole set can
     *  be found and edited together. */
    val buildCollage: (List<String>) -> Unit = { orderedUris ->
        isCreatingCollage = true
        showCollageDialog = false
        scope.launch {
            try {
                val tagName = "Collage " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val fileName = "collage_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".jpg"

                // Create the unique tag if it does not exist yet
                var tag = tagDao.getTagByName(tagName)
                var tagId = if (tag != null) tag.id else tagDao.insertTag(TagEntity(name = tagName))
                if (tagId == -1L) {
                    tag = tagDao.getTagByName(tagName)
                    tagId = tag?.id ?: -1L
                }
                if (tagId == -1L) {
                    snackbarHostState.showSnackbar("Failed to create the collage tag")
                    return@launch
                }

                // Build the collage grid and save it to Pictures/PYQAlbum/
                val bitmap = withContext(Dispatchers.IO) {
                    ImageUtil.createCollage(
                        context = context,
                        sourceUris = orderedUris,
                        columns = collageColumns,
                        backgroundColor = collageBgColor
                    )
                }
                if (bitmap == null) {
                    snackbarHostState.showSnackbar("Failed to create collage — could not decode the selected images")
                    return@launch
                }
                val collageUri = withContext(Dispatchers.IO) { ImageUtil.saveCollageBitmap(context, bitmap, fileName) }
                if (collageUri == null) {
                    snackbarHostState.showSnackbar("Failed to save the collage image")
                    return@launch
                }

                // Register the collage in the library (folder PYQAlbum) so it shows up immediately
                val imageDao = app.database.imageDao()
                imageDao.insertImage(
                    ImageEntity(
                        uri = collageUri,
                        displayName = fileName,
                        width = bitmap.width,
                        height = bitmap.height,
                        sizeBytes = bitmap.byteCount.toLong(),
                        dateAdded = System.currentTimeMillis() / 1000,
                        folderName = "PYQAlbum"
                    )
                )

                // Tag every original input + the collage output with the unique tag
                tagDao.addTagToImages((orderedUris + collageUri).map { uri ->
                    ImageTagCrossRef(imageUri = uri, tagId = tagId)
                })

                snackbarHostState.showSnackbar("Collage saved & tagged \"$tagName\"")
                isMultiSelectMode = false
                selectedImageUris = emptySet()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Collage failed: ${e.message}")
            } finally {
                isCreatingCollage = false
            }
        }
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

    // Restore persisted sort mode on first load
    LaunchedEffect(Unit) {
        folderSortMode = viewModel.getPersistedFolderSortMode()
        groupByMode = viewModel.getPersistedGroupByMode()
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
        viewModel.persistFolderSortMode(folderSortMode)
    }

    // Persist groupByMode changes
    LaunchedEffect(groupByMode) {
        viewModel.persistGroupByMode(groupByMode)
    }

    // Track scroll position for restore after image detail exit
    // rememberSaveable survives navigation away and back
    val gridState = rememberLazyGridState()
    val waterfallState = rememberLazyStaggeredGridState()
    val justifiedState = rememberLazyListState()

    // Saved positions per view layout type
    var savedGridIndex by rememberSaveable { mutableIntStateOf(-1) }
    var savedGridOffset by rememberSaveable { mutableIntStateOf(0) }
    var savedWaterfallIndex by rememberSaveable { mutableIntStateOf(-1) }
    var savedWaterfallOffset by rememberSaveable { mutableIntStateOf(0) }
    var savedJustifiedIndex by rememberSaveable { mutableIntStateOf(-1) }
    var savedJustifiedOffset by rememberSaveable { mutableIntStateOf(0) }

    // Restore saved scroll position when returning from detail view
    LaunchedEffect(savedGridIndex) {
        if (savedGridIndex >= 0) {
            gridState.scrollToItem(savedGridIndex, savedGridOffset)
            savedGridIndex = -1
        }
    }
    LaunchedEffect(savedWaterfallIndex) {
        if (savedWaterfallIndex >= 0) {
            waterfallState.scrollToItem(savedWaterfallIndex, savedWaterfallOffset)
            savedWaterfallIndex = -1
        }
    }
    LaunchedEffect(savedJustifiedIndex) {
        if (savedJustifiedIndex >= 0) {
            justifiedState.scrollToItem(savedJustifiedIndex, savedJustifiedOffset)
            savedJustifiedIndex = -1
        }
    }

    // Long-press directly enters multi-select mode (no popup dialog)

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
                    // Images selectable in the current browse context (for Select All)
                    val contextImages: List<ImageItem> = when {
                        selectedBrowseMode == BrowseMode.FOLDER && selectedFolder != null -> images
                        selectedBrowseMode == BrowseMode.TAG && selectedTagName != null -> tagImages
                        else -> emptyList()
                    }
                    val contextUris = contextImages.map { it.uri }
                    val allContextSelected = contextUris.isNotEmpty() && contextUris.all { it in selectedImageUris }
                    BatchMultiSelectBar(
                        selectedCount = selectedImageUris.size,
                        allContextSelected = allContextSelected,
                        onCancel = {
                            isMultiSelectMode = false
                            selectedImageUris = emptySet()
                        },
                        onSelectAll = {
                            selectedImageUris = if (allContextSelected) emptySet() else selectedImageUris + contextUris.toSet()
                        },
                        onCollage = { openCollageDialog() },
                        onAddTag = {
                            showTagDialog = true
                        },
                        onRate = {
                            showRateDialog = true
                        },
                        onRemoveTags = {
                            if (selectedImageUris.isNotEmpty()) {
                                showRemoveTagsDialog = true
                            }
                        },
                        onSelectForAi = {
                            // Toggle: remove already-selected URIs, add the rest
                            val alreadySelected = selectedImageUris.intersect(aiSelectedUris)
                            val newlySelected = selectedImageUris - aiSelectedUris
                            if (newlySelected.isEmpty() && alreadySelected.isNotEmpty()) {
                                // All selected images are already AI-selected → deselect them
                                aiSelectedUris = aiSelectedUris - alreadySelected
                            } else {
                                // Add newly selected, keep existing ones
                                aiSelectedUris = aiSelectedUris + newlySelected
                            }
                            saveAiSelectedUris(context, aiSelectedUris)
                        },
                        onRemoveAiInfo = {
                            val uris = selectedImageUris.toList()
                            viewModel.batchRemoveAiInfo(uris)
                            isMultiSelectMode = false
                            selectedImageUris = emptySet()
                        },
                        onCopyTo = {
                            destinationDialogOp = "copy"
                            folderBrowserDir = android.os.Environment.getExternalStorageDirectory()
                            showDestinationDialog = true
                        },
                        onMoveTo = {
                            destinationDialogOp = "move"
                            folderBrowserDir = android.os.Environment.getExternalStorageDirectory()
                            showDestinationDialog = true
                        },
                        onDelete = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                performDelete()
                            } else {
                                showDeleteDialog = true
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
                                        gridState = gridState,
                                        waterfallState = waterfallState,
                                        justifiedState = justifiedState,
                                        onImageClick = { uri, _ ->
                                            if (isMultiSelectMode) {
                                                selectedImageUris = if (uri in selectedImageUris)
                                                    selectedImageUris - uri
                                                else
                                                    selectedImageUris + uri
                                            } else {
                                                // Save scroll position per view layout type
                                                when (selectedViewLayout) {
                                                    ViewLayout.GRID -> {
                                                        savedGridIndex = gridState.firstVisibleItemIndex
                                                        savedGridOffset = gridState.firstVisibleItemScrollOffset
                                                    }
                                                    ViewLayout.WATERFALL -> {
                                                        savedWaterfallIndex = waterfallState.firstVisibleItemIndex
                                                        savedWaterfallOffset = waterfallState.firstVisibleItemScrollOffset
                                                    }
                                                    ViewLayout.JUSTIFIED -> {
                                                        savedJustifiedIndex = justifiedState.firstVisibleItemIndex
                                                        savedJustifiedOffset = justifiedState.firstVisibleItemScrollOffset
                                                    }
                                                }
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
                                                // Enter multi-select mode
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
                                            val isSelected = image.uri in selectedImageUris
                                            Box(
                                                modifier = Modifier
                                                    .aspectRatio(1f)
                                                    .combinedClickable(
                                                        onClick = {
                                                            if (isMultiSelectMode) {
                                                                selectedImageUris = if (isSelected)
                                                                    selectedImageUris - image.uri
                                                                else
                                                                    selectedImageUris + image.uri
                                                            } else {
                                                                onImageClick(image.uri, tagImageUris)
                                                            }
                                                        },
                                                        onLongClick = {
                                                            // Enter multi-select mode
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
                                                    rating = image.rating,
                                                    aiScore = image.aiScore
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
                                                // Multi-select blue tick indicator (same as ImageGridCell)
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
                                    }
                                }
                            }
                        }
                    }


                }
            }

            // --- Batch operation dialogs ---

            // In-app folder browser for copy/move — browse all directories under
            // external storage. Preset folders at the top for quick access; tap any
            // directory to descend, use ".." to navigate up, or pick the current
            // directory with "Select folder". "Choose folder (system picker)…"
            // opens the SAF tree picker for locations not reachable via File API.
            if (showDestinationDialog) {
                val isCopy = destinationDialogOp == "copy"
                val isRoot = folderBrowserDir == android.os.Environment.getExternalStorageDirectory()

                val subDirs = remember(folderBrowserDir) {
                    folderBrowserDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                        ?.sortedBy { it.name.lowercase() }
                        ?: emptyList()
                }

                AlertDialog(
                    onDismissRequest = { showDestinationDialog = false },
                    title = { Text(if (isCopy) "Copy to..." else "Move to...") },
                    text = {
                        Column {
                            // Current path breadcrumb
                            Text(
                                text = folderBrowserDir.absolutePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Scrollable directory list
                            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                                // ".." parent navigation (hidden at external storage root)
                                if (!isRoot) {
                                    item {
                                        TextButton(
                                            onClick = { folderBrowserDir = folderBrowserDir.parentFile!! },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                Icons.Default.ArrowUpward,
                                                contentDescription = "Parent folder",
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("..", modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                }

                                items(subDirs.size) { i ->
                                    val dir = subDirs[i]
                                    TextButton(
                                        onClick = { folderBrowserDir = dir },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(dir.name, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // SAF fallback for non-standard locations
                            TextButton(
                                onClick = {
                                    showDestinationDialog = false
                                    pendingOperation = destinationDialogOp
                                    folderPickerLauncher.launch(null)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Choose folder (system picker)…",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDestinationDialog = false
                            // Non-standard folders (outside Download/Pictures/DCIM/Movies)
                            // need "All files access" on Android 11+ to write via File I/O.
                            // Without it, FileOutputStream throws EACCES — same as the SAF
                            // picker flow, which also shows this dialog for non-standard folders.
                            val needFullAccess = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                                    !android.os.Environment.isExternalStorageManager() &&
                                    !FileOperationHelper.isStandardMediaCollection(folderBrowserDir)
                            if (needFullAccess) {
                                pendingOperation = destinationDialogOp
                                targetFolderForOperation = folderBrowserDir
                                selectedTreeUri = null
                                showFullAccessDialog = true
                            } else {
                                launchCopyMove(
                                    destinationDialogOp,
                                    selectedImageUris.toList(),
                                    folderBrowserDir,
                                    null
                                )
                            }
                        }) {
                            Text("Select folder")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDestinationDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // All files access offer (API 30+) — shown after picking a copy/move
            // destination when the app still lacks the file-manager-style permission.
            // With it, copies/moves write via direct paths into ANY folder; without
            // it the app keeps using the SAF per-folder grant.
            if (showFullAccessDialog) {
                AlertDialog(
                    onDismissRequest = { continueCopyMoveWithoutFullAccess() },
                    title = { Text("Copy/Move to any folder?") },
                    text = {
                        Text(
                            "The chosen folder sits outside Android's media folders, which " +
                            "normal apps cannot write to directly. Granting \"All files access\" " +
                            "(the same permission your file manager has) lets the app copy/move " +
                            "into ANY folder on this phone, exactly like a file manager.\n\n" +
                            "Android will open a settings screen — enable the switch there, then " +
                            "the copy/move starts automatically."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showFullAccessDialog = false
                            try {
                                fullAccessLauncher.launch(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            } catch (e: Exception) {
                                // No such settings screen on this device — just proceed
                                continueCopyMoveWithoutFullAccess()
                            }
                        }) { Text("Grant access") }
                    },
                    dismissButton = {
                        TextButton(onClick = { continueCopyMoveWithoutFullAccess() }) { Text("Later") }
                    }
                )
            }

            // Add Tag dialog
            if (showTagDialog) {
                var tagInput by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showTagDialog = false },
                    title = { Text("Add Tag to ${selectedImageUris.size} image(s)") },
                    text = {
                        Column {
                            Text("Choose existing tag or create a new one:")
                            Spacer(Modifier.height(8.dp))
                            if (allTags.isNotEmpty()) {
                                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                    items(allTags) { tag ->
                                        OutlinedButton(
                                            onClick = {
                                                selectedImageUris.forEach { uri ->
                                                    viewModel.addTagToImage(uri, tag.name)
                                                }
                                                showTagDialog = false
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                        ) { Text(tag.name) }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
                            }
                            OutlinedTextField(
                                value = tagInput,
                                onValueChange = { tagInput = it },
                                label = { Text("New tag name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (tagInput.isNotBlank()) {
                                selectedImageUris.forEach { uri ->
                                    viewModel.addTagToImage(uri, tagInput.trim())
                                }
                            }
                            showTagDialog = false
                        }) { Text("Add") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTagDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // Rate dialog
            if (showRateDialog) {
                var batchRating by remember { mutableFloatStateOf(0f) }
                AlertDialog(
                    onDismissRequest = { showRateDialog = false },
                    title = { Text("Rate ${selectedImageUris.size} image(s)") },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Select a rating for all selected images:")
                            Spacer(Modifier.height(8.dp))
                            RatingBar(rating = batchRating, onRatingChange = { batchRating = it })
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = String.format("%.1f stars", batchRating),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            selectedImageUris.forEach { uri ->
                                viewModel.updateRating(uri, batchRating)
                            }
                            showRateDialog = false
                        }) { Text("Assign") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRateDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // Remove Tags dialog — only tags actually present on the selected images
            if (showRemoveTagsDialog) {
                var tagToRemove by remember { mutableStateOf<TagEntity?>(null) }
                // Reload the tags of the current selection whenever the dialog opens
                LaunchedEffect(showRemoveTagsDialog, selectedImageUris) {
                    selectedImageTags = tagDao.getTagsForImages(selectedImageUris.toList())
                }
                AlertDialog(
                    onDismissRequest = { showRemoveTagsDialog = false },
                    title = { Text("Remove Tags from ${selectedImageUris.size} image(s)") },
                    text = {
                        Column {
                            Text("Select a tag to remove from all selected images:")
                            Spacer(Modifier.height(8.dp))
                            if (selectedImageTags.isNotEmpty()) {
                                LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                                    items(selectedImageTags, key = { it.id }) { tag ->
                                        OutlinedButton(
                                            onClick = { tagToRemove = tag },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(selected = tagToRemove == tag, onClick = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text(tag.name)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text("No tags on the selected images.")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                tagToRemove?.let { tag ->
                                    viewModel.batchRemoveTagsFromImages(selectedImageUris.toList(), tag.name)
                                }
                                showRemoveTagsDialog = false
                            },
                            enabled = tagToRemove != null
                        ) { Text("Remove") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRemoveTagsDialog = false }) { Text("Cancel") }
                    }
                )
            }
            // Collage options dialog — background color / columns / image order
            if (showCollageDialog) {
                CollageOptionsDialog(
                    imageUris = collageImageOrder,
                    allImages = images,
                    columns = collageColumns,
                    onColumnsChange = { collageColumns = it },
                    bgColor = collageBgColor,
                    onBgColorChange = { collageBgColor = it },
                    order = collageImageOrder,
                    onOrderChange = { collageImageOrder = it },
                    onConfirm = { buildCollage(collageImageOrder) },
                    onDismiss = { showCollageDialog = false }
                )
            }
            // Delete confirmation dialog (Android 9 and below — no system delete dialog)
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text("Delete ${selectedImageUris.size} image(s)?") },
                    text = { Text("This permanently deletes the selected images from your device. This cannot be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteDialog = false
                            performDelete()
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}
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
    allContextSelected: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onCollage: () -> Unit,
    onAddTag: () -> Unit,
    onRate: () -> Unit,
    onRemoveTags: () -> Unit,
    onSelectForAi: () -> Unit,
    onRemoveAiInfo: () -> Unit,
    onCopyTo: () -> Unit,
    onMoveTo: () -> Unit,
    onDelete: () -> Unit
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
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .navigationBarsPadding()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Small action button — left side
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        onClick = { showMenu = false; onSelectAll() },
                        text = { Text(if (allContextSelected) "Deselect All" else "Select All") },
                        leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        onClick = { showMenu = false; onCollage() },
                        text = { Text("Collage") },
                        leadingIcon = { Icon(Icons.Default.GridOn, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    HorizontalDivider()
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
                        text = { Text("AI Ranking") },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        onClick = { showMenu = false; onRemoveAiInfo() },
                        text = { Text("Remove AI Info") },
                        leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp)) }
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
                    HorizontalDivider()
                    DropdownMenuItem(
                        onClick = { showMenu = false; onDelete() },
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }

            // Selected count — centered
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally)
            )

            // Cancel button — right side
            TextButton(onClick = onCancel, modifier = Modifier.height(34.dp)) {
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
    gridState: LazyGridState = rememberLazyGridState(),
    waterfallState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    justifiedState: LazyListState = rememberLazyListState(),
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
                    state = gridState,
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
                    state = gridState,
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
                state = waterfallState,
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
                state = justifiedState,
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
            rating = image.rating,
            aiScore = image.aiScore
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

// ---------- Collage options ----------

/** Preset background colors offered in the collage options dialog. */
private val CollageBgPresets = listOf(
    "White" to Color.White,
    "Black" to Color.Black,
    "Light Gray" to Color(0xFFE0E0E0),
    "Beige" to Color(0xFFF5F5DC),
    "Light Blue" to Color(0xFFBBDEFB),
    "Pink" to Color(0xFFF8BBD0)
)

/**
 * Lets the user configure the collage before it is built:
 * background color for empty space, number of columns, and the image order.
 */
@Composable
private fun CollageOptionsDialog(
    imageUris: List<String>,
    allImages: List<ImageItem>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    bgColor: Int,
    onBgColorChange: (Int) -> Unit,
    order: List<String>,
    onOrderChange: (List<String>) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collage Options (${imageUris.size} images)") },
        text = {
            Column {
                // Background color for empty space
                Text("Background color", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CollageBgPresets.forEach { (_, color) ->
                        val selected = color.toArgb() == bgColor
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { onBgColorChange(color.toArgb()) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Number of columns
                Text("Columns", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..5).forEach { c ->
                        FilterChip(
                            selected = columns == c,
                            onClick = { onColumnsChange(c) },
                            enabled = c <= imageUris.size,
                            label = { Text("$c") }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Image order
                Text("Image order", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Images are placed left-to-right, top-to-bottom in the order below — use ▲▼ to reorder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    itemsIndexed(order) { index, uri ->
                        val name = allImages.find { it.uri == uri }?.displayName
                            ?: uri.substringAfterLast('/')
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}.",
                                modifier = Modifier.width(28.dp),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onOrderChange(moveCollageItem(order, index, index - 1)) },
                                enabled = index > 0
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = "Move up",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onOrderChange(moveCollageItem(order, index, index + 1)) },
                                enabled = index < order.size - 1
                            ) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    contentDescription = "Move down",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Create Collage") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Return [list] with the element at [from] moved to [to] (bounds-safe). */
private fun moveCollageItem(list: List<String>, from: Int, to: Int): List<String> {
    if (from < 0 || to < 0 || from >= list.size || to >= list.size || from == to) return list
    val result = list.toMutableList()
    val item = result.removeAt(from)
    result.add(to, item)
    return result
}

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
            // Secondary volume (SD card): the docId volume label doubles as the
            // mount point (e.g. /storage/1C23-45AB). Direct-path I/O there only
            // works with "All files access" granted — otherwise the raw write
            // fails with EACCES and the SAF tree URI covers it (see launchCopyMove).
            val colon = docId.indexOf(':')
            if (colon > 0) File("/storage/${docId.substring(0, colon)}", docId.substring(colon + 1))
            else null
        }
    } catch (e: Exception) {
        null
    }
}

/** Human-readable name of the copy/move destination for the result snackbar. */
private fun destinationFolderLabel(destDir: File?, treeUri: Uri?): String {
    destDir?.let { return it.name }
    if (treeUri != null) {
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            docId.substringAfterLast('/').ifBlank { "selected folder" }
        } catch (e: Exception) {
            "selected folder"
        }
    }
    return "selected folder"
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