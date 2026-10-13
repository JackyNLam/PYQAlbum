package com.pyqcr.ui.screen

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
    var strokes by remember { mutableStateOf<List<BrushStroke>>(emptyList()) }
    var brushSize by remember { mutableFloatStateOf(0.04f) }
    var eraser by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var imageDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val context = LocalContext.current

    // Load intrinsic image dimensions so we can compute the actual
    // displayed image bounds within the container (ContentScale.Fit
    // centers the image with letterboxing).
    LaunchedEffect(imageUri) {
        imageDims = loadImageDimensions(context, imageUri)
    }

    // Pre-fill the tool with an existing selection (e.g. re-opening the dialog
    // to refine a previously selected source).
    LaunchedEffect(imageUri, initialSelection) {
        if (initialSelection?.cropRect?.isValid() == true) {
            mode = SelectionMode.RECT
        } else if (initialSelection?.hasMask == true) {
            mode = SelectionMode.BRUSH
            strokes = initialSelection.mask!!.strokes
        }
    }

    /** Map a container-space position to image-normalized 0..1 space, clamped. */
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

    /** Map an image-normalized point back to container space for the overlay. */
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

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                Text(
                    if (mode == SelectionMode.RECT) "Select area to edit" else "Paint area to keep",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )

                // Rectangle / Brush tool switcher
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    SegmentedButton(
                        selected = mode == SelectionMode.RECT,
                        onClick = { mode = SelectionMode.RECT; dragStart = null; dragEnd = null },
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

                // Image area with gesture handling for the active tool
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 250.dp, max = 450.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (mode == SelectionMode.RECT) {
                                    dragStart = down.position
                                    dragEnd = down.position
                                    drag(down.id) { change ->
                                        change.consume()
                                        dragEnd = change.position
                                    }
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
                                    }
                                    addPoint(down.position)
                                    drag(down.id) { change ->
                                        change.consume()
                                        addPoint(change.position)
                                    }
                                    if (pts.isNotEmpty()) {
                                        strokes = strokes + BrushStroke(
                                            points = pts,
                                            radius = brushSize,
                                            eraser = eraser
                                        )
                                    }
                                }
                            }
                        }
                        .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
                ) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = "Source image for area selection",
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
                            drawRect(dim, topLeft = Offset.Zero, size = Size(w, top))           // top
                            drawRect(dim, topLeft = Offset(0f, bottom), size = Size(w, h - bottom)) // bottom
                            drawRect(dim, topLeft = Offset(0f, top), size = Size(left, bottom - top)) // left
                            drawRect(dim, topLeft = Offset(right, top), size = Size(w - right, bottom - top)) // right

                            // Selection border
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                style = Stroke(width = 2.dp.toPx())
                            )
                            // Crosshair indicator in center
                            val cx = (left + right) / 2f
                            val cy = (top + bottom) / 2f
                            val cs = 6.dp.toPx()
                            drawLine(Color.White, Offset(cx - cs, cy), Offset(cx + cs, cy), strokeWidth = 1f)
                            drawLine(Color.White, Offset(cx, cy - cs), Offset(cx, cy + cs), strokeWidth = 1f)
                        }
                    } else {
                        // Painted strokes overlay — pre-rendered to an offscreen
                        // Bitmap with native Canvas APIs so overlapping strokes
                        // don't compound ("layering" effect) and eraser strokes
                        // punch real holes (PorterDuff CLEAR). The Bitmap is
                        // composited into the Compose Canvas at uniform alpha.
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width.toInt().coerceAtLeast(1)
                            val h = size.height.toInt().coerceAtLeast(1)
                            val reference = minOf(size.width, size.height)

                            val bitmap = remember(w, h) {
                                android.graphics.Bitmap.createBitmap(
                                    w, h, android.graphics.Bitmap.Config.ARGB_8888
                                )
                            }
                            val nativeCanvas = remember(bitmap) {
                                android.graphics.Canvas(bitmap)
                            }
                            bitmap.eraseColor(0) // transparent

                            // Paint strokes (non-eraser) — full-opacity white
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                isAntiAlias = true
                                style = android.graphics.Paint.Style.STROKE
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                            }
                            for (stroke in strokes) {
                                if (stroke.eraser) continue
                                val strokePx = stroke.radius * reference
                                if (stroke.points.isEmpty()) continue
                                paint.strokeWidth = strokePx
                                if (stroke.points.size == 1) {
                                    val c = containerPoint(stroke.points[0])
                                    nativeCanvas.drawCircle(c.x, c.y, strokePx / 2f, paint)
                                } else {
                                    val p = android.graphics.Path()
                                    stroke.points.forEachIndexed { i, pt ->
                                        val o = containerPoint(pt)
                                        if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
                                    }
                                    nativeCanvas.drawPath(p, paint)
                                }
                            }

                            // Eraser strokes — punch transparent holes
                            val erase = android.graphics.Paint().apply {
                                isAntiAlias = true
                                style = android.graphics.Paint.Style.STROKE
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                                xfermode = android.graphics.PorterDuffXfermode(
                                    android.graphics.PorterDuff.Mode.CLEAR
                                )
                            }
                            for (stroke in strokes) {
                                if (!stroke.eraser) continue
                                val strokePx = stroke.radius * reference
                                if (stroke.points.isEmpty()) continue
                                erase.strokeWidth = strokePx
                                if (stroke.points.size == 1) {
                                    val c = containerPoint(stroke.points[0])
                                    nativeCanvas.drawCircle(c.x, c.y, strokePx / 2f, erase)
                                } else {
                                    val p = android.graphics.Path()
                                    stroke.points.forEachIndexed { i, pt ->
                                        val o = containerPoint(pt)
                                        if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
                                    }
                                    nativeCanvas.drawPath(p, erase)
                                }
                            }

                            // Composite at uniform alpha — all painted areas
                            // share the same transparency.
                            drawImage(
                                image = bitmap.asImageBitmap(),
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
                                    if (mode == SelectionMode.RECT) "Drag to select area" else "Paint to select area",
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                // Brush tool controls (size / eraser / clear)
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
                            onClick = { strokes = emptyList() },
                            enabled = strokes.isNotEmpty()
                        ) {
                            Text("Clear")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
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
                                    onConfirm(EditSelection(mask = BrushMask(strokes)))
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
 * Convert drag coordinates (in container space) to a normalized [CropRect]
 * by mapping onto the actual displayed image bounds, accounting for
 * [ContentScale.Fit] letterboxing.
 */
private fun dragToCropRect(
    dragStart: Offset,
    dragEnd: Offset,
    containerSize: Size,
    imageDims: Pair<Int, Int>?
): CropRect {
    val dims = imageDims
    if (dims != null && containerSize.width > 0 && containerSize.height > 0) {
        val imgW = dims.first.toFloat()
        val imgH = dims.second.toFloat()

        // ContentScale.Fit math
        val scale = minOf(containerSize.width / imgW, containerSize.height / imgH)
        val displayW = imgW * scale
        val displayH = imgH * scale
        val offsetX = (containerSize.width - displayW) / 2f
        val offsetY = (containerSize.height - displayH) / 2f

        // Clamp drag positions to the displayed image bounds
        fun clampToImage(pos: Offset): Offset {
            return Offset(
                (pos.x - offsetX).coerceIn(0f, displayW) / displayW,
                (pos.y - offsetY).coerceIn(0f, displayH) / displayH
            )
        }
        val startN = clampToImage(dragStart)
        val endN = clampToImage(dragEnd)
        return CropRect(
            left = minOf(startN.x, endN.x),
            top = minOf(startN.y, endN.y),
            right = maxOf(startN.x, endN.x),
            bottom = maxOf(startN.y, endN.y)
        )
    }
    // Fallback: normalize by container size (no image dims available)
    return CropRect(
        left = (minOf(dragStart.x, dragEnd.x) / containerSize.width).coerceIn(0f, 1f),
        top = (minOf(dragStart.y, dragEnd.y) / containerSize.height).coerceIn(0f, 1f),
        right = (maxOf(dragStart.x, dragEnd.x) / containerSize.width).coerceIn(0f, 1f),
        bottom = (maxOf(dragStart.y, dragEnd.y) / containerSize.height).coerceIn(0f, 1f)
    )
}

/**
 * Load just the pixel dimensions of an image without decoding the full bitmap.
 * Also accounts for EXIF orientation so the returned dimensions match how
 * [coil3.compose.AsyncImage] displays the image (which applies EXIF rotation
 * automatically).
 * Supports content:// URIs and file paths.
 */
private fun loadImageDimensions(context: Context, uriStr: String): Pair<Int, Int>? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
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