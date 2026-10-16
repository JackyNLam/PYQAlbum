package com.pyqcr.ui.screen

import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.pyqcr.util.MotionPhotoExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    onOpenAiEdit: () -> Unit = {}
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

    // Pinch-zoom state for the enlarged image (scale 1 = fitted, >1 = zoomed in).
    // Swipe navigation is only active while scale == 1; while zoomed, single-finger
    // drags pan the image instead.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Size of the image viewport (px) — used to clamp the pan so the zoomed
    // image can never be dragged entirely out of view.
    var viewSize by remember { mutableStateOf(Size.Zero) }

    // Motion-photo state: [motionVideoPath] is the cache file with the embedded
    // MP4 (null = not a motion photo); [showMotionVideo] toggles the video surface.
    var motionVideoPath by remember(imageUri) { mutableStateOf<String?>(null) }
    var showMotionVideo by remember { mutableStateOf(false) }

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

    // Detect and extract the embedded video of a motion photo (JPEG + appended
    // MP4). GIFs and animated WebP play directly through Coil, so no extraction.
    LaunchedEffect(imageUri) {
        motionVideoPath = withContext(Dispatchers.IO) {
            MotionPhotoExtractor.extractToCache(context, imageUri)?.absolutePath
        }
        showMotionVideo = false
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
                    .onSizeChanged {
                        viewSize = Size(it.width.toFloat(), it.height.toFloat())
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
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
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            // Gesture ended at scale 1 — decide swipe navigation
                            if (!transformMode) {
                                if (verticalAccum > swipeThreshold) {
                                    onBack()
                                } else if (verticalAccum < -swipeThreshold) {
                                    showDetailPanel = true
                                    showControls = false
                                } else if (horizontalAccum < -swipeThreshold) {
                                    onNextImage()
                                } else if (horizontalAccum > swipeThreshold) {
                                    onPreviousImage()
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

                // Motion photo: show a play button; tapping it plays the
                // embedded video over the still frame.
                if (!showMotionVideo && motionVideoPath != null) {
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
                    Text(
                        text = "Path: ${fullPath ?: imageUri}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (imageItem?.width != null && imageItem?.height != null) {
                        Text(
                            text = "${imageItem?.width} x ${imageItem?.height}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "Size: ${formatFileSize(imageItem?.sizeBytes ?: 0L)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

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
                                            // Auto-delete tag if it has no images left
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

                    Spacer(Modifier.height(16.dp))

                    // ====== AI actions (below the tag area) ======
                    Text(
                        text = "AI Actions",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenAiRating,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("AI Rating")
                        }
                        Button(
                            onClick = onOpenAiEdit,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("AI Edit")
                        }
                    }
                }
            }
        }
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
 * Plays the motion-photo video extracted by [MotionPhotoExtractor] using
 * [MediaPlayer] on a [SurfaceView] (AndroidView interop). The player is
 * released when the composable leaves composition.
 */
@Composable
private fun MotionPhotoPlayer(
    videoPath: String,
    onClose: () -> Unit
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            try {
                                val mp = MediaPlayer().apply {
                                    setDataSource(videoPath)
                                    setDisplay(holder)
                                    isLooping = true
                                    setVolume(0f, 0f) // motion clips are silent
                                    prepare()
                                    start()
                                }
                                player = mp
                            } catch (e: Exception) {
                                // Unsupported codec or corrupt clip — the still
                                // frame behind stays visible
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            try { player?.release() } catch (e: Exception) {}
                            player = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = {
                try { player?.release() } catch (e: Exception) {}
                player = null
            }
        )

        // Close button over the video
        TextButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        ) {
            Text("✕ Close motion", color = Color.White)
        }
    }
}