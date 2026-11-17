package com.pyqcr.ui.screen

import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.AppSnackbarHost
import com.pyqcr.util.EditMark
import com.pyqcr.util.EditOverlay
import com.pyqcr.util.EditStrokePoint
import com.pyqcr.util.EditTextItem
import com.pyqcr.util.ImageEditDoc
import com.pyqcr.util.ImageEditEngine
import com.pyqcr.util.ImageEditSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EditTool(val label: String, val icon: ImageVector) {
    CROP("Crop", Icons.Default.Crop),
    PEN("Pen", Icons.Default.Brush),
    PIXELATE("Pixelate", Icons.Default.BlurOn),
    CUTOUT("Reveal", Icons.Default.Gesture),
    TEXT("Text", Icons.Default.TextFields)
}

private val PenColors = listOf(
    AndroidColor.RED,
    AndroidColor.YELLOW,
    AndroidColor.GREEN,
    AndroidColor.CYAN,
    AndroidColor.BLUE,
    AndroidColor.MAGENTA,
    AndroidColor.WHITE,
    AndroidColor.BLACK
)

private val TextSizeChoices = listOf(
    "Small" to 0.03f,
    "Medium" to 0.055f,
    "Large" to 0.09f
)

/**
 * Image Edit: non-AI editing of photos.
 *
 *  - Crop (drag a rectangle, apply)
 *  - Resize (same width/pad-square behavior as the batch resize)
 *  - Draw (color pen) and Pixelate strokes
 *  - Text labels with size and color
 *  - Layers: stack several photos at the same position and paint a free-shape
 *    cutout on the top layer so the photo underneath shows through
 *
 * The flattened result is saved as a new image in Pictures/PYQAlbum.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // The images chosen in the folder view's batch menu (⋮ → Edit images).
    // Only these are offered here — both the initial picker and "add layer".
    // State (not a plain remember) because the screen can also empty the pool:
    // removing a photo here must stick, not come back on the next gallery emit.
    var poolUris by remember { mutableStateOf(ImageEditSelectionStore.load(context)) }

    var allImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    // URIs that still resolve to a real gallery row. A photo deleted (in the
    // app, in Parallel Space, or by another gallery) must disappear from the
    // picker instead of lingering as an unopenable placeholder.
    var editableUris by remember { mutableStateOf(poolUris) }
    var prunedCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(poolUris) {
        if (poolUris.isEmpty()) {
            allImages = emptyList()
            editableUris = emptyList()
            return@LaunchedEffect
        }
        repository.getAllImages().collect { list ->
            val byUri = list.associateBy { it.uri }
            val alive = poolUris.filter { byUri.containsKey(it) }
            allImages = alive.mapNotNull { byUri[it] }
            editableUris = alive
            if (alive.size != poolUris.size) {
                prunedCount = poolUris.size - alive.size
                // Persist the pruned selection so the next visit starts clean.
                ImageEditSelectionStore.save(context, alive)
            }
        }
    }

    /**
     * Replace the pool (removing one photo, or clearing it) and persist the
     * change, so the folder view's "Edit images" selection starts from the
     * same list next time. The effect above re-fills the lists from the flow.
     */
    fun setPool(uris: List<String>) {
        if (uris.isEmpty()) {
            ImageEditSelectionStore.clear(context)
            prunedCount = 0
        } else {
            ImageEditSelectionStore.save(context, uris)
        }
        poolUris = uris
    }

    var doc by remember { mutableStateOf<ImageEditDoc?>(null) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var tool by remember { mutableStateOf(EditTool.PEN) }
    var penColor by remember { mutableIntStateOf(AndroidColor.RED) }
    var penRadius by remember { mutableFloatStateOf(0.012f) }
    var activeLayer by remember { mutableIntStateOf(-1) }
    var activeStroke by remember { mutableStateOf<List<EditStrokePoint>>(emptyList()) }
    var cropStart by remember { mutableStateOf<EditStrokePoint?>(null) }
    var cropEnd by remember { mutableStateOf<EditStrokePoint?>(null) }
    var showResize by remember { mutableStateOf(false) }
    var showRotate by remember { mutableStateOf(false) }
    var showTextOptions by remember { mutableStateOf(false) }
    var showLayerPicker by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf<EditTextItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    val undoStack = remember { mutableStateListOf<ImageEditDoc>() }

    fun pushUndo(current: ImageEditDoc) {
        undoStack.add(current)
        while (undoStack.size > 12) undoStack.removeAt(0)
    }

    fun resetEditor() {
        doc = null
        preview = null
        cropStart = null
        cropEnd = null
        activeStroke = emptyList()
        pendingText = null
        activeLayer = -1
        undoStack.clear()
    }

    fun openImage(uri: String) {
        scope.launch {
            busy = true
            val loaded = withContext(Dispatchers.IO) {
                ImageEditEngine.decodeBitmap(context, uri)
            }
            busy = false
            if (loaded == null) {
                snackbar.showSnackbar("Could not open that image")
            } else {
                doc = ImageEditDoc(base = loaded)
                activeLayer = -1
                tool = EditTool.PEN
                undoStack.clear()
            }
        }
    }

    fun addLayer(uri: String) {
        val current = doc ?: return
        scope.launch {
            busy = true
            val layer = withContext(Dispatchers.IO) {
                ImageEditEngine.decodeBitmap(context, uri)?.let {
                    ImageEditEngine.fitOverlayToBase(it, current.base)
                }
            }
            busy = false
            if (layer == null) {
                snackbar.showSnackbar("Could not add that photo")
            } else {
                pushUndo(current)
                val updated = current.copy(overlays = current.overlays + EditOverlay(layer))
                doc = updated
                activeLayer = updated.overlays.lastIndex
                tool = EditTool.CUTOUT
            }
        }
    }

    // Re-render the flattened preview whenever the document changes.
    LaunchedEffect(doc) {
        val d = doc
        if (d == null) {
            preview = null
            return@LaunchedEffect
        }
        preview = withContext(Dispatchers.Default) { ImageEditEngine.render(d) }
    }

    fun commitMark(points: List<EditStrokePoint>) {
        val current = doc ?: return
        if (points.isEmpty()) return
        if (tool == EditTool.CUTOUT) {
            val idx = activeLayer
            if (idx < 0 || idx >= current.overlays.size) {
                scope.launch {
                    snackbar.showSnackbar("Add a layer first, then paint where it should be see-through")
                }
                return
            }
            pushUndo(current)
            val mark = EditMark(points = points, radius = penRadius)
            val overlays = current.overlays.toMutableList()
            val layer = overlays[idx]
            overlays[idx] = layer.copy(cutout = layer.cutout + mark)
            doc = current.copy(overlays = overlays)
        } else {
            pushUndo(current)
            val mark = EditMark(
                points = points,
                radius = penRadius,
                color = if (tool == EditTool.PIXELATE) AndroidColor.WHITE else penColor,
                pixelate = tool == EditTool.PIXELATE
            )
            doc = current.copy(marks = current.marks + mark)
        }
    }

    fun placeText(item: EditTextItem) {
        val current = doc ?: return
        pushUndo(current)
        doc = current.copy(texts = current.texts + item)
        pendingText = null
    }

    fun saveDocument() {
        val current = doc ?: return
        scope.launch {
            busy = true
            val saved = withContext(Dispatchers.IO) {
                ImageEditEngine.saveDocument(context, current)
            }
            busy = false
            if (saved == null) {
                snackbar.showSnackbar("Save failed")
            } else {
                snackbar.showSnackbar("Saved to Pictures/PYQAlbum")
            }
        }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Image Edit") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (doc != null) resetEditor() else onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (doc != null) {
                        IconButton(
                            onClick = {
                                if (undoStack.isNotEmpty()) {
                                    doc = undoStack.removeAt(undoStack.lastIndex)
                                    cropStart = null
                                    cropEnd = null
                                    activeStroke = emptyList()
                                }
                            },
                            enabled = undoStack.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo")
                        }
                        TextButton(onClick = { saveDocument() }, enabled = !busy) {
                            Text("Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val current = doc
        if (current == null) {
            if (editableUris.isEmpty()) {
                NoSelectionScreen(
                    padding = padding,
                    onBack = onBack,
                    prunedCount = prunedCount
                )
            } else {
                EditorImagePicker(
                    images = allImages,
                    padding = padding,
                    busy = busy,
                    onPick = { openImage(it) },
                    onRemove = { uri -> setPool(editableUris.filterNot { it == uri }) },
                    onClear = {
                        val n = editableUris.size
                        setPool(emptyList())
                        scope.launch {
                            snackbar.showSnackbar("Removed $n photo(s) from the edit pool")
                        }
                    }
                )
            }
        } else {
            val bitmap = preview ?: current.base
            val imageW = bitmap.width
            val imageH = bitmap.height

            fun toNormalized(pos: Offset): EditStrokePoint? {
                val rect = ImageEditEngine.fitRect(
                    containerSize.width,
                    containerSize.height,
                    imageW,
                    imageH
                )
                if (rect.width() <= 0f || rect.height() <= 0f) return null
                if (pos.x < rect.left || pos.x > rect.right ||
                    pos.y < rect.top || pos.y > rect.bottom
                ) return null
                return EditStrokePoint(
                    ((pos.x - rect.left) / rect.width()).coerceIn(0f, 1f),
                    ((pos.y - rect.top) / rect.height()).coerceIn(0f, 1f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                        .onSizeChanged {
                            containerSize = Size(it.width.toFloat(), it.height.toFloat())
                        }
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Edit preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Interactive overlay: gesture capture + live stroke/crop drawing.
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(tool, doc, activeLayer, penColor, penRadius) {
                                when (tool) {
                                    EditTool.CROP -> detectDragGestures(
                                        onDragStart = { offset ->
                                            cropStart = toNormalized(offset)
                                            cropEnd = cropStart
                                        },
                                        onDragEnd = {},
                                        onDragCancel = {
                                            cropStart = null
                                            cropEnd = null
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            toNormalized(change.position)?.let { cropEnd = it }
                                        }
                                    )
                                    EditTool.TEXT -> detectTapGestures { offset ->
                                        val pending = pendingText
                                        val point = toNormalized(offset)
                                        if (pending != null && point != null) {
                                            placeText(pending.copy(x = point.x, y = point.y))
                                        }
                                    }
                                    else -> detectDragGestures(
                                        onDragStart = { offset ->
                                            val p = toNormalized(offset)
                                            activeStroke = if (p == null) emptyList() else listOf(p)
                                        },
                                        onDragEnd = {
                                            commitMark(activeStroke)
                                            activeStroke = emptyList()
                                        },
                                        onDragCancel = { activeStroke = emptyList() },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val p = toNormalized(change.position) ?: return@detectDragGestures
                                            val last = activeStroke.lastOrNull()
                                            if (last == null || last.x != p.x || last.y != p.y) {
                                                activeStroke = activeStroke + p
                                            }
                                        }
                                    )
                                }
                            }
                    ) {
                        val rect = ImageEditEngine.fitRect(
                            size.width,
                            size.height,
                            imageW,
                            imageH
                        )
                        fun px(p: EditStrokePoint) = Offset(
                            rect.left + p.x * rect.width(),
                            rect.top + p.y * rect.height()
                        )

                        // Crop selection: dim everything outside, white border.
                        val cs = cropStart
                        val ce = cropEnd
                        if (tool == EditTool.CROP && cs != null && ce != null) {
                            val left = minOf(cs.x, ce.x) * rect.width() + rect.left
                            val top = minOf(cs.y, ce.y) * rect.height() + rect.top
                            val right = maxOf(cs.x, ce.x) * rect.width() + rect.left
                            val bottom = maxOf(cs.y, ce.y) * rect.height() + rect.top
                            val dim = Color(0x80000000)
                            drawRect(dim, Offset.Zero, Size(size.width, top))
                            drawRect(dim, Offset(0f, bottom), Size(size.width, size.height - bottom))
                            drawRect(dim, Offset(0f, top), Size(left, bottom - top))
                            drawRect(dim, Offset(right, top), Size(size.width - right, bottom - top))
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }

                        // Live stroke.
                        if (activeStroke.isNotEmpty()) {
                            val widthPx = penRadius * minOf(rect.width(), rect.height())
                            if (activeStroke.size == 1) {
                                drawCircle(
                                    color = if (tool == EditTool.CUTOUT) Color.White
                                    else Color(penColor),
                                    radius = widthPx / 2f,
                                    center = px(activeStroke[0])
                                )
                            } else {
                                val path = Path()
                                activeStroke.forEachIndexed { i, p ->
                                    val o = px(p)
                                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                                }
                                drawPath(
                                    path = path,
                                    color = if (tool == EditTool.CUTOUT) Color.White
                                    else if (tool == EditTool.PIXELATE) Color(0xCC00E5FF)
                                    else Color(penColor),
                                    style = Stroke(
                                        width = widthPx,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    }

                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White
                        )
                    }

                    // Contextual hint.
                    val hint = when {
                        pendingText != null -> "Tap the image to place the text"
                        tool == EditTool.CROP -> "Drag a rectangle, then tap Apply crop"
                        tool == EditTool.CUTOUT && activeLayer < 0 ->
                            "Add a layer first — painted areas reveal the photo underneath"
                        tool == EditTool.CUTOUT ->
                            "Paint the area where the photo underneath should show"
                        tool == EditTool.PIXELATE -> "Paint to pixelate"
                        tool == EditTool.TEXT -> "Use the Text tool to add a label"
                        else -> "Paint with the pen"
                    }
                    Surface(
                        color = Color(0x99000000),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            hint,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                EditorControls(
                    doc = current,
                    tool = tool,
                    penColor = penColor,
                    penRadius = penRadius,
                    activeLayer = activeLayer,
                    cropReady = cropStart != null && cropEnd != null,
                    onTool = { tool = it },
                    onColor = { penColor = it },
                    onRadius = { penRadius = it },
                    onActiveLayer = { activeLayer = it },
                    onAddLayer = { showLayerPicker = true },
                    onDeleteLayer = {
                        val idx = activeLayer
                        if (idx in current.overlays.indices) {
                            pushUndo(current)
                            val overlays = current.overlays.toMutableList()
                            overlays.removeAt(idx)
                            doc = current.copy(overlays = overlays)
                            activeLayer = overlays.lastIndex
                        }
                    },
                    onApplyCrop = {
                        val cs = cropStart
                        val ce = cropEnd
                        if (cs != null && ce != null &&
                            kotlin.math.abs(cs.x - ce.x) > 0.01f &&
                            kotlin.math.abs(cs.y - ce.y) > 0.01f
                        ) {
                            pushUndo(current)
                            doc = ImageEditEngine.crop(current, cs.x, cs.y, ce.x, ce.y)
                            cropStart = null
                            cropEnd = null
                            tool = EditTool.PEN
                        }
                    },
                    onResize = { showResize = true },
                    onRotate = { showRotate = true },
                    onText = { showTextOptions = true }
                )
            }

            if (showResize) {
                EditResizeDialog(
                    imageWidth = current.base.width,
                    onDismiss = { showResize = false },
                    onConfirm = { width, padSquare, padColor ->
                        showResize = false
                        pushUndo(current)
                        doc = ImageEditEngine.resize(current, width, padSquare, padColor)
                    }
                )
            }

            if (showRotate) {
                EditRotateDialog(
                    onDismiss = { showRotate = false },
                    onConfirm = { degrees ->
                        showRotate = false
                        pushUndo(current)
                        doc = ImageEditEngine.rotate(current, degrees)
                    }
                )
            }

            if (showTextOptions) {
                EditTextDialog(
                    onDismiss = { showTextOptions = false },
                    onConfirm = { text, size, color ->
                        showTextOptions = false
                        pendingText = EditTextItem(
                            text = text,
                            x = 0.5f,
                            y = 0.5f,
                            sizeFraction = size,
                            color = color
                        )
                        tool = EditTool.TEXT
                    }
                )
            }

            if (showLayerPicker) {
                AlertDialog(
                    onDismissRequest = { showLayerPicker = false },
                    title = { Text("Add photo as layer") },
                    text = {
                        if (allImages.isEmpty()) {
                            Text("No selected photos to add")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(80.dp),
                                modifier = Modifier.height(360.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(allImages, key = { it.uri }) { image ->
                                    AsyncImage(
                                        model = image.uri,
                                        contentDescription = image.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable {
                                                showLayerPicker = false
                                                addLayer(image.uri)
                                            }
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLayerPicker = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

// ------------------------------------------------------------------ picker

/**
 * Shown when Image Edit is opened without a batch selection (e.g. from the
 * drawer). Selection lives in the folder view's ⋮ menu, so there is nothing
 * to edit here until the user picks photos there.
 */
@Composable
private fun NoSelectionScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
    prunedCount: Int = 0
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Brush,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "No photos selected",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (prunedCount > 0) {
                "$prunedCount selected photo(s) are no longer in the gallery. " +
                    "Select photos again from the folder view, then open the ⋮ " +
                    "menu and choose \"Edit images\"."
            } else {
                "In the folder view, select one or more photos, then open the ⋮ menu " +
                    "and choose \"Edit images\". Only those photos are shown here — as " +
                    "the edit target and as layers."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text("Go back") }
    }
}

@Composable
private fun EditorImagePicker(
    images: List<ImageItem>,
    padding: PaddingValues,
    busy: Boolean,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
        ) {
            Text(
                "Pick a photo to edit",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
            // The pool is only editable from the folder view otherwise; this
            // empties it (and the saved selection) without leaving the editor.
            TextButton(onClick = onClear) { Text("Clear") }
        }
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(images, key = { it.uri }) { image ->
                PoolTile(
                    image = image,
                    corner = 6,
                    onUse = { onPick(image.uri) },
                    onRemove = { onRemove(image.uri) }
                )
            }
        }
    }
}

/**
 * One photo of the selected pool. Tapping it uses the photo (as the edit
 * target or as a layer); the corner button takes it out of the pool, which
 * also updates the saved "Edit images" selection.
 */
@Composable
private fun PoolTile(
    image: ImageItem,
    corner: Int,
    onUse: () -> Unit,
    onRemove: () -> Unit
) {
    Box(Modifier.aspectRatio(1f)) {
        AsyncImage(
            model = image.uri,
            contentDescription = image.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(corner.dp))
                .clickable(onClick = onUse)
        )
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White
        ) {
            Box(
                modifier = Modifier.clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove from the pool",
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- controls

@Composable
private fun EditorControls(
    doc: ImageEditDoc,
    tool: EditTool,
    penColor: Int,
    penRadius: Float,
    activeLayer: Int,
    cropReady: Boolean,
    onTool: (EditTool) -> Unit,
    onColor: (Int) -> Unit,
    onRadius: (Float) -> Unit,
    onActiveLayer: (Int) -> Unit,
    onAddLayer: () -> Unit,
    onDeleteLayer: () -> Unit,
    onApplyCrop: () -> Unit,
    onResize: () -> Unit,
    onRotate: () -> Unit,
    onText: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            val checkIcon: @Composable () -> Unit = {
                Icon(Icons.Default.Check, contentDescription = null)
            }
            // Layer strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = { onActiveLayer(-1) },
                    label = { Text("Base") },
                    leadingIcon = if (activeLayer < 0) checkIcon else null
                )
                doc.overlays.forEachIndexed { index, _ ->
                    AssistChip(
                        onClick = { onActiveLayer(index) },
                        label = { Text("Layer ${index + 1}") },
                        leadingIcon = if (activeLayer == index) checkIcon else null
                    )
                }
                AssistChip(
                    onClick = onAddLayer,
                    label = { Text("Add layer") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                )
                if (activeLayer in doc.overlays.indices) {
                    AssistChip(
                        onClick = onDeleteLayer,
                        label = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Tool strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EditTool.values().forEach { t ->
                    FilterChip(
                        selected = tool == t,
                        onClick = { onTool(t) },
                        label = { Text(t.label) },
                        leadingIcon = {
                            Icon(t.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = onText,
                    label = { Text("Add text") },
                    leadingIcon = {
                        Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                FilterChip(
                    selected = false,
                    onClick = onResize,
                    label = { Text("Resize") },
                    leadingIcon = {
                        Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                FilterChip(
                    selected = false,
                    onClick = onRotate,
                    label = { Text("Rotate") },
                    leadingIcon = {
                        Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }

            // Tool options
            when {
                tool == EditTool.PEN || tool == EditTool.PIXELATE || tool == EditTool.CUTOUT -> {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (tool == EditTool.PEN) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PenColors.forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color(c))
                                            .border(
                                                width = if (penColor == c) 3.dp else 1.dp,
                                                color = if (penColor == c)
                                                    MaterialTheme.colorScheme.primary
                                                else Color.Gray,
                                                shape = CircleShape
                                            )
                                            .clickable { onColor(c) }
                                    )
                                }
                            }
                        } else {
                            Text(
                                if (tool == EditTool.PIXELATE) "Pixelate brush" else "Reveal brush",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Size", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = penRadius,
                            onValueChange = onRadius,
                            valueRange = 0.004f..0.06f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                    }
                }
                tool == EditTool.CROP -> {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = onApplyCrop, enabled = cropReady) {
                            Text("Apply crop")
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs

/**
 * Free rotation: a slider for any angle plus the three quarter turns people
 * actually use. Applied to the flattened document, so earlier marks rotate with
 * the photo.
 */
@Composable
private fun EditRotateDialog(
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var angle by remember { mutableFloatStateOf(0f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rotate") },
        text = {
            Column {
                Text(
                    if (angle == 0f) "No rotation" else "${angle.toInt()}°",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = angle,
                    onValueChange = { angle = it },
                    valueRange = -180f..180f,
                    steps = 71
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { angle -= 90f }) { Text("−90°") }
                    TextButton(onClick = { angle += 90f }) { Text("+90°") }
                    TextButton(onClick = { angle = 180f }) { Text("180°") }
                    TextButton(onClick = { angle = 0f }) { Text("Reset") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = angle != 0f,
                onClick = { onConfirm(angle) }
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditResizeDialog(
    imageWidth: Int,
    onDismiss: () -> Unit,
    onConfirm: (width: Int, padSquare: Boolean, padColor: Int) -> Unit
) {
    var selected by remember { mutableIntStateOf(1000) }
    var custom by remember { mutableStateOf("") }
    var useCustom by remember { mutableStateOf(false) }
    var padSquare by remember { mutableStateOf(false) }
    var padColor by remember { mutableIntStateOf(AndroidColor.WHITE) }
    val width = if (useCustom) custom.toIntOrNull() ?: imageWidth else selected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resize image") },
        text = {
            Column {
                Text("Current width: ${imageWidth}px", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(500, 1000, 2000).forEach { w ->
                        FilterChip(
                            selected = !useCustom && selected == w,
                            onClick = { selected = w; useCustom = false },
                            label = { Text("${w}px") }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                FilterChip(
                    selected = useCustom,
                    onClick = { useCustom = !useCustom; if (!useCustom) custom = "" },
                    label = { Text("Custom") }
                )
                if (useCustom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it.filter { c -> c.isDigit() } },
                        label = { Text("Width in px") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.clickable { padSquare = !padSquare },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = padSquare, onCheckedChange = { padSquare = it })
                    Text("Pad to square", modifier = Modifier.padding(start = 4.dp))
                }
                if (padSquare) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            AndroidColor.WHITE,
                            AndroidColor.BLACK,
                            0xFF90CAF9.toInt(),
                            0xFFA5D6A7.toInt()
                        ).forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(
                                        width = if (padColor == c) 3.dp else 1.dp,
                                        color = if (padColor == c)
                                            MaterialTheme.colorScheme.primary
                                        else Color.Gray,
                                        shape = CircleShape
                                    )
                                    .clickable { padColor = c }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(width.coerceAtLeast(1), padSquare, padColor) },
                enabled = !useCustom || (custom.toIntOrNull() ?: 0) > 0
            ) { Text("Resize") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (text: String, size: Float, color: Int) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var sizeIndex by remember { mutableIntStateOf(1) }
    var color by remember { mutableIntStateOf(AndroidColor.WHITE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextSizeChoices.forEachIndexed { index, (label, _) ->
                        FilterChip(
                            selected = sizeIndex == index,
                            onClick = { sizeIndex = index },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AndroidColor.WHITE,
                        AndroidColor.BLACK,
                        AndroidColor.RED,
                        AndroidColor.YELLOW,
                        AndroidColor.GREEN,
                        AndroidColor.CYAN
                    ).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    width = if (color == c) 3.dp else 1.dp,
                                    color = if (color == c)
                                        MaterialTheme.colorScheme.primary
                                    else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text, TextSizeChoices[sizeIndex].second, color) },
                enabled = text.isNotBlank()
            ) { Text("Place") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}