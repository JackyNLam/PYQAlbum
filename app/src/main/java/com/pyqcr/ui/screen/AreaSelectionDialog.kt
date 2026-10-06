package com.pyqcr.ui.screen

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.pyqcr.ai.CropRect

/**
 * Full-screen dialog that shows the source image and lets the user drag
 * to select a rectangular area. Returns the selection as a normalized [CropRect].
 *
 * Coordinates are 0f..1f relative to the image itself (not the container),
 * accounting for letterboxing from ContentScale.Fit.
 */
@Composable
fun AreaSelectionDialog(
    imageUri: String,
    onConfirm: (CropRect) -> Unit,
    onDismiss: () -> Unit
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var imageDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val context = LocalContext.current

    // Load intrinsic image dimensions so we can compute the actual
    // displayed image bounds within the container (ContentScale.Fit
    // centers the image with letterboxing).
    LaunchedEffect(imageUri) {
        imageDims = loadImageDimensions(context, imageUri)
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
                    "Select area to edit",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )

                // Image area with drag gesture
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 250.dp, max = 450.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { dragStart = it; dragEnd = it },
                                onDrag = { change, _ ->
                                    change.consume()
                                    dragEnd = change.position
                                }
                            )
                        }
                        .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
                ) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = "Source image for area selection",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

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

                    // Instruction overlay when no drag started
                    if (dragStart == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = Color(0x88000000),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "Drag to select area",
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
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
                            val s = dragStart ?: return@Button
                            val e = dragEnd ?: return@Button
                            if (containerSize.width <= 0 || containerSize.height <= 0) return@Button

                            val rect = dragToCropRect(
                                s, e, containerSize, imageDims
                            )
                            if (rect.isValid()) onConfirm(rect)
                        },
                        enabled = dragStart != null && dragEnd != null
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
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            Pair(opts.outWidth, opts.outHeight)
        } else null
    } catch (e: Exception) {
        null
    }
}