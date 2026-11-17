package com.pyqcr.ui.screen

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pyqcr.ai.AiEditSelectionStore
import com.pyqcr.data.model.ImageItem
import com.pyqcr.ui.component.CopyMoveDestinationDialog
import com.pyqcr.ui.component.RatingBar
import com.pyqcr.ui.util.FileOperationHelper
import com.pyqcr.ui.viewmodel.AlbumViewModel
import com.pyqcr.util.FavouriteStore
import com.pyqcr.util.ImageEditSelectionStore
import com.pyqcr.util.PermissionHelper
import com.pyqcr.util.WechatGridSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The batch actions the search result list offers — the folder view's menu. */
enum class SearchBatchAction {
    TAG, RATE, FAVOURITE, DELETE, RESIZE, COLLAGE, RENAME,
    COPY, MOVE, VAULT, AI_RATING, AI_EDIT, EDIT_IMAGES, WECHAT_GRID
}

/**
 * Runs one batch action chosen from the shared batch bar in the search results.
 *
 * The actions are the folder view's, driven by the same helpers so a result set
 * behaves like a selection in the album grid: tag/rating go through
 * [AlbumViewModel], delete through [FileOperationHelper]'s consent state
 * machine, copy/move through the in-app destination browser, and the
 * "hand over to another screen" actions use the same selection stores those
 * screens read (Edit images, WeChat Grid, AI Edit, AI Rating).
 *
 * [action] is consumed once: the host reports back through [onConsumed] and
 * every outcome is announced with [onMessage].
 */
@Composable
fun SearchBatchActionHost(
    action: SearchBatchAction?,
    uris: List<String>,
    images: List<ImageItem>,
    onConsumed: () -> Unit,
    onMessage: (String) -> Unit,
    onNavigateToImageEdit: () -> Unit,
    onNavigateToWechatGrid: () -> Unit,
    onNavigateToAiEdit: () -> Unit,
    viewModel: AlbumViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showTag by remember { mutableStateOf(false) }
    var tagName by remember { mutableStateOf("") }
    var showRate by remember { mutableStateOf(false) }
    var ratingValue by remember { mutableStateOf(0f) }
    var showResize by remember { mutableStateOf(false) }
    var showCollage by remember { mutableStateOf(false) }
    var collageColumns by remember { mutableIntStateOf(3) }
    var collageBg by remember { mutableIntStateOf(android.graphics.Color.WHITE) }
    var collageOrder by remember { mutableStateOf(uris) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showDestinations by remember { mutableStateOf(false) }
    var destIsCopy by remember { mutableStateOf(true) }
    var showAiRole by remember { mutableStateOf(false) }

    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }
    var pendingAllFiles by remember { mutableStateOf<List<String>?>(null) }
    var pendingFavourite by remember { mutableStateOf<List<String>?>(null) }

    // Launcher inputs/outputs. The callbacks only move state — Kotlin cannot
    // reference a local function declared further down, so the work happens in
    // the effects placed after those functions.
    var verifyDelete by remember { mutableStateOf<List<String>?>(null) }
    var retryDelete by remember { mutableStateOf<List<String>?>(null) }
    var applyFavourite by remember { mutableStateOf<List<String>?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingDelete
        pendingDelete = null
        if (result.resultCode == Activity.RESULT_OK) verifyDelete = pending
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pending = pendingAllFiles
        pendingAllFiles = null
        retryDelete = pending
    }

    val favouriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingFavourite
        pendingFavourite = null
        if (result.resultCode == Activity.RESULT_OK) applyFavourite = pending
    }

    fun notify(text: String) = onMessage(text)

    // Re-read what really left the gallery: a trashed row counts as deleted,
    // and the platform applies IS_TRASHED a moment after the dialog closes.
    fun finishVerified(pending: List<String>) {
        scope.launch {
            val survivors = withContext(Dispatchers.IO) {
                FileOperationHelper.survivingUris(
                    context, pending, FileOperationHelper.BIN_SETTLE_MS
                )
            }
            val removed = pending.filterNot { it in survivors }
            if (removed.isNotEmpty()) viewModel.deleteImages(removed)
            viewModel.refreshImages()
            notify("Moved ${pending.size - survivors.size}/${pending.size} item(s) to the recycle bin")
        }
    }

    fun launchConsent(pending: List<String>) {
        val sender = FileOperationHelper.createTrashRequest(context, pending)
        if (sender == null) {
            notify("Could not ask Android to move ${pending.size} item(s) to the recycle bin")
            return
        }
        pendingDelete = pending
        try {
            consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            pendingDelete = null
            notify("Could not open the delete prompt")
        }
    }

    fun runDelete(pending: List<String>, askAllFiles: Boolean) {
        if (pending.isEmpty()) return
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                FileOperationHelper.attemptDelete(context, pending, askAllFiles)
            }
            when (outcome) {
                is FileOperationHelper.DeleteOutcome.Done -> {
                    val done = outcome.removed.size + outcome.trashed.size
                    if (outcome.removed.isNotEmpty()) viewModel.deleteImages(outcome.removed)
                    viewModel.refreshImages()
                    notify("Moved $done/${pending.size} item(s) to the recycle bin")
                }
                is FileOperationHelper.DeleteOutcome.NeedsAllFiles -> {
                    pendingAllFiles = outcome.remaining
                    try {
                        allFilesLauncher.launch(PermissionHelper.allFilesAccessIntent(context))
                    } catch (e: Exception) {
                        pendingAllFiles = null
                        launchConsent(outcome.remaining)
                    }
                }
                is FileOperationHelper.DeleteOutcome.NeedsConsent ->
                    launchConsent(outcome.remaining)
            }
        }
    }

    fun applyFavouriteNow(pending: List<String>) {
        pending.forEach { FavouriteStore.setFavourite(context, it, true) }
        FavouriteStore.syncFavouriteAlbum(context, pending, true)
        scope.launch { viewModel.refreshImages() }
        notify("Added ${pending.size} item(s) to Favourites")
    }

    fun runFavourite(pending: List<String>) {
        val sender = FavouriteStore.createFavoriteRequest(context, pending, true)
        if (sender != null) {
            pendingFavourite = pending
            try {
                favouriteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                return
            } catch (e: Exception) {
                pendingFavourite = null
            }
        }
        applyFavouriteNow(pending)
    }

    fun runCopyMove(pending: List<String>, dest: File, isCopy: Boolean) {
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                if (isCopy) FileOperationHelper.batchCopyViaFiles(context, pending, dest)
                else FileOperationHelper.batchMoveViaFiles(context, pending, dest)
            }
            val ok = results.count { it.second.success }
            if (!isCopy) {
                val sources = results
                    .filter { it.second.success && it.second.sourceNeedsDeletion }
                    .map { it.first }
                if (sources.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        FileOperationHelper.deleteMoveSources(context, sources)
                    }
                }
            }
            viewModel.refreshImages()
            val verb = if (isCopy) "Copied" else "Moved"
            notify("$verb $ok/${pending.size} item(s) to \"${dest.name}\"")
        }
    }

    fun runResize(pending: List<String>, width: Int, saveMode: String, padSquare: Boolean, padColor: Int) {
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                pending.count { uri ->
                    resizeSingleImageToWidth(
                        context = context,
                        uriStr = uri,
                        targetWidth = width,
                        saveMode = saveMode,
                        padSquare = padSquare,
                        padColor = padColor
                    )
                }
            }
            viewModel.refreshImages()
            notify("Resized $done/${pending.size} image(s)")
        }
    }

    fun runVault(pending: List<String>) {
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                var imported = 0
                for (uri in pending) {
                    val name = VaultStorage.galleryDisplayName(context, uri)
                    val isVideo = uri.contains("/video/")
                    if (VaultStorage.importFromGallery(context, uri, name, isVideo)) imported++
                }
                if (imported > 0) FileOperationHelper.deleteMoveSources(context, pending)
                imported
            }
            viewModel.refreshImages()
            notify("Moved $done/${pending.size} item(s) to the Photo Vault")
        }
    }

    // Launcher results, one effect per input, so nothing is lost if the screen
    // recomposes while a system dialog is up.
    LaunchedEffect(verifyDelete) {
        val pending = verifyDelete ?: return@LaunchedEffect
        verifyDelete = null
        finishVerified(pending)
    }
    LaunchedEffect(retryDelete) {
        val pending = retryDelete ?: return@LaunchedEffect
        retryDelete = null
        runDelete(pending, askAllFiles = false)
    }
    LaunchedEffect(applyFavourite) {
        val pending = applyFavourite ?: return@LaunchedEffect
        applyFavourite = null
        applyFavouriteNow(pending)
    }

    // ---- dispatch --------------------------------------------------------
    LaunchedEffect(action) {
        val chosen = action ?: return@LaunchedEffect
        val pending = uris
        if (pending.isEmpty()) {
            onConsumed()
            return@LaunchedEffect
        }
        when (chosen) {
            SearchBatchAction.TAG -> {
                tagName = ""
                showTag = true
            }
            SearchBatchAction.RATE -> {
                ratingValue = images.firstOrNull { it.uri == pending.first() }?.rating ?: 0f
                showRate = true
            }
            SearchBatchAction.RESIZE -> showResize = true
            SearchBatchAction.COLLAGE -> {
                collageOrder = pending
                showCollage = true
            }
            SearchBatchAction.RENAME -> {
                renameText = images.firstOrNull { it.uri == pending.first() }
                    ?.displayName?.substringBeforeLast('.') ?: ""
                showRename = true
            }
            SearchBatchAction.COPY, SearchBatchAction.MOVE -> {
                destIsCopy = chosen == SearchBatchAction.COPY
                showDestinations = true
            }
            SearchBatchAction.AI_EDIT -> showAiRole = true
            SearchBatchAction.FAVOURITE -> {
                runFavourite(pending)
                onConsumed()
            }
            SearchBatchAction.DELETE -> {
                runDelete(pending, askAllFiles = true)
                onConsumed()
            }
            SearchBatchAction.VAULT -> {
                runVault(pending)
                onConsumed()
            }
            SearchBatchAction.AI_RATING -> {
                val merged = loadAiSelected(context) + pending
                saveAiSelected(context, merged)
                notify("Added ${pending.size} image(s) to the AI Rating queue")
                onConsumed()
            }
            SearchBatchAction.EDIT_IMAGES -> {
                ImageEditSelectionStore.save(context, pending)
                onConsumed()
                onNavigateToImageEdit()
            }
            SearchBatchAction.WECHAT_GRID -> {
                WechatGridSelectionStore.add(context, pending)
                onConsumed()
                onNavigateToWechatGrid()
            }
        }
    }

    // ---- dialogs ---------------------------------------------------------
    if (showTag) {
        AlertDialog(
            onDismissRequest = { showTag = false; onConsumed() },
            title = { Text("Tag ${uris.size} item(s)") },
            text = {
                OutlinedTextField(
                    value = tagName,
                    onValueChange = { tagName = it },
                    singleLine = true,
                    label = { Text("Tag name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = tagName.isNotBlank(),
                    onClick = {
                        uris.forEach { viewModel.addTagToImage(it, tagName.trim()) }
                        notify("Tagged ${uris.size} item(s) \"${tagName.trim()}\"")
                        showTag = false
                        onConsumed()
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showTag = false; onConsumed() }) { Text("Cancel") }
            }
        )
    }

    if (showRate) {
        AlertDialog(
            onDismissRequest = { showRate = false; onConsumed() },
            title = { Text("Rate ${uris.size} item(s)") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    RatingBar(
                        rating = ratingValue,
                        onRatingChange = { ratingValue = it },
                        starSize = 32.dp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (ratingValue <= 0f) "No stars — tap to rate"
                        else "${ratingValue.toInt()} star(s)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uris.forEach { viewModel.updateRating(it, ratingValue) }
                    notify("Rated ${uris.size} item(s) ${ratingValue.toInt()}★")
                    showRate = false
                    onConsumed()
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showRate = false; onConsumed() }) { Text("Cancel") }
            }
        )
    }

    if (showRename) {
        val target = uris.firstOrNull()
        AlertDialog(
            onDismissRequest = { showRename = false; onConsumed() },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("File name (without extension)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank() && target != null,
                    onClick = {
                        val ok = target != null &&
                            FileOperationHelper.renameMedia(context, target, renameText.trim())
                        notify(if (ok) "Renamed to \"${renameText.trim()}\"" else "Rename failed")
                        if (ok) viewModel.refreshImages()
                        showRename = false
                        onConsumed()
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false; onConsumed() }) { Text("Cancel") }
            }
        )
    }

    if (showAiRole) {
        AlertDialog(
            onDismissRequest = { showAiRole = false; onConsumed() },
            title = { Text("AI Edit") },
            text = { Text("Add ${uris.size} selected image(s) to AI Edit as:") },
            confirmButton = {
                TextButton(onClick = {
                    AiEditSelectionStore.saveSourceUris(context, uris.toSet())
                    showAiRole = false
                    onConsumed()
                    onNavigateToAiEdit()
                }) { Text("Source") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        AiEditSelectionStore.saveTargetUris(context, uris.toSet())
                        showAiRole = false
                        onConsumed()
                        onNavigateToAiEdit()
                    }) { Text("Target") }
                    TextButton(onClick = { showAiRole = false; onConsumed() }) { Text("Cancel") }
                }
            }
        )
    }

    if (showResize) {
        ResizeOptionsDialog(
            imageCount = uris.size,
            onDismiss = { showResize = false; onConsumed() },
            onConfirm = { width, saveMode, padSquare, padColor ->
                showResize = false
                onConsumed()
                runResize(uris, width, saveMode, padSquare, padColor)
            }
        )
    }

    if (showCollage) {
        CollageOptionsDialog(
            imageUris = collageOrder,
            allImages = images,
            columns = collageColumns,
            onColumnsChange = { collageColumns = it },
            bgColor = collageBg,
            onBgColorChange = { collageBg = it },
            order = collageOrder,
            onOrderChange = { collageOrder = it },
            onDismiss = { showCollage = false; onConsumed() },
            onConfirm = {
                val order = collageOrder
                showCollage = false
                onConsumed()
                scope.launch {
                    val saved = buildCollageImage(context, order, collageColumns, collageBg)
                    if (saved) {
                        viewModel.refreshImages()
                        notify("Collage of ${order.size} photo(s) saved to Pictures/PYQAlbum")
                    } else {
                        notify("Could not save the collage")
                    }
                }
            }
        )
    }

    if (showDestinations) {
        CopyMoveDestinationDialog(
            isCopy = destIsCopy,
            onDismiss = { showDestinations = false; onConsumed() },
            onPickFolder = { dir ->
                showDestinations = false
                onConsumed()
                runCopyMove(uris, dir, destIsCopy)
            },
            onPickSystem = {
                // The batch flows deliberately stay in-app; the system picker is
                // not offered for an in-app destination move.
                showDestinations = false
                onConsumed()
                notify("Choose a folder inside the app")
            }
        )
    }
}

private const val AI_SELECT_PREFS = "pyqcr_ai_select"
private const val AI_SELECT_KEY = "ai_selected_uris"

private fun loadAiSelected(context: Context): Set<String> =
    context.getSharedPreferences(AI_SELECT_PREFS, Context.MODE_PRIVATE)
        .getStringSet(AI_SELECT_KEY, emptySet()) ?: emptySet()

private fun saveAiSelected(context: Context, uris: Set<String>) {
    context.getSharedPreferences(AI_SELECT_PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(AI_SELECT_KEY, uris).apply()
}

/**
 * Build one collage JPEG from [uris] and register it in the library — the same
 * pipeline the folder view's Collage action uses, minus the automatic tag.
 */
private suspend fun buildCollageImage(
    context: Context,
    uris: List<String>,
    columns: Int,
    backgroundColor: Int
): Boolean = withContext(Dispatchers.IO) {
    val bitmap = com.pyqcr.util.ImageUtil.createCollage(
        context = context,
        sourceUris = uris,
        columns = columns,
        backgroundColor = backgroundColor
    ) ?: return@withContext false
    val fileName = "collage_${System.currentTimeMillis()}.jpg"
    // The JPEG is published into MediaStore, so the library refresh that runs
    // after this picks it up — no separate Room insert needed.
    val collageUri = com.pyqcr.util.ImageUtil.saveCollageBitmap(context, bitmap, fileName)
    bitmap.recycle()
    collageUri != null
}
