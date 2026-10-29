package com.pyqcr.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.pyqcr.PyqCrApp
import com.pyqcr.ai.AiEditSelectionStore
import com.pyqcr.data.db.ImageTagCrossRef
import com.pyqcr.data.db.TagEntity
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.GifPlayer
import com.pyqcr.ui.component.MotionPhotoPlayer
import com.pyqcr.ui.component.RatingBar
import com.pyqcr.ui.component.TagChip
import com.pyqcr.ui.component.VideoPlayer
import com.pyqcr.ui.util.FileOperationHelper
import com.pyqcr.util.FavouriteStore
import com.pyqcr.util.MotionPhotoExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves the zoom pan offset across activity recreation (switching to another
 * app and coming back), so the viewer keeps the exact framing.
 */
private val OffsetSaver = listSaver<Offset, Float>(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) }
)

/**
 * Full-screen image detail view.
 *
 * - Swipe down anywhere on the image → go back to the thumbnail grid.
 * - Swipe up → reveal rating / tags / file path panel.
 * - Pinch (or double-tap) to zoom in/out; while zoomed, drag to pan.
 * - Left/right swipe → previous/next image (only when not zoomed).
 * - Image fills available screen space on either width or height (ContentScale.Fit,
 *   constrained by parent Box) — no cropping, no overflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDetailScreen(
    imageUriRaw: String,
    onBack: () -> Unit,
    onNextImage: () -> Unit = {},
    onPreviousImage: () -> Unit = {},
    onOpenAiRating: () -> Unit = {},
    onOpenAiEdit: () -> Unit = {},
    hasNextImage: Boolean = true,
    hasPreviousImage: Boolean = true,
    // +1 = arrived by swiping left (slide in from the right), -1 = arrived by
    // swiping right, 0 = opened from the grid (no slide).
    enterDirection: Int = 0
) {
    // URL-decode the URI — Navigation Compose encodes it to avoid path-segment issues
    val imageUri = Uri.decode(imageUriRaw)

    // Plain video file (content://media/.../video/...) — the folder browse now
    // shows videos beside images, so the detail screen must PLAY them instead
    // of trying to decode a thumbnail.
    val isVideo = imageUri.contains("/video/")

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
    var showDetailPanel by rememberSaveable { mutableStateOf(false) } // Swipe-up action bar
    var showInfoDialog by remember { mutableStateOf(false) }  // Details (metadata/rating)
    var showTagsDialog by remember { mutableStateOf(false) }  // Tags editor
    val coroutineScope = rememberCoroutineScope()

    // Used to detect gesture directions with thresholds
    val swipeThreshold = with(LocalDensity.current) { 60.dp.toPx() }
    var dragVerticalAccum by remember { mutableFloatStateOf(0f) }

    // Pinch-zoom state for the enlarged image (scale 1 = fitted, >1 = zoomed in).
    // Swipe navigation is only active while scale == 1; while zoomed, single-finger
    // drags pan the image instead. Both values are saveable so the viewer keeps
    // the same framing after the activity is recreated.
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

    // Live swipe feedback: the image tracks the finger and slides off-screen in
    // the swipe direction before navigating (short, keeps the gesture tactile).
    val swipeX = remember { Animatable(0f) }
    val swipeY = remember { Animatable(0f) }

    // Size of the image viewport (px) — used to clamp the pan so the zoomed
    // image can never be dragged entirely out of view.
    var viewSize by remember { mutableStateOf(Size.Zero) }

    // Slide a newly opened neighbour in from the side it was swiped from.
    var enterSlideDone by remember(imageUri) { mutableStateOf(false) }
    LaunchedEffect(imageUri, viewSize.width) {
        if (!enterSlideDone && enterDirection != 0 && viewSize.width > 0f) {
            enterSlideDone = true
            swipeX.snapTo(viewSize.width * 0.28f * enterDirection)
            swipeX.animateTo(0f, tween(200))
        }
    }

    // Motion-photo state: [motionVideoPath] is the cache file with the embedded
    // MP4 (null = not a motion photo); [showMotionVideo] toggles the video surface.
    var motionVideoPath by remember(imageUri) { mutableStateOf<String?>(null) }
    var showMotionVideo by remember { mutableStateOf(false) }

    // ---- AI queue / favourite state for the swipe-up toggle buttons ----
    // (Same stores as the album's batch menu: pyqcr_ai_select prefs for AI
    // Ranking, AiEditSelectionStore for AI Edit, MediaStore IS_FAVORITE for
    // the favourite flag.)
    var aiSelectedUris by remember(imageUri) { mutableStateOf(loadImageAiSelectedUris(context)) }
    var aiEditSource by remember(imageUri) {
        mutableStateOf(AiEditSelectionStore.loadSourceUris(context).contains(imageUri))
    }
    var aiEditTarget by remember(imageUri) {
        mutableStateOf(AiEditSelectionStore.loadTargetUris(context).contains(imageUri))
    }
    var isFavourite by remember(imageUri) { mutableStateOf(FavouriteStore.isFavourite(context, imageUri)) }
    var showAiEditRoleDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Favourite marking: the platform favourite-request dialog is the PRIMARY
    // method — the media provider itself persists IS_FAVORITE (works even for
    // rows the app does not own). On Android 11/12 (no favourite-request
    // dialog) a WRITE-ACCESS request is launched first, and the direct
    // IS_FAVORITE write runs only after the provider grants access — so the
    // flag persists even for rows this app does not own. The plain direct
    // write remains the last-resort fallback.
    var pendingFavouriteValue by remember { mutableStateOf(false) }
    var pendingFavouriteNeedsWriteGrant by remember { mutableStateOf(false) }
    val favouriteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        coroutineScope.launch {
            if (pendingFavouriteNeedsWriteGrant) {
                // Android 11/12 path: the dialog granted write access to this
                // row — now the direct flag write persists even for photos this
                // app does not own.
                pendingFavouriteNeedsWriteGrant = false
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val ok = withContext(Dispatchers.IO) {
                        FavouriteStore.setFavourite(context, imageUri, pendingFavouriteValue)
                    }
                    isFavourite = withContext(Dispatchers.IO) {
                        FavouriteStore.isFavourite(context, imageUri)
                    }
                    snackbarHostState.showSnackbar(
                        if (ok) {
                            if (pendingFavouriteValue) "Marked as favourite" else "Removed from favourites"
                        } else {
                            "Could not update favourite"
                        }
                    )
                } else {
                    snackbarHostState.showSnackbar("Write access was not granted")
                }
            } else {
                // Re-read the real flag on ANY result — some ROMs report
                // RESULT_CANCELED even though the platform dialog applied it.
                isFavourite = withContext(Dispatchers.IO) {
                    FavouriteStore.isFavourite(context, imageUri)
                }
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    if (isFavourite == pendingFavouriteValue) {
                        snackbarHostState.showSnackbar(
                            if (pendingFavouriteValue) "Marked as favourite" else "Removed from favourites"
                        )
                    } else {
                        // The ROM claimed success but the provider flag did not
                        // change — the user deserves honest feedback.
                        snackbarHostState.showSnackbar(
                            "This device did not apply the favourite change"
                        )
                    }
                }
            }
        }
    }

    // Toggle this image in/out of the AI Rating queue (same as batch menu's
    // "AI Rating" item) — feedback mirrors the batch menu snackbar.
    val toggleAiRanking: () -> Unit = {
        val wasSelected = imageUri in aiSelectedUris
        aiSelectedUris = if (wasSelected) aiSelectedUris - imageUri else aiSelectedUris + imageUri
        saveImageAiSelectedUris(context, aiSelectedUris)
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                if (wasSelected) "Removed from AI Rating" else "Added to AI Rating"
            )
        }
    }

    // Toggle the favourite flag (MediaStore IS_FAVORITE, API 30+). PRIMARY
    // method: the direct verified IS_FAVORITE write — in-app only, no system
    // dialog, and the flag does not need to show in other apps. The platform
    // favourite-request dialog is only a fallback when the provider rejects
    // the direct write (row owned by another app).
    val toggleFavourite: () -> Unit = {
        if (!FavouriteStore.supported) {
            coroutineScope.launch { snackbarHostState.showSnackbar("Favourite requires Android 11+") }
        } else {
            val newValue = !isFavourite
            coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    FavouriteStore.setFavourite(context, imageUri, newValue)
                }
                if (ok) {
                    isFavourite = newValue
                    snackbarHostState.showSnackbar(
                        if (newValue) "Marked as favourite" else "Removed from favourites"
                    )
                } else {
                    // Provider rejected the direct write — fall back to the
                    // platform favourite-request dialog (Android 13+), or a
                    // WRITE-ACCESS request (Android 11/12).
                    val sender = FavouriteStore.createFavoriteRequest(
                        context, listOf(imageUri), newValue
                    )
                    if (sender != null) {
                        pendingFavouriteValue = newValue
                        pendingFavouriteNeedsWriteGrant = false
                        favouriteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } else {
                        val writeSender = FavouriteStore.createWriteAccessRequest(
                            context, listOf(imageUri)
                        )
                        if (writeSender != null) {
                            pendingFavouriteValue = newValue
                            pendingFavouriteNeedsWriteGrant = true
                            favouriteLauncher.launch(IntentSenderRequest.Builder(writeSender).build())
                        } else {
                            snackbarHostState.showSnackbar("Could not update favourite")
                        }
                    }
                }
            }
        }
    }

    // Apply an AI Edit role toggle for this image (add if absent, remove if
    // present) — same source/target semantics as the batch menu's role dialog.
    val toggleAiEditRole: (Boolean) -> Unit = { asSource ->
        showAiEditRoleDialog = false
        if (asSource) {
            val currentlySource = AiEditSelectionStore.loadSourceUris(context).contains(imageUri)
            if (currentlySource) {
                AiEditSelectionStore.saveSourceUris(
                    context, AiEditSelectionStore.loadSourceUris(context) - imageUri
                )
                aiEditSource = false
                coroutineScope.launch { snackbarHostState.showSnackbar("Removed from AI Edit source") }
            } else {
                AiEditSelectionStore.addSource(context, imageUri)
                aiEditSource = true
                aiEditTarget = false
                coroutineScope.launch { snackbarHostState.showSnackbar("Added as AI Edit source") }
            }
        } else {
            val currentlyTarget = AiEditSelectionStore.loadTargetUris(context).contains(imageUri)
            if (currentlyTarget) {
                AiEditSelectionStore.saveTargetUris(
                    context, AiEditSelectionStore.loadTargetUris(context) - imageUri
                )
                aiEditTarget = false
                coroutineScope.launch { snackbarHostState.showSnackbar("Removed from AI Edit target") }
            } else {
                val targets = (AiEditSelectionStore.loadTargetUris(context) + imageUri)
                    .minus(AiEditSelectionStore.loadSourceUris(context))
                AiEditSelectionStore.saveTargetUris(context, targets)
                AiEditSelectionStore.saveSourceUris(
                    context, AiEditSelectionStore.loadSourceUris(context) - imageUri
                )
                aiEditTarget = true
                aiEditSource = false
                coroutineScope.launch { snackbarHostState.showSnackbar("Added as AI Edit target") }
            }
        }
    }

    // ---- Batch-menu actions on the swipe-up panel ----
    // The same operations the album's batch ⋮ menu offers, applied to this
    // single image: resize / rename / copy / move / move-to-vault / delete.

    var showResizeDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameBaseDraft by remember { mutableStateOf("") }
    var renameExtDraft by remember { mutableStateOf("") }

    /** Load the file name into the rename dialog (same behaviour as the batch menu). */
    val openRenameDialog: () -> Unit = {
        coroutineScope.launch {
            val name = withContext(Dispatchers.IO) {
                FileOperationHelper.displayNameOf(context, imageUri)
            }
            if (name.isNullOrBlank()) {
                snackbarHostState.showSnackbar("Could not read the file name")
            } else {
                val dot = name.lastIndexOf('.')
                if (dot > 0) {
                    renameBaseDraft = name.substring(0, dot)
                    renameExtDraft = name.substring(dot + 1)
                } else {
                    renameBaseDraft = name
                    renameExtDraft = ""
                }
                showRenameDialog = true
            }
        }
    }
    var destinationDialogOp by remember { mutableStateOf("copy") }
    var folderBrowserDir by remember { mutableStateOf<File?>(null) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showFullAccessDialog by remember { mutableStateOf(false) }
    var targetFolderForOperation by remember { mutableStateOf<File?>(null) }
    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }

    // System trash/delete confirmation (API 30+); on success refresh the album
    // DB and leave the detail screen (the image no longer exists).
    var pendingDeleteUris by remember { mutableStateOf<List<String>?>(null) }
    var pendingDeleteMessage by remember { mutableStateOf("") }
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && pendingDeleteUris != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(pendingDeleteMessage)
                withContext(Dispatchers.IO) {
                    repository.refreshImagesFromMediaStore()
                }
                onBack()
            }
        }
        pendingDeleteUris = null
    }

    /**
     * Send [uris] to the recycle bin first (system trash request, API 30+),
     * falling back to a permanent direct delete on older APIs. Mirrors the
     * album batch menu's trashOrDelete.
     */
    val trashUris: (List<String>, String, String) -> Unit = { uris, trashMsg, directMsg ->
        val deleteDirectly: () -> Unit = {
            coroutineScope.launch {
                FileOperationHelper.deleteMediaDirect(context, uris)
                snackbarHostState.showSnackbar(directMsg)
                withContext(Dispatchers.IO) {
                    repository.refreshImagesFromMediaStore()
                }
                onBack()
            }
        }
        if (uris.isNotEmpty()) {
            val trashRequest = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                FileOperationHelper.createTrashRequest(context, uris)
            } else {
                null
            }
            if (trashRequest != null) {
                pendingDeleteUris = uris
                pendingDeleteMessage = trashMsg
                deleteLauncher.launch(IntentSenderRequest.Builder(trashRequest).build())
            } else {
                deleteDirectly()
            }
        }
    }

    /**
     * Copy/move this image through the in-app folder browser (or the SAF tree
     * picker); moves re-map tag cross-refs and then drop the gallery originals
     * via the recycle-bin path.
     */
    val launchCopyMove: (String, List<String>, File?, Uri?) -> Unit = { op, uris, destDir, treeUri ->
        coroutineScope.launch {
            val results = withContext(Dispatchers.IO) {
                if (op == "move") {
                    FileOperationHelper.batchMoveViaFiles(context, uris, destDir, treeUri)
                } else {
                    FileOperationHelper.batchCopyViaFiles(context, uris, destDir, treeUri)
                }
            }
            val ok = results.count { it.second.success }
            val failed = results.size - ok
            val needTrash = results.filter { it.second.sourceNeedsDeletion }.map { it.first }
            snackbarHostState.showSnackbar(
                if (op == "move") {
                    if (failed == 0) "Moved $ok image(s)" else "Moved $ok, $failed failed"
                } else {
                    if (failed == 0) "Copied $ok image(s)" else "Copied $ok, $failed failed"
                }
            )
            if (op == "move") {
                // Re-point the tag cross-refs at the new MediaStore URIs (tags
                // are keyed by URI — a move must remap them).
                withContext(Dispatchers.IO) {
                    for ((sourceUri, result) in results) {
                        if (result.success) {
                            val newUri = FileOperationHelper.resolveNewImageUri(
                                context, result.destination ?: "", treeUri
                            )
                            if (newUri != null && newUri != sourceUri) {
                                tagDao.moveImageCrossRef(sourceUri, newUri)
                            }
                        }
                    }
                }
                if (needTrash.isNotEmpty()) {
                    trashUris(needTrash, "Moved image(s)", "Moved image(s)")
                } else {
                    withContext(Dispatchers.IO) {
                        repository.refreshImagesFromMediaStore()
                    }
                    onBack()
                }
            } else {
                withContext(Dispatchers.IO) {
                    repository.refreshImagesFromMediaStore()
                }
            }
        }
    }

    /** Proceed with the pending copy/move after the All-files-access flow. */
    val continueCopyMove: () -> Unit = {
        showFullAccessDialog = false
        launchCopyMove(destinationDialogOp, listOf(imageUri), targetFolderForOperation, selectedTreeUri)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedTreeUri = uri
            val destDir = resolveTreeUriToPath(uri)
            targetFolderForOperation = destDir
            launchCopyMove(destinationDialogOp, listOf(imageUri), destDir, uri)
        }
    }

    val fullAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        continueCopyMove()
    }

    /**
     * Clamp a pan offset so the zoomed image stays within the viewport.
     * [candidate] is the raw accumulated translation (screen px); the bounds
     * are derived from ContentScale.Fit geometry: at scale [s] the displayed
     * image is `intrinsic * fit * s`, so the maximum useful translation is
     * half the overflow per axis. When the intrinsic size is unknown (not
     * loaded yet) the viewport size is used as a conservative bound.
     *
     * NOTE: declared before the pointerInput blocks below — Kotlin local
     * functions cannot be forward-referenced.
     */
    fun clampPanOffset(candidate: Offset, s: Float): Offset {
        if (s <= 1f) return Offset.Zero
        val viewW = viewSize.width
        val viewH = viewSize.height
        if (viewW <= 0f || viewH <= 0f) return candidate
        val iw = imageItem?.width?.takeIf { it > 0 }?.toFloat()
        val ih = imageItem?.height?.takeIf { it > 0 }?.toFloat()
        val maxX: Float
        val maxY: Float
        if (iw != null && ih != null) {
            val fit = minOf(viewW / iw, viewH / ih)
            maxX = ((iw * fit * s - viewW) / 2f).coerceAtLeast(0f)
            maxY = ((ih * fit * s - viewH) / 2f).coerceAtLeast(0f)
        } else {
            maxX = ((s - 1f) * viewW / 2f).coerceAtLeast(0f)
            maxY = ((s - 1f) * viewH / 2f).coerceAtLeast(0f)
        }
        return Offset(
            candidate.x.coerceIn(-maxX, maxX),
            candidate.y.coerceIn(-maxY, maxY)
        )
    }

    // Full file path of the current image, resolved lazily for the detail panel
    var fullPath by remember(imageUri) { mutableStateOf<String?>(null) }
    LaunchedEffect(imageUri) {
        fullPath = com.pyqcr.ui.util.FileOperationHelper.resolveFilePath(context, imageUri)
    }

    // Real file name of the current item (MediaStore DISPLAY_NAME) — used as
    // the top-bar title for items the Room DB does not know (folder-browse
    // videos, GIFs…). Also drives GIF detection: an animated GIF gets the
    // full playback control bar instead of the plain Coil image.
    var mediaFileName by remember(imageUri) { mutableStateOf<String?>(null) }
    var isGif by remember(imageUri) { mutableStateOf(false) }
    LaunchedEffect(imageUri) {
        val name = withContext(Dispatchers.IO) {
            try {
                FileOperationHelper.getFileName(context, imageUri)
            } catch (e: Exception) {
                null
            }
        }
        mediaFileName = name
        isGif = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.getType(Uri.parse(imageUri)) == "image/gif" ||
                    name?.lowercase()?.endsWith(".gif") == true
            } catch (e: Exception) {
                false
            }
        }
    }

    // Detect and extract the embedded video of a motion photo (JPEG + appended
    // MP4). GIFs and animated WebP play directly through Coil, so no extraction.
    // Plain video files skip this entirely — they play directly below.
    LaunchedEffect(imageUri) {
        if (!isVideo) {
            motionVideoPath = withContext(Dispatchers.IO) {
                MotionPhotoExtractor.extractToCache(context, imageUri)?.absolutePath
            }
            showMotionVideo = false
        }
    }

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

    // Full-screen viewer: no top bar at all (no title, no back arrow) — the
    // image uses every pixel. Swipe down or system Back leaves the viewer.

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ===================== IMAGE AREA =====================
            // The image always fills the whole screen; the swipe-up action bar
            // floats over it instead of splitting the screen.
            val imageModifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)

            Box(
                modifier = imageModifier
                    .background(Color.Black)
                    .clipToBounds()
                    .onSizeChanged {
                        viewSize = Size(it.width.toFloat(), it.height.toFloat())
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        // The saved pan offset plus the in-progress swipe
                        // translation — swiping at scale 1 drags the photo with
                        // the finger before it settles / leaves the screen.
                        translationX = offset.x + swipeX.value
                        translationY = offset.y + swipeY.value
                        val width = if (viewSize.width > 0f) viewSize.width else 1f
                        val swipeFraction =
                            (kotlin.math.abs(swipeX.value) / width).coerceIn(0f, 1f)
                        alpha = 1f - swipeFraction * 0.45f
                    }
                    .pointerInput(Unit) {
                        // Unified gesture handling:
                        //  - scale == 1: single-finger swipe navigates (down → back,
                        //    up → detail panel, left → next, right → previous)
                        //  - scale  > 1: single-finger drag pans, two-finger pinch zooms
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var transformMode = scale > 1f
                            var verticalAccum = 0f
                            var horizontalAccum = 0f
                            do {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                // A second finger switches the gesture into pan/zoom mode
                                if (pressedCount >= 2) transformMode = true
                                if (transformMode) {
                                    if (pressedCount >= 2) {
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                        scale = newScale
                                        offset = if (newScale > 1f)
                                            clampPanOffset(offset + panChange * newScale, newScale)
                                        else
                                            Offset.Zero
                                    } else if (pressedCount == 1) {
                                        val change = event.changes.first { it.pressed }
                                        // Pointer coordinates arrive in the layer's LOCAL space,
                                        // already divided by the layer's scale (the graphicsLayer
                                        // transform is applied to this node) — multiply by the
                                        // current scale so the image tracks the finger 1:1 at any
                                        // zoom level, then clamp so it never leaves the viewport.
                                        offset = clampPanOffset(
                                            offset + change.positionChange() * scale,
                                            scale
                                        )
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else {
                                    val change = event.changes.firstOrNull { it.pressed } ?: continue
                                    if (change.positionChanged()) {
                                        verticalAccum += change.positionChange().y
                                        horizontalAccum += change.positionChange().x
                                        // Live feedback: the photo follows the finger.
                                        swipeX.snapTo(horizontalAccum)
                                        swipeY.snapTo(verticalAccum)
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            // Gesture ended at scale 1 — decide swipe navigation
                            if (!transformMode) {
                                val widthPx = if (viewSize.width > 0f) viewSize.width else 1080f
                                val heightPx = if (viewSize.height > 0f) viewSize.height else 1920f
                                when {
                                    verticalAccum > swipeThreshold -> {
                                        // Swipe down → leave the viewer: let the
                                        // photo slide down a little first.
                                        swipeY.animateTo(heightPx * 0.35f, tween(150))
                                        onBack()
                                    }
                                    verticalAccum < -swipeThreshold -> {
                                        // Swipe up → settle back, then reveal the
                                        // action bar with its own slide-up.
                                        swipeY.animateTo(0f, spring())
                                        showDetailPanel = true
                                    }
                                    horizontalAccum < -swipeThreshold -> {
                                        if (hasNextImage) {
                                            swipeX.animateTo(-widthPx, tween(170))
                                            onNextImage()
                                        } else {
                                            swipeX.animateTo(0f, spring())
                                        }
                                    }
                                    horizontalAccum > swipeThreshold -> {
                                        if (hasPreviousImage) {
                                            swipeX.animateTo(widthPx, tween(170))
                                            onPreviousImage()
                                        } else {
                                            swipeX.animateTo(0f, spring())
                                        }
                                    }
                                    else -> {
                                        // Not far enough — spring back into place.
                                        swipeX.animateTo(0f, spring())
                                        swipeY.animateTo(0f, spring())
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        // Double-tap toggles between fitted and 3x zoom
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 3f
                                    offset = Offset.Zero
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isVideo) {
                    // Plain video file (mediaStore /video/ uri opened from the
                    // folder grid): play it like the bin/vault viewers. The
                    // outer gestures still handle swipe left/right (next/prev)
                    // and swipe down (close) — no zoom on video.
                    VideoPlayer(
                        data = imageUri,
                        onClose = onBack,
                        showCloseButton = false
                    )
                } else if (isGif) {
                    // Animated GIF: play it with the same control bar as
                    // videos (play/pause, seek bar, ±10 s, screenshot) instead
                    // of the plain Coil image.
                    GifPlayer(
                        data = imageUri,
                        onClose = onBack,
                        showCloseButton = false
                    )
                } else {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUri)
                            .size(1600)
                            .crossfade(true)
                            .build(),
                        contentDescription = imageItem?.displayName ?: mediaFileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                // Motion photo: show a play button; tapping it plays the
                // embedded video over the still frame.
                if (!isVideo && !showMotionVideo && motionVideoPath != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .clipToBounds()
                            .background(Color(0x99000000), MaterialTheme.shapes.small)
                            .clickable { showMotionVideo = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("▶ Motion photo", color = Color.White)
                    }
                }

                if (showMotionVideo) {
                    motionVideoPath?.let { path ->
                        MotionPhotoPlayer(
                            videoPath = path,
                            onClose = { showMotionVideo = false }
                        )
                    }
                }
            }

// ===================== ACTION BAR (swipe-up) =====================
            // Compact semi-transparent icon bar: every single-image action is
            // one tap away; anything needing detail opens a dialog on top.
            AnimatedVisibility(
                    visible = showDetailPanel,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(220)
                    ) + fadeIn(tween(160)),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(180)
                    ) + fadeOut(tween(140)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .padding(bottom = 26.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragVerticalAccum > swipeThreshold) {
                                            showDetailPanel = false
                                        }
                                        dragVerticalAccum = 0f
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        dragVerticalAccum += dragAmount
                                    }
                                )
                            },
                        shape = RoundedCornerShape(22.dp),
                        // Semi-transparent so the image stays visible behind the bar.
                        color = Color(0x99000000),
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            DetailIconButton(Icons.Default.Info, "Info") { showInfoDialog = true }
                            DetailIconButton(Icons.Default.Label, "Tags") { showTagsDialog = true }
                            DetailIconButton(
                                Icons.Default.AutoAwesome,
                                "AI Rating",
                                active = imageUri in aiSelectedUris
                            ) { toggleAiRanking() }
                            DetailIconButton(
                                Icons.Default.Edit,
                                "AI Edit",
                                active = aiEditSource || aiEditTarget
                            ) { showAiEditRoleDialog = true }
                            DetailIconButton(
                                Icons.Default.Favorite,
                                "Favourite",
                                active = isFavourite,
                                tint = if (isFavourite) Color(0xFFE91E63) else Color.White
                            ) { toggleFavourite() }
                            DetailIconButton(Icons.Default.PhotoSizeSelectLarge, "Resize") {
                                showResizeDialog = true
                            }
                            DetailIconButton(Icons.Default.DriveFileRenameOutline, "Rename") {
                                openRenameDialog()
                            }
                            DetailIconButton(Icons.Default.SaveAlt, "Copy") {
                                destinationDialogOp = "copy"
                                folderBrowserDir = Environment.getExternalStorageDirectory()
                                showDestinationDialog = true
                            }
                            DetailIconButton(Icons.Default.Forward, "Move") {
                                destinationDialogOp = "move"
                                folderBrowserDir = Environment.getExternalStorageDirectory()
                                showDestinationDialog = true
                            }
                            DetailIconButton(Icons.Default.Lock, "Vault") {
                                coroutineScope.launch {
                                    val name = VaultStorage.galleryDisplayName(context, imageUri)
                                    val imported = withContext(Dispatchers.IO) {
                                        VaultStorage.importFromGallery(context, imageUri, name)
                                    }
                                    if (imported) {
                                        trashUris(
                                            listOf(imageUri),
                                            "Moved to photo vault",
                                            "Moved to photo vault"
                                        )
                                    } else {
                                        snackbarHostState.showSnackbar("Could not move to the vault")
                                    }
                                }
                            }
                            DetailIconButton(
                                Icons.Default.Delete,
                                "Delete",
                                tint = Color(0xFFFF6E6E)
                            ) {
                                trashUris(
                                    listOf(imageUri),
                                    "Moved to the recycle bin",
                                    "Deleted permanently"
                                )
                            }
                        }
                    }
                }
            }

            // Info dialog — metadata, path, rating and AI score (everything the
            // old full-height swipe-up panel used to show).
            if (showInfoDialog) {
                AlertDialog(
                    onDismissRequest = { showInfoDialog = false },
                    title = { Text("Details") },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 460.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                DetailChip("📁 ${imageItem?.folderName ?: "Unknown"}")
                                if ((imageItem?.width ?: 0) > 0 && (imageItem?.height ?: 0) > 0) {
                                    DetailChip("${imageItem?.width} × ${imageItem?.height}")
                                }
                                DetailChip(formatFileSize(imageItem?.sizeBytes ?: 0L))
                            }
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = fullPath ?: imageUri,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            DetailSectionTitle("Rating")
                            Spacer(Modifier.height(6.dp))
                            RatingBar(
                                rating = rating,
                                onRatingChange = { newRating ->
                                    rating = newRating
                                    coroutineScope.launch {
                                        repository.updateRating(imageUri, newRating)
                                    }
                                }
                            )
                            imageItem?.aiScore?.let { aiScore ->
                                Spacer(Modifier.height(10.dp))
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
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showInfoDialog = false }) { Text("Close") }
                    }
                )
            }

            // Tags dialog — same tag list / add-tag flow as before, opened from
            // the bar's Tags icon.
            if (showTagsDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showTagsDialog = false
                        showAddTag = false
                    },
                    title = { Text("Tags") },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 440.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (tags.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    tags.forEach { tag ->
                                        TagChip(
                                            text = tag.name,
                                            onRemove = {
                                                coroutineScope.launch {
                                                    tagDao.removeTagFromImage(imageUri, tag.id)
                                                    val remaining = tagDao.getImageUrisForTag(tag.id)
                                                    if (remaining.isEmpty()) {
                                                        tagDao.deleteTagById(tag.id)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "No tags",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            if (!showAddTag) {
                                OutlinedButton(onClick = { showAddTag = true }) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add Tag")
                                }
                            } else {
                                val existingTagNames = tags.map { it.name }.toSet()
                                val availableTags = allTags.filter { it.name !in existingTagNames }
                                if (availableTags.isNotEmpty()) {
                                    Text(
                                        text = "Select existing tag:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        availableTags.chunked(3).forEach { row ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                row.forEach { tag ->
                                                    SuggestionChip(
                                                        onClick = {
                                                            coroutineScope.launch {
                                                                if (tagDao.hasTag(imageUri, tag.id) == 0) {
                                                                    tagDao.addTagToImage(
                                                                        ImageTagCrossRef(
                                                                            imageUri = imageUri,
                                                                            tagId = tag.id
                                                                        )
                                                                    )
                                                                }
                                                                showAddTag = false
                                                            }
                                                        },
                                                        label = {
                                                            Text(
                                                                tag.name,
                                                                style = MaterialTheme.typography.bodySmall
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                                        tagDao.insertTag(
                                                            TagEntity(name = newTagName.trim())
                                                        )
                                                    }
                                                    if (tagDao.hasTag(imageUri, tagId) == 0) {
                                                        tagDao.addTagToImage(
                                                            ImageTagCrossRef(
                                                                imageUri = imageUri,
                                                                tagId = tagId
                                                            )
                                                        )
                                                    }
                                                    newTagName = ""
                                                    showAddTag = false
                                                }
                                            }
                                        }
                                    ) { Text("Add") }
                                }
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = { showAddTag = false }) {
                                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showTagsDialog = false
                            showAddTag = false
                        }) { Text("Done") }
                    }
                )
            }
                    // AI Edit role dialog — same source/target roles as the batch menu
                    if (showAiEditRoleDialog) {
                        AlertDialog(
                            onDismissRequest = { showAiEditRoleDialog = false },
                            title = { Text("AI Edit selection") },
                            text = {
                                Text(
                                    if (aiEditSource || aiEditTarget)
                                        "This image is already in the AI Edit queue. " +
                                            "Choose a role to keep or switch it."
                                    else
                                        "Choose a role for this image in the AI Edit queue."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { toggleAiEditRole(true) }) {
                                    Text(if (aiEditSource) "Remove as source" else "As AI Edit source")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { toggleAiEditRole(false) }) {
                                    Text(if (aiEditTarget) "Remove as target" else "As AI Edit target")
                                }
                            }
                        )
                    }

                    // Batch-style dialogs for the single image: resize options, rename,
                    // and the in-app copy/move folder browser (same as the
                    // album's batch menu).
                    if (showRenameDialog) {
                        AlertDialog(
                            onDismissRequest = { showRenameDialog = false },
                            title = { Text("Rename file") },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = renameBaseDraft,
                                        onValueChange = { renameBaseDraft = it },
                                        label = { Text("File name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (renameExtDraft.isNotEmpty()) {
                                        Text(
                                            text = "The extension .$renameExtDraft is kept",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showRenameDialog = false
                                        val base = renameBaseDraft.trim().replace("/", "_").replace("\\", "_")
                                        if (base.isNotEmpty()) {
                                            coroutineScope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    FileOperationHelper.renameMedia(context, imageUri, base)
                                                }
                                                withContext(Dispatchers.IO) {
                                                    repository.refreshImagesFromMediaStore()
                                                }
                                                if (ok) {
                                                    imageItem = imageItem?.copy(
                                                        displayName = if (renameExtDraft.isNotEmpty())
                                                            "$base.$renameExtDraft" else base
                                                    )
                                                    snackbarHostState.showSnackbar("Renamed to $base")
                                                } else {
                                                    snackbarHostState.showSnackbar("Rename failed — the file is protected or was removed")
                                                }
                                            }
                                        }
                                    }
                                ) { Text("Rename") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    if (showResizeDialog) {
                        ResizeOptionsDialog(
                            imageCount = 1,
                            onDismiss = { showResizeDialog = false },
                            onConfirm = { targetWidth, saveMode, padSquare, padColor ->
                                showResizeDialog = false
                                coroutineScope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        resizeSingleImageToWidth(context, imageUri, targetWidth, saveMode, padSquare, padColor)
                                    }
                                    if (ok) {
                                        snackbarHostState.showSnackbar("Resized to ${targetWidth}px wide")
                                    } else {
                                        snackbarHostState.showSnackbar("Resize failed")
                                    }
                                    if (saveMode == "suffix") {
                                        withContext(Dispatchers.IO) {
                                            repository.refreshImagesFromMediaStore()
                                        }
                                    }
                                }
                            }
                        )
                    }

                    if (showDestinationDialog) {
                        val currentDir = folderBrowserDir ?: Environment.getExternalStorageDirectory()
                        val isCopy = destinationDialogOp == "copy"
                        val isRoot = currentDir == Environment.getExternalStorageDirectory()
                        val subDirs = remember(currentDir) {
                            currentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                                ?.sortedBy { it.name.lowercase() }
                                ?: emptyList()
                        }
                        AlertDialog(
                            onDismissRequest = { showDestinationDialog = false },
                            title = { Text(if (isCopy) "Copy to..." else "Move to...") },
                            text = {
                                Column {
                                    Text(
                                        text = currentDir.absolutePath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                                        if (!isRoot) {
                                            item {
                                                TextButton(
                                                    onClick = { folderBrowserDir = currentDir.parentFile },
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
                                }
                            },
                            confirmButton = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            showDestinationDialog = false
                                            folderPickerLauncher.launch(null)
                                        },
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("System", style = MaterialTheme.typography.labelMedium)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { showDestinationDialog = false }) {
                                        Text("Cancel")
                                    }
                                    TextButton(onClick = {
                                        showDestinationDialog = false
                                        val needFullAccess = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                                                !android.os.Environment.isExternalStorageManager() &&
                                                !FileOperationHelper.isStandardMediaCollection(currentDir)
                                        if (needFullAccess) {
                                            targetFolderForOperation = currentDir
                                            selectedTreeUri = null
                                            showFullAccessDialog = true
                                        } else {
                                            launchCopyMove(destinationDialogOp, listOf(imageUri), currentDir, null)
                                        }
                                    }) {
                                        Text("Select folder")
                                    }
                                }
                            }
                        )
                    }

                    if (showFullAccessDialog) {
                        AlertDialog(
                            onDismissRequest = { continueCopyMove() },
                            title = { Text("Copy/Move to any folder?") },
                            text = {
                                Text(
                                    "The chosen folder sits outside Android's media folders, which " +
                                        "normal apps cannot write to directly. Granting \"All files access\" " +
                                        "lets the app copy/move into ANY folder on this phone.\n\n" +
                                        "Android will open a settings screen — enable the switch there, " +
                                        "then the copy/move starts automatically."
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
                                        continueCopyMove()
                                    }
                                }) { Text("Grant access") }
                            },
                            dismissButton = {
                                TextButton(onClick = { continueCopyMove() }) { Text("Later") }
                            }
                        )
                    }
                    }
                }
            }

/** Small rounded label used for the metadata row in the swipe-up panel. */
@Composable
private fun DetailChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** Section heading in the swipe-up panel. */
@Composable
private fun DetailSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

/** One icon + caption cell in the floating swipe-up action bar. */
@Composable
private fun DetailIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    val fg = if (active) Color(0xFF7FD4FF) else tint
    Column(
        modifier = Modifier
            .width(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = fg
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Format a byte count as "1.2 MB" / "856 KB" / "123 B". */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "Unknown"
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

/**
 * AI Rating queue prefs — same SharedPreferences keys as the album's batch
 * menu ("pyqcr_ai_select" / "ai_selected_uris"; also read by AiSelectScreen).
 */
private fun loadImageAiSelectedUris(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
    return prefs.getStringSet("ai_selected_uris", emptySet()) ?: emptySet()
}

private fun saveImageAiSelectedUris(context: Context, uris: Set<String>) {
    val prefs = context.getSharedPreferences("pyqcr_ai_select", Context.MODE_PRIVATE)
    prefs.edit().putStringSet("ai_selected_uris", uris).apply()
}