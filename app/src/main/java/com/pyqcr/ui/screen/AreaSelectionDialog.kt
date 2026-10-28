package com.pyqcr.ui.screen

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.pyqcr.ai.BrushMask
import com.pyqcr.ai.BrushStroke
import com.pyqcr.ai.BrushStrokePoint
import com.pyqcr.ai.CropRect
import com.pyqcr.ai.EditSelection

/** Selection tool: drag a rectangle, or paint a brush mask (transparent outside). */
private enum class SelectionMode { RECT, BRUSH }

/**
 * Full-screen dialog that lets the user select the edit region on a source
 * image, in one of two modes:
 *
 *  - Rectangle (drag): returns an [EditSelection] with a normalized [CropRect].
 *  - Brush (paint): returns an [EditSelection] with a [BrushMask]; everything
 *    outside the painted strokes becomes transparent in the AI input.
 *
 * Coordinates are 0f..1f relative to the image itself (not the container),
 * accounting for letterboxing from ContentScale.Fit.
 *
 * Two fingers pinch-zoom / pan the PREVIEW only: strokes are stored in
 * image-normalized space, so the selection tracks the image at any zoom.
 * Every completed stroke is APPENDED to the list — painting several times
 * accumulates all brushed areas (undo pops the newest stroke).
 */
@Composable
fun AreaSelectionDialog(
    imageUri: String,
    initialSelection: EditSelection? = null,
    onConfirm: (EditSelection) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(SelectionMode.RECT) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    // Snapshot list — commit is an append, never a whole-list replacement,
    // so no stale-capture path can drop earlier strokes.
    val strokes = remember { mutableStateListOf<BrushStroke>() }
    var activeStroke by remember { mutableStateOf<BrushStroke?>(null) }
    var brushSize by remember { mutableFloatStateOf(0.04f) }
    var eraser by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var imageDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current

    // Load intrinsic image dimensions so we can compute the actual
    // displayed image bounds within the container (ContentScale.Fit
    // centers the image with letterboxing).
    LaunchedEffect(imageUri) {
        imageDims = loadImageDimensions(context, imageUri)
    }

    // Pre-fill the tool with an existing selection (e.g. re-opening the dialog
    // to refine a previously selected source). A stored brush mask wins over
    // the default whole-image crop rect, and its strokes are restored so new
    // strokes append to them instead of starting over.
    LaunchedEffect(imageUri, initialSelection) {
        if (initialSelection?.hasMask == true) {
            mode = SelectionMode.BRUSH
            strokes.clear()
            strokes.addAll(initialSelection.mask!!.strokes)
        } else if (initialSelection?.cropRect?.isValid() == true) {
            mode = SelectionMode.RECT
        }
    }

    /** Map a layer-space position to image-normalized 0..1 space, clamped. */
    fun normalizedPoint(pos: Offset): BrushStrokePoint {
        val dims = imageDims
        if (dims != null && containerSize.width > 0 && containerSize.height > 0) {
            val imgW = dims.first.toFloat()
            val imgH = dims.second.toFloat()
            val scale = minOf(containerSize.width / imgW, containerSize.height / imgH)
            val displayW = imgW * scale
            val displayH = imgH * scale
            val offsetX = (containerSize.width - displayW) / 2f
            val offsetY = (containerSize.height - displayH) / 2f
            return BrushStrokePoint(
                ((pos.x - offsetX) / displayW).coerceIn(0f, 1f),
                ((pos.y - offsetY) / displayH).coerceIn(0f, 1f)
            )
        }
        return BrushStrokePoint(
            (pos.x / containerSize.width).coerceIn(0f, 1f),
            (pos.y / containerSize.height).coerceIn(0f, 1f)
        )
    }

    /** Map an image-normalized point back to layer space for the overlay. */
    fun containerPoint(n: BrushStrokePoint): Offset {
        val dims = imageDims
        if (dims != null && containerSize.width > 0 && containerSize.height > 0) {
            val imgW = dims.first.toFloat()
            val imgH = dims.second.toFloat()
            val scale = minOf(containerSize.width / imgW, containerSize.height / imgH)
            val displayW = imgW * scale
            val displayH = imgH * scale
            val offsetX = (containerSize.width - displayW) / 2f
            val offsetY = (containerSize.height - displayH) / 2f
            return Offset(n.x * displayW + offsetX, n.y * displayH + offsetY)
        }
        return Offset(n.x * containerSize.width, n.y * containerSize.height)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar: close, title, undo (brush), zoom reset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = Color.White
                        )
                    }
                    Text(
                        if (mode == SelectionMode.RECT) "Select area to edit" else "Paint area to keep",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    if (mode == SelectionMode.BRUSH && strokes.isNotEmpty()) {
                        TextButton(onClick = {
                            strokes.removeAt(strokes.lastIndex)
                            activeStroke = null
                        }) { Text("Undo", color = Color.White) }
                    }
                    if (zoom > 1f) {
                        TextButton(onClick = {
                            zoom = 1f
                            panX = 0f
                            panY = 0f
                        }) { Text("Reset zoom", color = Color.White) }
                    }
                }

                // Rectangle / Brush tool switcher
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    SegmentedButton(
                        selected = mode == SelectionMode.RECT,
                        onClick = {
                            mode = SelectionMode.RECT
                            dragStart = null
                            dragEnd = null
                            activeStroke = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text("Rectangle")
                    }
                    SegmentedButton(
                        selected = mode == SelectionMode.BRUSH,
                        onClick = { mode = SelectionMode.BRUSH },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text("Brush")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Full-screen image area with gesture handling for the active
                // tool. graphicsLayer applies the pinch-zoom and pointerInput
                // sits INSIDE it, so delivered positions are already in the
                // image's own (pre-zoom) space — painting tracks the zoomed
                // image with no manual transform math.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panX
                            translationY = panY
                        }
                        .pointerInput(mode) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (mode == SelectionMode.RECT) {
                                    dragStart = down.position
                                    dragEnd = down.position
                                    var pinch = false
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.count { it.pressed } >= 2) {
                                            pinch = true
                                        }
                                        if (!pinch) {
                                            val change = event.changes.firstOrNull { it.pressed }
                                            if (change != null) {
                                                change.consume()
                                                dragEnd = change.position
                                            }
                                        } else {
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                                            if (zoom > 1f) {
                                                panX += panChange.x
                                                panY += panChange.y
                                            } else {
                                                panX = 0f
                                                panY = 0f
                                            }
                                            event.changes.forEach {
                                                if (it.positionChanged()) it.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                } else {
                                    val pts = mutableListOf<BrushStrokePoint>()
                                    fun addPoint(pos: Offset) {
                                        val n = normalizedPoint(pos)
                                        val last = pts.lastOrNull()
                                        if (last == null) {
                                            pts.add(n)
                                        } else {
                                            val dx = n.x - last.x
                                            val dy = n.y - last.y
                                            if (dx * dx + dy * dy > 0.000009f) pts.add(n)
                                        }
                                        // Refresh the overlay on every point so the
                                        // stroke shows while the finger is still down.
                                        activeStroke = BrushStroke(
                                            points = pts.toList(),
                                            radius = brushSize,
                                            eraser = eraser
                                        )
                                    }
                                    addPoint(down.position)
                                    var pinch = false
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (!pinch && pressedCount >= 2) {
                                            pinch = true
                                            // Commit what was painted so far before
                                            // the pinch takes over — nothing is lost.
                                            if (pts.isNotEmpty()) {
                                                strokes.add(
                                                    BrushStroke(
                                                        points = pts.toList(),
                                                        radius = brushSize,
                                                        eraser = eraser
                                                    )
                                                )
                                                pts.clear()
                                            }
                                            activeStroke = null
                                        }
                                        if (!pinch) {
                                            val change = event.changes.firstOrNull { it.pressed }
                                            if (change != null) {
                                                change.consume()
                                                addPoint(change.position)
                                            }
                                        } else {
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                                            if (zoom > 1f) {
                                                panX += panChange.x
                                                panY += panChange.y
                                            } else {
                                                panX = 0f
                                                panY = 0f
                                            }
                                            event.changes.forEach {
                                                if (it.positionChanged()) it.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    if (!pinch && pts.isNotEmpty()) {
                                        strokes.add(
                                            BrushStroke(
                                                points = pts.toList(),
                                                radius = brushSize,
                                                eraser = eraser
                                            )
                                        )
                                    }
                                    activeStroke = null
                                }
                            }
                        }
                        .onSizeChanged {
                            containerSize = Size(it.width.toFloat(), it.height.toFloat())
                        }
                ) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = "First image for area selection",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    if (mode == SelectionMode.RECT) {
                        // Selection rectangle drawn with Canvas overlay
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val start = dragStart ?: return@Canvas
                            val end = dragEnd ?: return@Canvas
                            val left = minOf(start.x, end.x)
                            val top = minOf(start.y, end.y)
                            val right = maxOf(start.x, end.x)
                            val bottom = maxOf(start.y, end.y)
                            val w = size.width
                            val h = size.height

                            // Dim outside the selection via four rects (avoids BlendMode issues)
                            val dim = Color(0x80000000)
                            drawRect(dim, topLeft = Offset.Zero, size = Size(w, top))
                            drawRect(dim, topLeft = Offset(0f, bottom), size = Size(w, h - bottom))
                            drawRect(dim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
                            drawRect(dim, topLeft = Offset(right, top), size = Size(w - right, bottom - top))

                            // Selection border
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    } else {
                        // Painted strokes overlay — pre-rendered to an offscreen
                        // Bitmap with native Canvas APIs so overlapping strokes
                        // don't compound ("layering" effect) and eraser strokes
                        // punch real holes (PorterDuff CLEAR). The Bitmap is
                        // composited into the Compose Canvas at uniform alpha.
                        var canvasW by remember { mutableStateOf(0) }
                        var canvasH by remember { mutableStateOf(0) }
                        val bitmap = remember(canvasW, canvasH) {
                            if (canvasW > 0 && canvasH > 0)
                                android.graphics.Bitmap.createBitmap(
                                    canvasW, canvasH,
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                            else null
                        }
                        val nativeCanvas = remember(bitmap) {
                            bitmap?.let { android.graphics.Canvas(it) }
                        }

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { canvasW = it.width; canvasH = it.height }
                        ) {
                            val bmp = bitmap ?: return@Canvas
                            val nc = nativeCanvas ?: return@Canvas
                            bmp.eraseColor(0) // transparent
                            val reference = minOf(size.width, size.height)

                            // Strokes render in temporal order: paint strokes add
                            // opaque white, eraser strokes punch holes (CLEAR)
                            // through only what was painted before them. Every
                            // committed stroke is in the list, so all brushed
                            // areas count — not just the first.
                            val allStrokes: List<BrushStroke> =
                                if (activeStroke != null) strokes + activeStroke!! else strokes

                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                isAntiAlias = true
                                style = android.graphics.Paint.Style.STROKE
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                            }
                            val erase = android.graphics.Paint().apply {
                                isAntiAlias = true
                                style = android.graphics.Paint.Style.STROKE
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                                xfermode = android.graphics.PorterDuffXfermode(
                                    android.graphics.PorterDuff.Mode.CLEAR
                                )
                            }
                            for (stroke in allStrokes) {
                                if (stroke.points.isEmpty()) continue
                                val strokePx = stroke.radius * reference
                                val p = if (stroke.eraser) erase else paint
                                p.strokeWidth = strokePx
                                if (stroke.points.size == 1) {
                                    val c = containerPoint(stroke.points[0])
                                    nc.drawCircle(c.x, c.y, strokePx / 2f, p)
                                } else {
                                    val path = android.graphics.Path()
                                    stroke.points.forEachIndexed { i, pt ->
                                        val o = containerPoint(pt)
                                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                                    }
                                    nc.drawPath(path, p)
                                }
                            }

                            // Composite at uniform alpha — all painted areas
                            // share the same transparency.
                            drawImage(
                                image = bmp.asImageBitmap(),
                                alpha = 0.4f
                            )
                        }
                    }

                    // Instruction overlay when nothing has been drawn yet
                    val noSelection = if (mode == SelectionMode.RECT) {
                        dragStart == null
                    } else {
                        strokes.isEmpty()
                    }
                    if (noSelection) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = Color(0x88000000),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    if (mode == SelectionMode.RECT)
                                        "Drag to select area — pinch with two fingers to zoom"
                                    else
                                        "Paint to select area — pinch with two fingers to zoom",
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                // Brush tool controls (size / eraser / undo / clear)
                if (mode == SelectionMode.BRUSH) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Size ${(brushSize * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.width(64.dp)
                        )
                        Slider(
                            value = brushSize,
                            onValueChange = { brushSize = it },
                            valueRange = 0.01f..0.15f,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = eraser,
                            onClick = { eraser = !eraser },
                            label = { Text("Eraser") }
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { strokes.clear(); activeStroke = null },
                            enabled = strokes.isNotEmpty()
                        ) {
                            Text("Clear", color = Color.White)
                        }
                    }
                }

                // Action row — fixed generous bottom padding (some ROMs deliver
                // no window insets into Dialog composables; HONOR Parallel Space
                // overlays its gesture area at the very bottom, which made the
                // buttons untappable at 24.dp).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 56.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            when (mode) {
                                SelectionMode.RECT -> {
                                    val s = dragStart ?: return@Button
                                    val e = dragEnd ?: return@Button
                                    if (containerSize.width <= 0 || containerSize.height <= 0) return@Button
                                    val rect = dragToCropRect(
                                        s, e, containerSize, imageDims
                                    )
                                    if (rect.isValid()) onConfirm(EditSelection(cropRect = rect))
                                }
                                SelectionMode.BRUSH -> {
                                    if (strokes.isEmpty()) return@Button
                                    onConfirm(EditSelection(mask = BrushMask(strokes.toList())))
                                }
                            }
                        },
                        enabled = when (mode) {
                            SelectionMode.RECT -> dragStart != null && dragEnd != null
                            SelectionMode.BRUSH -> strokes.isNotEmpty()
                        }
                    ) { Text("Confirm") }
                }
            }
        }
    }
}

/**
 * Convert drag coordinates (in container/layer space) to a normalized
 * [CropRect] (in image space), correctly accounting for ContentScale.Fit
 * letterboxing — the image is centered, so empty margins must be subtracted.
 */
private fun dragToCropRect(
    start: Offset,
    end: Offset,
    containerSize: Size,
    imageDims: Pair<Int, Int>?
): CropRect {
    if (imageDims == null || containerSize.width <= 0f || containerSize.height <= 0f) {
        return CropRect(0f, 0f, 1f, 1f)
    }
    val imgW = imageDims.first.toFloat()
    val imgH = imageDims.second.toFloat()
    val scale = minOf(containerSize.width / imgW, containerSize.height / imgH)
    val displayW = imgW * scale
    val displayH = imgH * scale
    val offsetX = (containerSize.width - displayW) / 2f
    val offsetY = (containerSize.height - displayH) / 2f
    val left = ((minOf(start.x, end.x) - offsetX) / displayW).coerceIn(0f, 1f)
    val top = ((minOf(start.y, end.y) - offsetY) / displayH).coerceIn(0f, 1f)
    val right = ((maxOf(start.x, end.x) - offsetX) / displayW).coerceIn(0f, 1f)
    val bottom = ((maxOf(start.y, end.y) - offsetY) / displayH).coerceIn(0f, 1f)
    return CropRect(left, top, right, bottom)
}

/**
 * Decode the image bounds (inJustDecodeBounds) plus EXIF orientation, so the
 * mapping matches what Coil displays after auto-rotation.
 */
private fun loadImageDimensions(context: Context, uriStr: String): Pair<Int, Int>? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    return try {
        if (uriStr.startsWith("content://")) {
            val uri = Uri.parse(uriStr)
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        } else {
            BitmapFactory.decodeFile(uriStr, opts)
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        var w = opts.outWidth
        var h = opts.outHeight

        // Check EXIF orientation — Coil applies it, so the displayed
        // dimensions may be swapped relative to the raw header values.
        try {
            val exif = if (uriStr.startsWith("content://")) {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ExifInterface(input)
                }
            } else {
                ExifInterface(uriStr)
            }
            if (exif != null) {
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE
                ) {
                    w = opts.outHeight
                    h = opts.outWidth
                }
            }
        } catch (_: Exception) {
            // EXIF not available or readable — use raw dimensions
        }

        Pair(w, h)
    } catch (e: Exception) {
        null
    }
}
