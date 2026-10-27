package com.pyqcr.ui.screen

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.gson.Gson
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail
import com.pyqcr.ui.component.MotionPhotoPlayer
import com.pyqcr.ui.component.VideoPlayer
import com.pyqcr.ui.util.FileOperationHelper
import com.pyqcr.util.MotionPhotoExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Photo Vault — a password-protected private area reached from the left menu.
 *
 * - Move images from the gallery into app-private storage (invisible to other
 *   apps and to the album's own MediaStore-backed views); the gallery
 *   originals go to the system recycle bin (API 30+).
 * - Optionally encrypt each entry with AES-256-GCM using a key derived from
 *   the vault password (PBKDF2) — without the password the file is useless.
 *
 * Layout:
 *   vault/
 *     <id>          plaintext entry (no extension — name lives in the meta)
 *     <id>.vault    encrypted entry (magic + salt + iv + ciphertext)
 *     <id>.meta     JSON: VaultMeta(name, encrypted, hidden, dateAdded)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }

    // Auth state
    var password by remember { mutableStateOf("") }
    var authed by remember { mutableStateOf(false) }

    // Vault state
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var galleryImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var galleryVideos by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }

    // Batch select state (folder grids)
    var vaultSelection by remember { mutableStateOf(setOf<String>()) }
    var vaultMultiSelect by remember { mutableStateOf(false) }

    // Import picker state
    var showImportPicker by remember { mutableStateOf(false) }
    var importSelection by remember { mutableStateOf(setOf<String>()) }
    // Folder filter for the import picker (null = all folders)
    var importFolderFilter by remember { mutableStateOf<String?>(null) }

    // Viewer state
    var viewingEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var viewPath by remember { mutableStateOf<String?>(null) }
    var viewError by remember { mutableStateOf<String?>(null) }

    // Password-prompt plumbing: an action (view/encrypt/decrypt/move-out) is
    // parked in [pendingAction] until a vault password is entered/created.
    var pendingAction by remember { mutableStateOf<Pair<VaultAction, VaultEntry>?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var enteredPassword by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var showCreatePasswordDialog by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var newPasswordConfirm by remember { mutableStateOf("") }

    // Motion-photo state for the viewer (extracted MP4 in cache)
    var motionVideoPath by remember { mutableStateOf<String?>(null) }
    var showMotionVideo by remember { mutableStateOf(false) }

    // Folder drill-down state: null = vault folder list, otherwise one folder
    var currentFolder by remember { mutableStateOf<String?>(null) }

    // Full-screen viewer state (pinch-zoom + pan, swipe-up actions panel)
    val viewingId = viewingEntry?.id
    var viewerScale by remember(viewingId) { mutableFloatStateOf(1f) }
    var viewerOffset by remember(viewingId) { mutableStateOf(Offset.Zero) }
    var viewerSize by remember(viewingId) { mutableStateOf(Size.Zero) }
    var showVaultActions by remember(viewingId) { mutableStateOf(false) }

    // Vault-wide password gate: the vault itself is locked (not just encrypted
    // entries), and the password can be changed from the top bar.
    var showUnlockDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var oldVaultPassword by remember { mutableStateOf("") }
    var newVaultPassword by remember { mutableStateOf("") }
    var newVaultPasswordConfirm by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    // Originals of moved images leave the gallery — recycle bin first (API 30+,
    // one system dialog for the whole batch), permanent delete on older APIs.
    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "Originals moved to the recycle bin", Toast.LENGTH_SHORT).show()
        }
    }

    /** Send [uris] (already copied into the vault) to the recycle bin. */
    val trashOriginals: (List<String>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            val trashRequest = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                FileOperationHelper.createTrashRequest(context, uris)
            } else {
                null
            }
            if (trashRequest != null) {
                trashLauncher.launch(IntentSenderRequest.Builder(trashRequest).build())
            } else {
                scope.launch {
                    val n = FileOperationHelper.deleteMediaDirect(context, uris)
                    Toast.makeText(context, "Deleted $n original image(s)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Refresh the entry list from disk whenever the vault is (re)opened
    fun refreshEntries() {
        entries = VaultStorage.listEntries(context)
    }

    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images ->
            galleryImages = images
        }
    }

    LaunchedEffect(Unit) {
        galleryVideos = withContext(Dispatchers.IO) {
            loadGalleryVideos(context)
        }
    }

    LaunchedEffect(Unit) {
        refreshEntries()
    }

    // Vault-wide password gate: the whole vault is locked behind the password
    // (first run asks you to create one, later runs ask for the existing one).
    LaunchedEffect(Unit) {
        if (!VaultStorage.hasPassword(context)) {
            newPassword = ""
            newPasswordConfirm = ""
            dialogError = null
            showCreatePasswordDialog = true
        } else {
            enteredPassword = ""
            dialogError = null
            showUnlockDialog = true
        }
    }

    /** Show the right password prompt for the current auth state. */
    val promptForAuth: () -> Unit = {
        if (!authed) {
            if (VaultStorage.hasPassword(context)) {
                enteredPassword = ""
                dialogError = null
                showUnlockDialog = true
            } else {
                newPassword = ""
                newPasswordConfirm = ""
                dialogError = null
                showCreatePasswordDialog = true
            }
        }
    }

    /** Close the full-screen viewer and reset its transform. */
    fun closeViewer() {
        viewingEntry = null
        viewPath = null
        viewError = null
        showVaultActions = false
        viewerScale = 1f
        viewerOffset = Offset.Zero
    }

    /**
     * Clamp a pan offset so the zoomed viewer image stays within the viewport
     * (same geometry as the folder-view full-screen image: at scale [s] the
     * displayed image is `intrinsic * fit * s`, so the maximum translation is
     * half the overflow per axis; the viewport size bounds it when the
     * intrinsic size is unknown).
     */
    fun clampViewerOffset(candidate: Offset, s: Float, size: Size): Offset {
        if (s <= 1f) return Offset.Zero
        val viewW = size.width
        val viewH = size.height
        if (viewW <= 0f || viewH <= 0f) return candidate
        val maxX = ((s - 1f) * viewW / 2f).coerceAtLeast(0f)
        val maxY = ((s - 1f) * viewH / 2f).coerceAtLeast(0f)
        return Offset(
            candidate.x.coerceIn(-maxX, maxX),
            candidate.y.coerceIn(-maxY, maxY)
        )
    }

    // Resolve the viewable path for [entry]: the plaintext path directly, or
    // the decrypted cache file when the entry is encrypted (needs [pwd]).
    val resolveViewPath: (VaultEntry, String?) -> String? = { entry, pwd ->
        if (entry.encrypted) {
            if (pwd.isNullOrEmpty()) null
            else VaultStorage.decryptToCache(context, entry, pwd)
        } else {
            VaultStorage.entryFile(context, entry).absolutePath
        }
    }

    /** Open the viewer for [entry]; encrypted entries prompt for the password. */
    val openViewer: (VaultEntry) -> Unit = { entry ->
        viewingEntry = entry
        viewPath = null
        viewError = null
        showVaultActions = false
        viewerScale = 1f
        viewerOffset = Offset.Zero
        if (entry.encrypted && !authed) {
            pendingAction = VaultAction.VIEW to entry
            enteredPassword = ""
            dialogError = null
            showPasswordDialog = true
        } else {
            scope.launch {
                viewPath = withContext(Dispatchers.IO) {
                    resolveViewPath(entry, password)
                }
                if (viewPath == null) {
                    viewError = "Wrong password or corrupted file"
                }
            }
        }
    }

    /** Move the viewer to the previous/next entry within the current folder. */
    val navigateViewer: (Int) -> Unit = { delta ->
        val current = viewingEntry
        if (current != null) {
            val list = entries.filter {
                it.folder.ifBlank { "Uncategorised" } == currentFolder
            }
            val idx = list.indexOfFirst { it.id == current.id }
            val next = list.getOrNull(idx + delta)
            if (next != null) {
                openViewer(next)
            }
        }
    }

    /** Execute [action] once a verified vault password is available. */
    val runVaultAction: (VaultAction, VaultEntry, String) -> Unit = { action, entry, pwd ->
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (action) {
                    VaultAction.VIEW -> resolveViewPath(entry, pwd)
                    VaultAction.ENCRYPT -> {
                        if (VaultStorage.encryptEntry(context, entry, pwd)) "encrypted" else null
                    }
                    VaultAction.DECRYPT -> {
                        if (VaultStorage.decryptEntry(context, entry, pwd)) "decrypted" else null
                    }
                    VaultAction.MOVE_OUT -> {
                        if (VaultStorage.moveOut(context, entry, pwd)) "moved" else null
                    }
                }
            }
            if (action == VaultAction.VIEW) {
                if (result == null) {
                    viewError = "Wrong password or corrupted file"
                } else {
                    viewPath = result
                }
            } else {
                viewingEntry = null
                viewPath = null
                viewError = null
                refreshEntries()
                Toast.makeText(
                    context,
                    when (result) {
                        "encrypted" -> "Encrypted — password required to view now"
                        "decrypted" -> "Decrypted — visible without a password"
                        "moved" -> "Moved back to its original folder"
                        else -> "Operation failed — wrong password?"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Run [action] for [entry], requesting the vault password when needed. */
    val requirePasswordOrRun: (VaultAction, VaultEntry) -> Unit = { action, entry ->
        if (action == VaultAction.ENCRYPT && !VaultStorage.hasPassword(context)) {
            // First encryption — the user picks the vault password now
            pendingAction = VaultAction.ENCRYPT to entry
            newPassword = ""
            newPasswordConfirm = ""
            dialogError = null
            showCreatePasswordDialog = true
        } else if (!authed) {
            pendingAction = action to entry
            enteredPassword = ""
            dialogError = null
            showPasswordDialog = true
        } else {
            runVaultAction(action, entry, password)
        }
    }

    /**
     * Run a batch encrypt/decrypt/move-out over the current vault selection
     * using the verified vault password (the vault is already unlocked when
     * the folder grids are visible).
     */
    val runVaultBatch: (VaultAction) -> Unit = { action ->
        val selected = entries.filter { it.id in vaultSelection }
        if (selected.isNotEmpty()) {
            scope.launch {
                var ok = 0
                for (entry in selected) {
                    val result = when (action) {
                        VaultAction.ENCRYPT -> if (!entry.encrypted) {
                            VaultStorage.encryptEntry(context, entry, password)
                        } else true
                        VaultAction.DECRYPT -> if (entry.encrypted) {
                            VaultStorage.decryptEntry(context, entry, password)
                        } else true
                        VaultAction.MOVE_OUT -> VaultStorage.moveOut(context, entry, password)
                        VaultAction.VIEW -> false
                    }
                    if (result) ok++
                }
                refreshEntries()
                vaultMultiSelect = false
                vaultSelection = emptySet()
                Toast.makeText(
                    context,
                    "$ok of ${selected.size} updated",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Permanently remove the vault selection (vault-local — no recycle bin). */
    val runVaultBatchDelete: () -> Unit = {
        val selected = entries.filter { it.id in vaultSelection }
        if (selected.isNotEmpty()) {
            scope.launch {
                var ok = 0
                for (entry in selected) {
                    try {
                        VaultStorage.deleteEntry(context, entry)
                        ok++
                    } catch (e: Exception) {
                    }
                }
                refreshEntries()
                vaultMultiSelect = false
                vaultSelection = emptySet()
                Toast.makeText(context, "Removed $ok from the vault", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentFolder ?: "Photo Vault") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentFolder != null) {
                            currentFolder = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (authed) {
                        IconButton(onClick = {
                            oldVaultPassword = ""
                            newVaultPassword = ""
                            newVaultPasswordConfirm = ""
                            dialogError = null
                            showChangePasswordDialog = true
                        }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Change vault password"
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!authed) {
                // Lock screen — the vault itself is password-protected
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Photo Vault is locked",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Enter your vault password to see the images",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = promptForAuth) {
                        Text("Unlock vault")
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Toolbar row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                showImportPicker = true
                                importSelection = emptySet()
                                importFolderFilter = null
                            },
                            enabled = !importing
                        ) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Move from Gallery")
                        }
                    }

                    val encryptedCount = entries.count { it.encrypted }

                    Text(
                        text = if (currentFolder == null) {
                            "${entries.size} in vault · $encryptedCount encrypted"
                        } else {
                            "${currentFolder ?: ""} · ${
                                entries.count { it.folder == currentFolder }
                            } images"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    if (entries.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No images in the vault yet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Move images from your gallery into this " +
                                        "password-protected private area. The gallery " +
                                        "originals go to the recycle bin.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                            }
                        }
                    } else if (currentFolder == null) {
                        // Vault folder list — mirrors the gallery's folder structure
                        val grouped = remember(entries) {
                            entries.groupBy { it.folder.ifBlank { "Uncategorised" } }
                        }
                        val folderNames = remember(grouped) { grouped.keys.sorted() }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color.White)
                        ) {
                            gridItems(folderNames, key = { it }) { folderName ->
                                FolderCell(
                                    folderName = folderName,
                                    previewEntry = grouped[folderName]
                                        ?.firstOrNull { !it.encrypted },
                                    count = grouped[folderName]?.size ?: 0,
                                    onTap = { currentFolder = folderName }
                                )
                            }
                        }
                    } else {
                        // Entries inside one folder
                        val folderEntries = remember(entries, currentFolder) {
                            entries.filter { it.folder.ifBlank { "Uncategorised" } == currentFolder }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color.White)
                        ) {
                            gridItems(folderEntries, key = { it.id }) { entry ->
                                val isSelected = entry.id in vaultSelection
                                VaultCell(
                                    entry = entry,
                                    previewPath = if (entry.encrypted) null
                                    else VaultStorage.entryFile(context, entry).absolutePath,
                                    selected = vaultMultiSelect && isSelected,
                                    onTap = {
                                        if (vaultMultiSelect) {
                                            vaultSelection = if (isSelected)
                                                vaultSelection - entry.id
                                            else
                                                vaultSelection + entry.id
                                        } else {
                                            openViewer(entry)
                                        }
                                    },
                                    onLongClick = {
                                        if (!vaultMultiSelect) {
                                            vaultMultiSelect = true
                                            vaultSelection = setOf(entry.id)
                                        }
                                    }
                                )
                            }
                        }
                        // Batch-select action bar (encrypt / decrypt / move out / delete)
                        if (vaultMultiSelect) {
                            VaultBatchBar(
                                selectedCount = vaultSelection.size,
                                onCancel = {
                                    vaultMultiSelect = false
                                    vaultSelection = emptySet()
                                },
                                onSelectAll = {
                                    vaultSelection = folderEntries.map { it.id }.toSet()
                                },
                                onEncrypt = { runVaultBatch(VaultAction.ENCRYPT) },
                                onDecrypt = { runVaultBatch(VaultAction.DECRYPT) },
                                onMoveOut = { runVaultBatch(VaultAction.MOVE_OUT) },
                                onDelete = { runVaultBatchDelete() }
                            )
                        }
                    }
                }
            }
        }
    }

    // ===================== Import picker dialog (full-screen, folder filter) =====================
    if (showImportPicker) {
        // Merged gallery list: images + videos (videos get a ▶ badge; both go
        // through the same copy-as-is import path).
        val pickerMedia = remember(galleryImages, galleryVideos) {
            galleryImages.map {
                GalleryMedia(it.uri, it.displayName, it.folderName, false)
            } + galleryVideos
        }
        // Distinct gallery folders for the filter row ("All folders" + each folder)
        val folders = remember(pickerMedia) {
            pickerMedia.map { it.folderName }
                .filter { !it.isNullOrBlank() }
                .distinct()
                .sorted()
        }
        val filteredMedia = remember(pickerMedia, importFolderFilter) {
            if (importFolderFilter == null) pickerMedia
            else pickerMedia.filter { it.folderName == importFolderFilter }
        }
        Dialog(
            onDismissRequest = { showImportPicker = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                // Draw edge-to-edge so the content receives real window insets —
                // only then can the bottom action row be padded above the system
                // navigation bar. Without this, some ROMs clip the "Move" button
                // behind the gesture bar.
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .imePadding()
                        .padding(8.dp)
                ) {
                    // Header
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Move to Photo Vault",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showImportPicker = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                    Text(
                        text = "Moved as-is into the password-protected vault (app-private " +
                            "storage, hidden from other gallery apps). The gallery originals " +
                            "go to the recycle bin. Encrypt later from the viewer if you want " +
                            "extra protection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    // Folder filter chips
                    if (folders.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = importFolderFilter == null,
                                    onClick = { importFolderFilter = null },
                                    label = { Text("All folders") }
                                )
                            }
                            items(folders) { folder ->
                                FilterChip(
                                    selected = importFolderFilter == folder,
                                    onClick = { importFolderFilter = folder },
                                    label = { Text(folder) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    // Grid fills the remaining screen height
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.Black)
                    ) {
                        gridItems(filteredMedia, key = { it.uri }) { media ->
                            val selected = media.uri in importSelection
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clickable {
                                        importSelection = if (selected)
                                            importSelection - media.uri
                                        else
                                            importSelection + media.uri
                                    }
                            ) {
                                if (media.isVideo) {
                                    // Videos can't be thumbnail-decoded by Coil —
                                    // dark tile with a play badge instead.
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF263238)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Video",
                                            tint = Color.White,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                } else {
                                    ImageThumbnail(
                                        imageUri = media.uri,
                                        modifier = Modifier.fillMaxSize(),
                                        backgroundColor = Color.Black
                                    )
                                }
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .border(3.dp, Color.Green)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0x40000000)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✓", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                    // Footer: selection count + actions. The fixed bottom padding is
                    // deliberately as large as the taller navigation bar (3-button
                    // style ~48dp): some ROMs never deliver window insets into the
                    // dialog, so systemBarsPadding() alone leaves the Move button
                    // clipped behind the navigation bar.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .padding(bottom = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${importSelection.size} selected",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showImportPicker = false }) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                importing = true
                                scope.launch {
                                    var ok = 0
                                    var fail = 0
                                    val movedUris = mutableListOf<String>()
                                    for (media in pickerMedia) {
                                        if (media.uri !in importSelection) continue
                                        val name = media.displayName.ifBlank {
                                            VaultStorage.galleryDisplayName(context, media.uri)
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            VaultStorage.importFromGallery(
                                                context = context,
                                                uri = media.uri,
                                                displayName = name,
                                                isVideo = media.isVideo
                                            )
                                        }
                                        if (result) {
                                            ok++
                                            movedUris.add(media.uri)
                                        } else {
                                            fail++
                                        }
                                    }
                                    importing = false
                                    showImportPicker = false
                                    refreshEntries()
                                    Toast.makeText(
                                        context,
                                        if (fail == 0) "Moved $ok item(s) to the vault"
                                        else "Moved $ok, $fail failed",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    // Originals leave the gallery — recycle bin first
                                    trashOriginals(movedUris)
                                }
                            },
                            enabled = importSelection.isNotEmpty() && !importing
                        ) {
                            Text(if (importing) "Moving…" else "Move")
                        }
                    }
                }
            }
        }
    }

    // Motion-photo detection: when a view path resolves, look for an embedded
    // MP4 (GIFs/animated WebP play directly through Coil, so no extraction;
    // standalone videos are played directly and never scanned).
    LaunchedEffect(viewPath) {
        motionVideoPath = null
        showMotionVideo = false
        if (viewingEntry?.isVideo == true) return@LaunchedEffect
        val path = viewPath
        if (path != null) {
            motionVideoPath = withContext(Dispatchers.IO) {
                MotionPhotoExtractor.extractToCache(
                    context, Uri.fromFile(File(path)).toString()
                )?.absolutePath
            }
        }
    }

    // ===================== Full-screen viewer (pinch-zoom, swipe-up actions) =====================
    viewingEntry?.let { entry ->
        Dialog(
            onDismissRequest = { closeViewer() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                ) {
                    // Top bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { closeViewer() }) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Text(
                            text = entry.displayName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (entry.encrypted) {
                            Text(
                                text = "🔒",
                                color = Color.White,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        }
                    }

                    // Image area: pinch to zoom, drag to pan while zoomed; at
                    // normal zoom a swipe up reveals the action panel below.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clipToBounds()
                            .onSizeChanged {
                                viewerSize = Size(it.width.toFloat(), it.height.toFloat())
                            }
                            .graphicsLayer {
                                scaleX = viewerScale
                                scaleY = viewerScale
                                translationX = viewerOffset.x
                                translationY = viewerOffset.y
                            }
                            .pointerInput(viewingEntry?.id) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var transformMode = viewerScale > 1f
                                    var verticalAccum = 0f
                                    var horizontalAccum = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (pressedCount >= 2) transformMode = true
                                        if (transformMode) {
                                            if (pressedCount >= 2) {
                                                val zoomChange = event.calculateZoom()
                                                val panChange = event.calculatePan()
                                                val newScale =
                                                    (viewerScale * zoomChange).coerceIn(1f, 5f)
                                                viewerScale = newScale
                                                viewerOffset = if (newScale > 1f) {
                                                    clampViewerOffset(
                                                        viewerOffset + panChange * newScale,
                                                        newScale,
                                                        viewerSize
                                                    )
                                                } else {
                                                    Offset.Zero
                                                }
                                            } else if (pressedCount == 1) {
                                                val single = event.changes.first { it.pressed }
                                                viewerOffset = clampViewerOffset(
                                                    viewerOffset + single.positionChange() * viewerScale,
                                                    viewerScale,
                                                    viewerSize
                                                )
                                            }
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        } else {
                                            val single = event.changes.firstOrNull { it.pressed }
                                                ?: continue
                                            if (single.positionChanged()) {
                                                verticalAccum += single.positionChange().y
                                                horizontalAccum += single.positionChange().x
                                                single.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    // Gesture ended at scale 1 — swipe up/down
                                    if (!transformMode) {
                                        if (verticalAccum < -80f) {
                                            showVaultActions = true
                                        } else if (verticalAccum > 80f) {
                                            showVaultActions = false
                                        } else if (horizontalAccum < -80f) {
                                            navigateViewer(1)
                                        } else if (horizontalAccum > 80f) {
                                            navigateViewer(-1)
                                        }
                                    }
                                }
                            }
                            .pointerInput(viewingEntry?.id) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (viewerScale > 1f) {
                                            viewerScale = 1f
                                            viewerOffset = Offset.Zero
                                        } else {
                                            viewerScale = 3f
                                            viewerOffset = Offset.Zero
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val currentPath = viewPath
                        if (currentPath != null) {
                            if (entry.isVideo) {
                                // Videos play directly (file path from the vault
                                // storage, or the decrypted cache copy).
                                VideoPlayer(data = currentPath, showCloseButton = false)
                            } else {
                                // GIF / animated WebP play directly through Coil;
                                // motion photos get a ▶ chip over the still frame.
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(currentPath)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = entry.displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                if (!showMotionVideo && motionVideoPath != null) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 8.dp)
                                            .background(
                                                Color(0x99000000),
                                                MaterialTheme.shapes.small
                                            )
                                            .clickable { showMotionVideo = true }
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Text("▶ Motion photo", color = Color.White)
                                    }
                                }
                                if (showMotionVideo && motionVideoPath != null) {
                                    MotionPhotoPlayer(
                                        videoPath = motionVideoPath!!,
                                        onClose = { showMotionVideo = false }
                                    )
                                }
                            }
                            if (!showVaultActions) {
                                Text(
                                    text = "Swipe: up actions · left/right browse",
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                )
                            }
                        } else if (viewError != null) {
                            Text(
                                text = viewError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }

                    // Swipe-up actions panel: encrypt / decrypt / move out / delete
                    if (showVaultActions) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xE6202020))
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .width(36.dp)
                                    .height(4.dp)
                                    .background(Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!entry.encrypted) {
                                    TextButton(onClick = {
                                        requirePasswordOrRun(VaultAction.ENCRYPT, entry)
                                    }) {
                                        Text("Encrypt", color = Color.White)
                                    }
                                } else {
                                    TextButton(onClick = {
                                        requirePasswordOrRun(VaultAction.DECRYPT, entry)
                                    }) {
                                        Text("Decrypt", color = Color.White)
                                    }
                                }
                                TextButton(onClick = {
                                    requirePasswordOrRun(VaultAction.MOVE_OUT, entry)
                                }) {
                                    Text("Move out", color = Color.White)
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { showVaultActions = false }) {
                                    Text("Hide", color = Color.White.copy(alpha = 0.7f))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    VaultStorage.deleteEntry(context, entry)
                                    refreshEntries()
                                    closeViewer()
                                    Toast.makeText(
                                        context,
                                        "Removed from vault",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (entry.encrypted) {
                                Text(
                                    text = "🔒 Encrypted — password required to view",
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ===================== Vault password prompt (existing password) =====================
    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showPasswordDialog = false
                pendingAction = null
            },
            title = { Text("Enter vault password") },
            text = {
                Column {
                    Text(
                        text = "This action is protected by the vault password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = enteredPassword,
                        onValueChange = {
                            enteredPassword = it
                            dialogError = null
                        },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    if (dialogError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = dialogError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val (action, entry) = pendingAction ?: return@TextButton
                    if (VaultStorage.verifyPassword(context, enteredPassword)) {
                        password = enteredPassword
                        authed = true
                        showPasswordDialog = false
                        pendingAction = null
                        runVaultAction(action, entry, enteredPassword)
                    } else {
                        dialogError = "Wrong password"
                    }
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPasswordDialog = false
                    pendingAction = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ===================== Create vault password (first Encrypt) =====================
    if (showCreatePasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreatePasswordDialog = false
                pendingAction = null
            },
            title = { Text("Set a vault password") },
            text = {
                Column {
                    Text(
                        text = "Encrypted entries are unreadable without this password. " +
                            "If you forget it, the encrypted images cannot be recovered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            dialogError = null
                        },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPasswordConfirm,
                        onValueChange = {
                            newPasswordConfirm = it
                            dialogError = null
                        },
                        label = { Text("Confirm password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    if (dialogError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = dialogError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        newPassword.length < 4 -> {
                            dialogError = "Password must be at least 4 characters"
                        }
                        newPassword != newPasswordConfirm -> {
                            dialogError = "Passwords do not match"
                        }
                        else -> {
                            VaultStorage.setPassword(context, newPassword)
                            password = newPassword
                            authed = true
                            showCreatePasswordDialog = false
                            dialogError = null
                            val pending = pendingAction
                            pendingAction = null
                            if (pending != null) {
                                runVaultAction(pending.first, pending.second, newPassword)
                            } else {
                                refreshEntries()
                            }
                        }
                    }
                }) {
                    Text("Set Password")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreatePasswordDialog = false
                    pendingAction = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ===================== Vault unlock gate (existing password) =====================
    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = { onBack() },
            title = { Text("Enter vault password") },
            text = {
                Column {
                    Text(
                        text = "Photo Vault is password-protected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = enteredPassword,
                        onValueChange = {
                            enteredPassword = it
                            dialogError = null
                        },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    if (dialogError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = dialogError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (VaultStorage.verifyPassword(context, enteredPassword)) {
                        password = enteredPassword
                        authed = true
                        showUnlockDialog = false
                        dialogError = null
                        refreshEntries()
                    } else {
                        dialogError = "Wrong password"
                    }
                }) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = { onBack() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ===================== Change vault password =====================
    if (showChangePasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showChangePasswordDialog = false
                dialogError = null
            },
            title = { Text("Change vault password") },
            text = {
                Column {
                    Text(
                        text = "Encrypted entries are re-encrypted with the new " +
                            "password automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = oldVaultPassword,
                        onValueChange = {
                            oldVaultPassword = it
                            dialogError = null
                        },
                        label = { Text("Current password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newVaultPassword,
                        onValueChange = {
                            newVaultPassword = it
                            dialogError = null
                        },
                        label = { Text("New password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newVaultPasswordConfirm,
                        onValueChange = {
                            newVaultPasswordConfirm = it
                            dialogError = null
                        },
                        label = { Text("Confirm new password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    if (dialogError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = dialogError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        oldVaultPassword.isBlank() -> {
                            dialogError = "Enter your current password"
                        }
                        newVaultPassword.length < 4 -> {
                            dialogError = "Password must be at least 4 characters"
                        }
                        newVaultPassword != newVaultPasswordConfirm -> {
                            dialogError = "Passwords do not match"
                        }
                        else -> {
                            val oldPwd = oldVaultPassword
                            val newPwd = newVaultPassword
                            showChangePasswordDialog = false
                            dialogError = null
                            scope.launch {
                                val reEncrypted = withContext(Dispatchers.IO) {
                                    VaultStorage.changePassword(context, oldPwd, newPwd)
                                }
                                when {
                                    reEncrypted == -1 -> {
                                        dialogError = "Wrong current password"
                                        showChangePasswordDialog = true
                                    }
                                    reEncrypted == -2 -> {
                                        dialogError = "Some encrypted entries could not be " +
                                            "re-encrypted — password unchanged"
                                        showChangePasswordDialog = true
                                    }
                                    else -> {
                                        password = newPwd
                                        Toast.makeText(
                                            context,
                                            if (reEncrypted == 0) {
                                                "Password changed — no encrypted entries"
                                            } else {
                                                "Password changed — $reEncrypted entry(ies) re-encrypted"
                                            },
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                }) {
                    Text("Change")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showChangePasswordDialog = false
                    dialogError = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** One tile in the vault grid. Encrypted entries show a lock instead of a preview. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultCell(
    entry: VaultEntry,
    previewPath: String?,
    selected: Boolean = false,
    onTap: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onTap, onLongClick = onLongClick)
            .background(Color.White)
    ) {
        if (previewPath != null) {
            if (entry.isVideo) {
                // Videos can't be thumbnail-decoded by Coil — play badge tile
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF263238)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                ImageThumbnail(
                    imageUri = previewPath,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.White
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF37474F)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Encrypted",
                    tint = Color(0xFFFFD740),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        // Selection overlay when batch-selecting
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x4000E676))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(3.dp, Color(0xFF00E676)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        // Name badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0xCC000000), RoundedCornerShape(2.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        ) {
            Text(
                text = entry.displayName,
                color = Color.White,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One folder tile in the vault's folder list (mirrors the gallery structure). */
@Composable
private fun FolderCell(
    folderName: String,
    previewEntry: VaultEntry?,
    count: Int,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onTap)
            .background(Color.White)
    ) {
        if (previewEntry != null) {
            if (previewEntry.isVideo) {
                // Videos can't be thumbnail-decoded by Coil — play badge tile
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF263238)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                ImageThumbnail(
                    imageUri = VaultStorage.entryFile(LocalContext.current, previewEntry).absolutePath,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.White
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF37474F)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Encrypted",
                    tint = Color(0xFFFFD740),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        // Folder name badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0xCC000000), RoundedCornerShape(2.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        ) {
            Text(
                text = folderName,
                color = Color.White,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Count badge
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color(0xCC000000), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = count.toString(),
                color = Color.White,
                fontSize = MaterialTheme.typography.labelSmall.fontSize
            )
        }
    }
}

/**
 * Bottom action bar shown while batch-selecting vault entries. Actions open
 * from a ⋮ menu (same pattern as the album's batch bar) to stay compact.
 */
@Composable
private fun VaultBatchBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onEncrypt: () -> Unit,
    onDecrypt: () -> Unit,
    onMoveOut: () -> Unit,
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
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel",
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            TextButton(onClick = onSelectAll) {
                Text("Select all")
            }
            // Actions menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Actions",
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        onClick = { showMenu = false; onEncrypt() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        text = { Text("Encrypt") }
                    )
                    DropdownMenuItem(
                        onClick = { showMenu = false; onDecrypt() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        text = { Text("Decrypt") }
                    )
                    DropdownMenuItem(
                        onClick = { showMenu = false; onMoveOut() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        text = { Text("Move out") }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    }
}

// ===================== Vault domain model =====================

/** What the user is trying to do when the vault password is requested. */
private enum class VaultAction { VIEW, ENCRYPT, DECRYPT, MOVE_OUT }

/** Lightweight entry description built from the on-disk metadata. */
data class VaultEntry(
    val id: String,
    val displayName: String,
    val folder: String,
    val relPath: String,
    val encrypted: Boolean,
    val hidden: Boolean,
    val dateAdded: Long,
    val isVideo: Boolean = false
)

/**
 * Per-entry metadata persisted as <id>.meta next to the data file.
 * [folder] is the source gallery folder name (BUCKET_DISPLAY_NAME) shown in
 * the vault's folder list; [relPath] is the source MediaStore RELATIVE_PATH
 * used to restore the entry to its original folder on move-out.
 */
data class VaultMeta(
    val name: String = "",
    val folder: String = "",
    val relPath: String = "",
    val encrypted: Boolean = false,
    val hidden: Boolean = false,
    val dateAdded: Long = 0L,
    val isVideo: Boolean = false
)

/** A gallery item (image or video) shown in the vault import picker. */
private data class GalleryMedia(
    val uri: String,
    val displayName: String,
    val folderName: String,
    val isVideo: Boolean
)

/** Non-trashed gallery videos for the vault import picker (newest first). */
private fun loadGalleryVideos(context: Context): List<GalleryMedia> {
    return try {
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "${MediaStore.MediaColumns.IS_TRASHED} = 0"
        } else {
            null
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val videos = mutableListOf<GalleryMedia>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                videos.add(
                    GalleryMedia(
                        uri = Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        ).toString(),
                        displayName = c.getString(nameCol) ?: "video_$id",
                        folderName = c.getString(folderCol) ?: "Unknown",
                        isVideo = true
                    )
                )
            }
        }
        videos
    } catch (e: Exception) {
        emptyList()
    }
}

// ===================== Crypto (PBKDF2 + AES-256-GCM) =====================

/**
 * Password hashing and file encryption.
 *
 * Files use a per-file random salt; the encryption key is derived from the
 * vault password with PBKDF2-HMAC-SHA256 (100k iterations, 256 bits).
 * Encrypted layout: "PYQVAULT1" + salt(16) + iv(12) + ciphertext.
 */
object VaultCrypto {

    private const val MAGIC = "PYQVAULT1"
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    /** Hash for password verification; also usable as a MAC-style check. */
    fun hashPassword(password: String, salt: ByteArray): ByteArray = deriveKey(password, salt)

    fun encryptBytes(bytes: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES")
        )
        val ciphertext = cipher.doFinal(bytes)
        val iv = cipher.iv
        val out = ByteArray(MAGIC.length + salt.size + iv.size + ciphertext.size)
        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        System.arraycopy(magicBytes, 0, out, 0, magicBytes.size)
        var p = magicBytes.size
        System.arraycopy(salt, 0, out, p, salt.size)
        p += salt.size
        System.arraycopy(iv, 0, out, p, iv.size)
        p += iv.size
        System.arraycopy(ciphertext, 0, out, p, ciphertext.size)
        return out
    }

    /** Decrypt; returns null on wrong password / tampered data. */
    fun decryptBytes(data: ByteArray, password: String): ByteArray? {
        return try {
            val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
            if (data.size < magicBytes.size + SALT_BYTES + IV_BYTES) return null
            if (!data.copyOfRange(0, magicBytes.size).contentEquals(magicBytes)) return null
            var p = magicBytes.size
            val salt = data.copyOfRange(p, p + SALT_BYTES)
            p += SALT_BYTES
            val iv = data.copyOfRange(p, p + IV_BYTES)
            p += IV_BYTES
            val ciphertext = data.copyOfRange(p, data.size)
            val key = deriveKey(password, salt)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(TAG_BITS, iv)
            )
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }
}

// ===================== Password storage (EncryptedSharedPreferences) =====================

private fun getVaultPrefs(context: Context): android.content.SharedPreferences? {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pyqcr_vault_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }
}

/** True once a vault password has been set. */
fun hasVaultPassword(context: Context): Boolean {
    return getVaultPrefs(context)?.getString("vault_auth", null) != null
}

/** Store base64(salt):base64(hash) of the password. */
fun setVaultPassword(context: Context, password: String) {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val hash = VaultCrypto.hashPassword(password, salt)
    val stored = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP) + ":" +
        android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
    try {
        getVaultPrefs(context)?.edit()?.putString("vault_auth", stored)?.apply()
    } catch (_: Exception) {
    }
}

/** Constant-time password check against the stored hash. */
fun verifyVaultPassword(context: Context, password: String): Boolean {
    val stored = getVaultPrefs(context)?.getString("vault_auth", null) ?: return false
    val parts = stored.split(":")
    if (parts.size != 2) return false
    return try {
        val salt = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
        val expected = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        val actual = VaultCrypto.hashPassword(password, salt)
        MessageDigest.isEqual(expected, actual)
    } catch (e: Exception) {
        false
    }
}

// ===================== Vault storage =====================

object VaultStorage {

    private val gson = Gson()

    fun vaultDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "vault")
        dir.mkdirs()
        return dir
    }

    fun entryFile(context: Context, entry: VaultEntry): File {
        val suffix = if (entry.encrypted) ".vault" else ""
        return File(vaultDir(context), entry.id + suffix)
    }

    fun hasPassword(context: Context): Boolean = hasVaultPassword(context)

    fun setPassword(context: Context, password: String) {
        setVaultPassword(context, password)
    }

    fun verifyPassword(context: Context, password: String): Boolean =
        verifyVaultPassword(context, password)

    /** List vault entries, newest first. Orphaned entries (missing data file) are skipped. */
    fun listEntries(context: Context): List<VaultEntry> {
        val dir = vaultDir(context)
        val metas = dir.listFiles { f -> f.isFile && f.name.endsWith(".meta") } ?: return emptyList()
        return metas.mapNotNull { metaFile ->
            val id = metaFile.name.removeSuffix(".meta")
            val meta = try {
                gson.fromJson(metaFile.readText(), VaultMeta::class.java)
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            val suffix = if (meta.encrypted) ".vault" else ""
            val dataFile = File(dir, id + suffix)
            if (!dataFile.exists()) return@mapNotNull null
            VaultEntry(
                id = id,
                displayName = meta.name,
                folder = meta.folder,
                relPath = meta.relPath,
                encrypted = meta.encrypted,
                hidden = meta.hidden,
                dateAdded = meta.dateAdded,
                isVideo = meta.isVideo
            )
        }.sortedByDescending { it.dateAdded }
    }

    /** Best-effort display name for a gallery content URI. */
    fun galleryDisplayName(context: Context, uri: String): String {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use { c ->
                if (c.moveToFirst()) c.getString(0)
                else uri.substringAfterLast('/')
            } ?: uri.substringAfterLast('/')
        } catch (e: Exception) {
            uri.substringAfterLast('/')
        }
    }

    /**
     * Copy [uri] from the gallery into the vault in its ORIGINAL form
     * (unencrypted — the vault's app-private storage already keeps it hidden
     * from other gallery apps). The source folder is recorded so the vault can
     * mirror the gallery's folder structure and move-out can restore the
     * original location. Works for images AND videos. Returns true on success.
     */
    fun importFromGallery(
        context: Context,
        uri: String,
        displayName: String,
        isVideo: Boolean = false
    ): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))
                ?.use { it.readBytes() } ?: return false
            if (bytes.isEmpty()) return false

            val (folder, relPath) = sourceLocation(context, uri)
            val id = "v" + System.currentTimeMillis() + "_" + (Math.random() * 100000).toInt()
            val dataFile = File(vaultDir(context), id).apply { writeBytes(bytes) }
            if (!dataFile.exists() || dataFile.length() == 0L) {
                dataFile.delete()
                return false
            }
            val meta = VaultMeta(
                name = displayName,
                folder = folder,
                relPath = relPath,
                encrypted = false,
                hidden = false,
                dateAdded = System.currentTimeMillis(),
                isVideo = isVideo
            )
            File(vaultDir(context), "$id.meta").writeText(gson.toJson(meta))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Original gallery location of a source image: the folder name shown in the
     * vault (BUCKET_DISPLAY_NAME) and the MediaStore RELATIVE_PATH the image
     * came from (used to restore the original folder on move-out). Falls back
     * to the old DATA-path form on devices/rows without RELATIVE_PATH.
     */
    private fun sourceLocation(context: Context, uri: String): Pair<String, String> {
        return try {
            var bucket: String? = null
            var relPath: String? = null
            var data: String? = null
            val projection = arrayOf(
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATA
            )
            context.contentResolver.query(Uri.parse(uri), projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val bIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val rIdx = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    val dIdx = c.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (bIdx >= 0) bucket = c.getString(bIdx)
                    if (rIdx >= 0) relPath = c.getString(rIdx)
                    if (dIdx >= 0) data = c.getString(dIdx)
                }
            }
            val folder = bucket ?: ""
            val rel = relPath?.takeIf { !it.isNullOrBlank() }
                ?: data?.let { d ->
                    val norm = d.removePrefix("/storage/emulated/0/")
                    val dirPart = norm.substringBeforeLast('/')
                    if (dirPart != norm && dirPart.isNotBlank()) dirPart + "/" else null
                }
            Pair(folder, rel ?: "")
        } catch (e: Exception) {
            Pair("", "")
        }
    }

    /**
     * Password-protect a plaintext entry in place: the bytes are AES-256-GCM
     * encrypted with a key derived from [password] and stored as "<id>.vault";
     * the plaintext copy is removed and the meta updated. Returns true on
     * success.
     */
    fun encryptEntry(context: Context, entry: VaultEntry, password: String): Boolean {
        return try {
            if (entry.encrypted || password.isEmpty()) return false
            val src = entryFile(context, entry)
            val bytes = src.readBytes()
            if (bytes.isEmpty()) return false
            val enc = VaultCrypto.encryptBytes(bytes, password)
            val vaultFile = File(vaultDir(context), "${entry.id}.vault")
            vaultFile.writeBytes(enc)
            if (!vaultFile.exists() || vaultFile.length() == 0L) {
                vaultFile.delete()
                return false
            }
            src.delete()
            updateMeta(context, entry.id) { it.copy(encrypted = true) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Remove the password protection of an encrypted entry in place: decrypt
     * with [password] back to the plaintext file and drop the ".vault" copy.
     * Returns true on success (false = wrong password / corrupted file).
     */
    fun decryptEntry(context: Context, entry: VaultEntry, password: String): Boolean {
        return try {
            if (!entry.encrypted) return false
            val vaultFile = entryFile(context, entry)
            val data = vaultFile.readBytes()
            val plain = VaultCrypto.decryptBytes(data, password) ?: return false
            val plainFile = File(vaultDir(context), entry.id).apply { writeBytes(plain) }
            if (!plainFile.exists() || plainFile.length() == 0L) {
                plainFile.delete()
                return false
            }
            vaultFile.delete()
            updateMeta(context, entry.id) { it.copy(encrypted = false) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Move an entry OUT of the vault back into the gallery — into its ORIGINAL
     * folder when that folder sits under a standard media root (Pictures /
     * DCIM / Movies / Download), otherwise Pictures/PYQAlbum. Zero extra
     * permission: it inserts through MediaStore and publishes the file.
     * A "Download/" RELATIVE_PATH is rejected on the Images/Video collections
     * by strict providers (e.g. HONOR Parallel Space's virtualised media
     * store), so a Download target additionally falls back to the Downloads
     * collection, and finally to Pictures/PYQAlbum, so the file always
     * escapes the vault instead of being stranded by a failed move.
     * Encrypted entries are decrypted with [password] in memory first. The
     * vault copy is removed on success.
     */
    fun moveOut(context: Context, entry: VaultEntry, password: String?): Boolean {
        val bytes = try {
            if (entry.encrypted) {
                if (password.isNullOrEmpty()) return false
                VaultCrypto.decryptBytes(entryFile(context, entry).readBytes(), password)
                    ?: return false
            } else {
                entryFile(context, entry).readBytes()
            }
        } catch (e: Exception) {
            return false
        }
        if (bytes.isEmpty()) return false

        val name = entry.displayName.ifBlank { "vault_image" }
        val mime = when {
            entry.isVideo -> when (name.substringAfterLast('.', "").lowercase()) {
                "webm" -> "video/webm"
                "3gp" -> "video/3gpp"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                else -> "video/mp4"
            }
            else -> when (name.substringAfterLast('.', "").lowercase()) {
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "png" -> "image/png"
                else -> "image/jpeg"
            }
        }
        val mediaCollection = if (entry.isVideo)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val rel = restoreRelativePath(entry.relPath)
        val toDownload = rel.trim('/').substringBefore('/').lowercase() == "download"
        var exported: Uri? = null
        exported = insertVaultOut(context, mediaCollection, name, mime, rel, true, bytes)
        if (exported == null && toDownload && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val downloadsCollection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // Direct write first (IS_PENDING on the Downloads collection is
            // unreliable on some devices), then the pending-publish variant.
            exported = insertVaultOut(context, downloadsCollection, name, mime, rel, false, bytes)
                ?: insertVaultOut(context, downloadsCollection, name, mime, rel, true, bytes)
        }
        if (exported == null && rel.removeSuffix("/") != "Pictures/PYQAlbum") {
            exported = insertVaultOut(context, mediaCollection, name, mime, "Pictures/PYQAlbum", true, bytes)
        }
        val doneUri = exported ?: return false
        return try {
            deleteEntry(context, entry)
            true
        } catch (e: Exception) {
            // Vault copy survived — drop the half-moved export so a retry
            // cannot stack up duplicate gallery files.
            try { context.contentResolver.delete(doneUri, null, null) } catch (_: Exception) {}
            false
        }
    }

    /**
     * Write [bytes] into [collection] as [name] ([mime]) under [relPath] and
     * return the new row's URI; [usePending] runs the IS_PENDING 1 → write →
     * publish cycle, while false writes straight into the freshly inserted
     * row. A half-written row is always removed before reporting failure
     * (null) so fallback retries do not stack up duplicates.
     */
    private fun insertVaultOut(
        context: Context,
        collection: Uri,
        name: String,
        mime: String,
        relPath: String,
        usePending: Boolean,
        bytes: ByteArray
    ): Uri? {
        var destUri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                if (usePending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            destUri = context.contentResolver.insert(collection, values) ?: return null
            val output = context.contentResolver.openOutputStream(destUri!!)
            if (output == null) {
                context.contentResolver.delete(destUri!!, null, null)
                return null
            }
            output.use { it.write(bytes) }
            if (usePending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val updated = context.contentResolver.update(destUri!!, publishValues, null, null)
                if (updated == 0) {
                    context.contentResolver.delete(destUri!!, null, null)
                    return null
                }
            }
            destUri
        } catch (e: Exception) {
            destUri?.let { uri ->
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            null
        }
    }

    /** MediaStore roots an image insert may target on API 29+ (Images volume). */
    private val RESTORABLE_ROOTS = setOf("Download", "Pictures", "DCIM", "Movies")

    /**
     * RELATIVE_PATH used when moving an entry out of the vault: the ORIGINAL
     * folder when it sits under a standard media root (Pictures/DCIM/Movies/
     * Download), otherwise Pictures/PYQAlbum — MediaStore inserts cannot create
     * files under non-standard roots without All-files access.
     */
    private fun restoreRelativePath(relPath: String): String {
        val trimmed = relPath.trim('/')
        if (trimmed.isBlank()) return "Pictures/PYQAlbum"
        val top = trimmed.substringBefore('/')
        return if (top in RESTORABLE_ROOTS) "$trimmed/" else "Pictures/PYQAlbum"
    }

    /**
     * Change the vault password. AES-GCM keys are derived from the password, so
     * every encrypted entry must be re-encrypted with the new password before
     * the stored hash is replaced. All re-encryption happens into temp files
     * FIRST — if any entry fails to re-encrypt the whole change is aborted and
     * the old password stays valid.
     *
     * @return number of entries re-encrypted; -1 wrong current password;
     *     -2 at least one encrypted entry could not be re-encrypted (aborted)
     */
    fun changePassword(context: Context, oldPassword: String, newPassword: String): Int {
        if (!verifyVaultPassword(context, oldPassword)) return -1
        if (newPassword.isBlank() || newPassword.length < 4) return -3
        val dir = vaultDir(context)
        val encryptedEntries = listEntries(context).filter { it.encrypted }
        val temps = mutableListOf<Pair<File, File>>()
        for (entry in encryptedEntries) {
            try {
                val finalFile = File(dir, "${entry.id}.vault")
                val data = finalFile.readBytes()
                val plain = VaultCrypto.decryptBytes(data, oldPassword) ?: return -2
                val reEnc = VaultCrypto.encryptBytes(plain, newPassword)
                val tempFile = File(dir, "${entry.id}.vault.tmp")
                tempFile.writeBytes(reEnc)
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    tempFile.delete()
                    for ((t, _) in temps) t.delete()
                    return -2
                }
                temps.add(tempFile to finalFile)
            } catch (e: Exception) {
                for ((t, _) in temps) t.delete()
                return -2
            }
        }
        // Phase 2: commit — temp files replace the originals in place
        for ((temp, finalFile) in temps) {
            if (!temp.renameTo(finalFile)) {
                for ((t, _) in temps) t.delete()
                return -2
            }
        }
        setVaultPassword(context, newPassword)
        return encryptedEntries.size
    }

    /** Rewrite one entry's meta file, applying [transform] to the stored meta. */
    private fun updateMeta(context: Context, id: String, transform: (VaultMeta) -> VaultMeta) {
        try {
            val metaFile = File(vaultDir(context), "$id.meta")
            val meta = try {
                gson.fromJson(metaFile.readText(), VaultMeta::class.java)
            } catch (e: Exception) {
                VaultMeta()
            }
            metaFile.writeText(gson.toJson(transform(meta)))
        } catch (_: Exception) {
        }
    }

    /** Decrypt an encrypted entry into a cache file for viewing. Null on failure. */
    fun decryptToCache(context: Context, entry: VaultEntry, password: String): String? {
        return try {
            val data = entryFile(context, entry).readBytes()
            val plain = VaultCrypto.decryptBytes(data, password) ?: return null
            val cacheDir = File(context.cacheDir, "vault_view").apply { mkdirs() }
            val outFile = File(cacheDir, "${entry.id}.jpg")
            outFile.writeBytes(plain)
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Hide (or unhide) an entry. */
    fun setHidden(context: Context, entry: VaultEntry, hidden: Boolean) {
        val metaFile = File(vaultDir(context), "${entry.id}.meta")
        val meta = try {
            gson.fromJson(metaFile.readText(), VaultMeta::class.java)
        } catch (e: Exception) {
            VaultMeta()
        }
        try {
            metaFile.writeText(gson.toJson(meta.copy(hidden = hidden)))
        } catch (_: Exception) {
        }
    }

    /** Remove an entry (data file + metadata). */
    fun deleteEntry(context: Context, entry: VaultEntry) {
        try {
            entryFile(context, entry).delete()
        } catch (_: Exception) {
        }
        try {
            File(vaultDir(context), "${entry.id}.meta").delete()
        } catch (_: Exception) {
        }
    }
}