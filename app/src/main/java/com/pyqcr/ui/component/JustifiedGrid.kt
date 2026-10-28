package com.pyqcr.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import com.pyqcr.data.model.ImageItem
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon

/**
 * Simple justified grid: 3 images per row, fixed height, dynamic width
 * based on each image's aspect ratio.
 * Each row width sums to exactly screen width (minus spacing).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JustifiedGrid(
    images: List<ImageItem>,
    rowHeight: Dp = 120.dp,
    spacing: Dp = 2.dp,
    isMultiSelectMode: Boolean = false,
    selectedImageUris: Set<String> = emptySet(),
    aiSelectedUris: Set<String> = emptySet(),
    favouriteUris: Set<String> = emptySet(),
    taggedUris: Set<String> = emptySet(),
    onImageClick: ((String) -> Unit)? = null,
    onLongPress: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState()
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val screenWidthDp = config.screenWidthDp

    // Group images into chunks of 3
    val rows = remember(images) {
        images.chunked(3)
    }

    val totalSpacingPerRow = spacing.value * 2 // 2 gaps between 3 items
    val availableWidth = screenWidthDp.toFloat() - totalSpacingPerRow

    LazyColumn(
        state = state,
        modifier = modifier.background(Color.White),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        items(rows) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                row.forEach { image ->
                    val ratio = if (image.width > 0 && image.height > 0)
                        image.width.toFloat() / image.height.toFloat()
                    else 1f

                    // Sum of ratios in this row
                    val sumRatios = row.sumOf {
                        if (it.width > 0 && it.height > 0)
                            it.width.toDouble() / it.height.toDouble()
                        else 1.0
                    }.toFloat()

                    val itemWidthDp = (ratio / sumRatios) * availableWidth

                    val isSelected = image.uri in selectedImageUris
                    Box(
                        modifier = Modifier
                            .width(itemWidthDp.dp)
                            .fillMaxHeight()
                            .background(Color.White)
                            .then(
                                Modifier.combinedClickable(
                                    onClick = { onImageClick?.invoke(image.uri) },
                                    onLongClick = { onLongPress?.invoke(image.uri) }
                                )
                            )
                    ) {
                        if (image.uri.contains("/video/")) {
                            // Videos preview a REAL frame now: the app
                            // ImageLoader registers coil-video's
                            // VideoFrameDecoder, so the URI decodes like an
                            // image. The old dark play tile stays as the
                            // loading/error placeholder.
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(image.uri)
                                    .size(coil3.size.Size(400, (rowHeight.value * 3).toInt()))
                                    .crossfade(true)
                                    .scale(Scale.FILL)
                                    .build(),
                                contentDescription = image.displayName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                loading = { VideoTilePlaceholder() },
                                error = { VideoTilePlaceholder() }
                            )
                        } else {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(image.uri)
                                    .size(coil3.size.Size(400, (rowHeight.value * 3).toInt()))
                                    .crossfade(true)
                                    .scale(Scale.FILL)
                                    .build(),
                                contentDescription = image.displayName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        // Rating badge
                        if (image.rating > 0f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .background(
                                        Color(0xCC000000),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = String.format("%.1f", image.rating),
                                    color = Color(0xFFFFD700),
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        // AI score badge
                        if (image.aiScore != null && image.aiScore > 0f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(
                                        Color(0xCC000000),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = String.format("%.0f", image.aiScore),
                                    color = Color(0xFF00BCD4),
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        // Tag badge
                        if (image.uri in taggedUris) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(
                                        Color(0xCC000000),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Label,
                                    contentDescription = "Tagged",
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        // AI selection indicator
                        if (image.uri in aiSelectedUris) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "AI Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        // Favourite indicator (heart — MediaStore IS_FAVORITE)
                        if (image.uri in favouriteUris) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Color(0xCC000000),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Favourite",
                                    tint = Color(0xFFFF4081),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        if (isMultiSelectMode && isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x80000000))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(androidx.compose.material3.MaterialTheme.colorScheme.primary, shape = CircleShape)
                                    .padding(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else if (isMultiSelectMode) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RadioButtonUnchecked,
                                    contentDescription = "Not selected",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                // Fill remaining space if row has < 3 images
                val remaining = 3 - row.size
                if (remaining > 0) {
                    val emptyWidth = (availableWidth / 3f) * remaining + spacing.value * (remaining - 1)
                    Spacer(Modifier.width(emptyWidth.dp))
                }
            }
        }
    }
}

/** Dark play tile for video cells that are still decoding or unreadable. */
@Composable
private fun VideoTilePlaceholder() {
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
            modifier = Modifier.size(36.dp)
        )
    }
}