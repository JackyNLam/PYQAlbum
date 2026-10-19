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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
    var importing by remember { mutableStateOf(false) }

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
        refreshEntries()
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
                        "moved" -> "Moved out of the vault to Pictures/PYQAlbum"
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            // The vault is a normal private gallery: unencrypted entries are
            // always visible and need no password. The vault password (created
            // on first Encrypt) only protects entries the user explicitly
            // encrypts — it is re-entered to view / decrypt / move them out.
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
                            text = "${entries.size} in vault · $encryptedCount encrypted",
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
                                            "private area (hidden from other apps — encrypt " +
                                            "later if you want). The gallery originals go to " +
                                            "the recycle bin.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 32.dp)
                                    )
                                }
                            }
                        } else {
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
                                gridItems(entries, key = { it.id }) { entry ->
                                    VaultCell(
                                        entry = entry,
                                        previewPath = if (entry.encrypted) null
                                        else VaultStorage.entryFile(context, entry).absolutePath,
                                        onTap = { openViewer(entry) }
                                    )
                                }
                            }
                        }
                    }
        }
    }

    // ===================== Import picker dialog (full-screen, folder filter) =====================
    if (showImportPicker) {
        // Distinct gallery folders for the filter row ("All folders" + each folder)
        val folders = remember(galleryImages) {
            galleryImages.map { it.folderName }
                .filter { !it.isNullOrBlank() }
                .distinct()
                .sorted()
        }
        val filteredImages = remember(galleryImages, importFolderFilter) {
            if (importFolderFilter == null) galleryImages
            else galleryImages.filter { it.folderName == importFolderFilter }
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
                        text = "Moved as-is into app-private storage — no password needed, " +
                            "and hidden from other gallery apps. The gallery originals go to " +
                            "the recycle bin. Encrypt later from the viewer if you want " +
                            "password protection.",
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
                        gridItems(filteredImages, key = { it.uri }) { image ->
                            val selected = image.uri in importSelection
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clickable {
                                        importSelection = if (selected)
                                            importSelection - image.uri
                                        else
                                            importSelection + image.uri
                                    }
                            ) {
                                ImageThumbnail(
                                    imageUri = image.uri,
                                    modifier = Modifier.fillMaxSize(),
                                    backgroundColor = Color.Black
                                )
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
                    // Footer: selection count + actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .padding(bottom = 8.dp),
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
                                    for (uri in importSelection) {
                                        val name = VaultStorage.galleryDisplayName(context, uri)
                                        val result = withContext(Dispatchers.IO) {
                                            VaultStorage.importFromGallery(
                                                context = context,
                                                uri = uri,
                                                displayName = name
                                            )
                                        }
                                        if (result) {
                                            ok++
                                            movedUris.add(uri)
                                        } else {
                                            fail++
                                        }
                                    }
                                    importing = false
                                    showImportPicker = false
                                    refreshEntries()
                                    Toast.makeText(
                                        context,
                                        if (fail == 0) "Moved $ok image(s) to the vault"
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
    // MP4 (GIFs/animated WebP play directly through Coil, so no extraction).
    LaunchedEffect(viewPath) {
        motionVideoPath = null
        showMotionVideo = false
        val path = viewPath
        if (path != null) {
            motionVideoPath = withContext(Dispatchers.IO) {
                MotionPhotoExtractor.extractToCache(
                    context, Uri.fromFile(File(path)).toString()
                )?.absolutePath
            }
        }
    }

    // ===================== Viewer dialog =====================
    viewingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = {
                viewingEntry = null
                viewPath = null
                viewError = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val currentPath = viewPath
                    if (currentPath != null) {
                        // GIF / animated WebP play directly through Coil; motion
                        // photos get a ▶ chip that overlays the embedded video.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
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
                                        .background(Color(0x99000000), MaterialTheme.shapes.small)
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
                    } else if (viewError != null) {
                        Text(
                            text = viewError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                    Spacer(Modifier.height(8.dp))
                    if (entry.encrypted) {
                        Text(
                            text = "🔒 Encrypted",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!entry.encrypted) {
                        // Password-protect this entry (first Encrypt sets the password)
                        TextButton(onClick = {
                            requirePasswordOrRun(VaultAction.ENCRYPT, entry)
                        }) {
                            Text("Encrypt")
                        }
                    } else {
                        // Remove the password protection (needs the vault password)
                        TextButton(onClick = {
                            requirePasswordOrRun(VaultAction.DECRYPT, entry)
                        }) {
                            Text("Decrypt")
                        }
                    }
                    TextButton(onClick = {
                        requirePasswordOrRun(VaultAction.MOVE_OUT, entry)
                    }) {
                        Text("Move out")
                    }
                    TextButton(
                        onClick = {
                            VaultStorage.deleteEntry(context, entry)
                            refreshEntries()
                            viewingEntry = null
                            viewPath = null
                            viewError = null
                            Toast.makeText(context, "Removed from vault", Toast.LENGTH_SHORT).show()
                        }
                    ) {
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
            },
            dismissButton = {
                TextButton(onClick = {
                    viewingEntry = null
                    viewPath = null
                    viewError = null
                }) {
                    Text("Close")
                }
            }
        )
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
                    val (action, entry) = pendingAction ?: return@TextButton
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
                            pendingAction = null
                            runVaultAction(action, entry, newPassword)
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
}

/** One tile in the vault grid. Encrypted entries show a lock instead of a preview. */
@Composable
private fun VaultCell(
    entry: VaultEntry,
    previewPath: String?,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onTap)
            .background(Color.White)
    ) {
        if (previewPath != null) {
            ImageThumbnail(
                imageUri = previewPath,
                modifier = Modifier.fillMaxSize(),
                backgroundColor = Color.White
            )
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

// ===================== Vault domain model =====================

/** What the user is trying to do when the vault password is requested. */
private enum class VaultAction { VIEW, ENCRYPT, DECRYPT, MOVE_OUT }

/** Lightweight entry description built from the on-disk metadata. */
data class VaultEntry(
    val id: String,
    val displayName: String,
    val encrypted: Boolean,
    val hidden: Boolean,
    val dateAdded: Long
)

/** Per-entry metadata persisted as <id>.meta next to the data file. */
data class VaultMeta(
    val name: String = "",
    val encrypted: Boolean = false,
    val hidden: Boolean = false,
    val dateAdded: Long = 0L
)

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
                encrypted = meta.encrypted,
                hidden = meta.hidden,
                dateAdded = meta.dateAdded
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
     * from other gallery apps). Password protection is applied later with
     * [encryptEntry] on the user's explicit choice. Returns true on success.
     */
    fun importFromGallery(
        context: Context,
        uri: String,
        displayName: String
    ): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))
                ?.use { it.readBytes() } ?: return false
            if (bytes.isEmpty()) return false

            val id = "v" + System.currentTimeMillis() + "_" + (Math.random() * 100000).toInt()
            val dataFile = File(vaultDir(context), id).apply { writeBytes(bytes) }
            if (!dataFile.exists() || dataFile.length() == 0L) {
                dataFile.delete()
                return false
            }
            val meta = VaultMeta(
                name = displayName,
                encrypted = false,
                hidden = false,
                dateAdded = System.currentTimeMillis()
            )
            File(vaultDir(context), "$id.meta").writeText(gson.toJson(meta))
            true
        } catch (e: Exception) {
            false
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
     * Move an entry OUT of the vault into the gallery (Pictures/PYQAlbum via
     * a MediaStore insert with the IS_PENDING publish pattern — zero extra
     * permission, exactly how the album's own copy/move writes to standard
     * collections). Encrypted entries are decrypted with [password] in memory
     * first. The vault copy is removed on success.
     */
    fun moveOut(context: Context, entry: VaultEntry, password: String?): Boolean {
        var destUri: Uri? = null
        return try {
            val bytes = if (entry.encrypted) {
                if (password.isNullOrEmpty()) return false
                VaultCrypto.decryptBytes(entryFile(context, entry).readBytes(), password)
                    ?: return false
            } else {
                entryFile(context, entry).readBytes()
            }
            if (bytes.isEmpty()) return false

            val name = entry.displayName.ifBlank { "vault_image" }
            val mime = when (name.substringAfterLast('.', "").lowercase()) {
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "png" -> "image/png"
                else -> "image/jpeg"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PYQAlbum")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            destUri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            val output = context.contentResolver.openOutputStream(destUri!!)
            if (output == null) {
                context.contentResolver.delete(destUri!!, null, null)
                return false
            }
            output.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val updated = context.contentResolver.update(destUri!!, publishValues, null, null)
                if (updated == 0) {
                    context.contentResolver.delete(destUri!!, null, null)
                    return false
                }
            }
            deleteEntry(context, entry)
            true
        } catch (e: Exception) {
            destUri?.let { uri ->
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            false
        }
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