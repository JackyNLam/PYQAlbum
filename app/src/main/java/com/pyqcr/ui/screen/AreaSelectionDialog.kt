package com.pyqcr.ui.screen

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.pyqcr.ai.CropRect

/**
 * Full-screen dialog that shows the source image and lets the user drag
 * to select a rectangular area. Returns the selection as a normalized [CropRect].
 *
 * Coordinates are 0f..1f relative to the displayed image container size.
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
                            val rect = CropRect(
                                left = minOf(s.x, e.x) / containerSize.width,
                                top = minOf(s.y, e.y) / containerSize.height,
                                right = maxOf(s.x, e.x) / containerSize.width,
                                bottom = maxOf(s.y, e.y) / containerSize.height
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