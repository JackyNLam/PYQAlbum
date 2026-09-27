package com.pyqcr.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.pyqcr.data.model.ImageItem
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon

/**
 * Pinterest-style waterfall/staggered grid.
 * Accepts onImageClick and onLongPress callbacks.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WaterfallGrid(
    images: List<ImageItem>,
    columns: Int = 3,
    isMultiSelectMode: Boolean = false,
    selectedImageUris: Set<String> = emptySet(),
    aiSelectedUris: Set<String> = emptySet(),
    onImageClick: ((String) -> Unit)? = null,
    onLongPress: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalItemSpacing = 4.dp,
        modifier = modifier.background(Color.White)
    ) {
        items(images, key = { it.uri }) { image ->
            val isSelected = image.uri in selectedImageUris
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .combinedClickable(
                        onClick = { onImageClick?.invoke(image.uri) },
                        onLongClick = { onLongPress?.invoke(image.uri) }
                    )
            ) {
                WaterfallTile(image = image)

                // Rating badge
                if (image.rating > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(
                                Color(0xCC000000),
                                shape = androidx.compose.material3.MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        androidx.compose.material3.Text(
                            text = String.format("%.1f", image.rating),
                            color = Color(0xFFFFD700),
                            fontSize = androidx.compose.material3.MaterialTheme.typography.labelSmall.fontSize,
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
                                shape = androidx.compose.material3.MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        androidx.compose.material3.Text(
                            text = String.format("%.0f", image.aiScore),
                            color = Color(0xFF00BCD4),
                            fontSize = androidx.compose.material3.MaterialTheme.typography.labelSmall.fontSize,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                // AI selection indicator
                if (image.uri in aiSelectedUris) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(
                                androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                shape = androidx.compose.material3.MaterialTheme.shapes.small
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
    }
}

@Composable
private fun WaterfallTile(image: ImageItem) {
    val context = LocalContext.current
    val ratio = if (image.height > 0) image.width.toFloat() / image.height.toFloat() else 1f

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(image.uri)
            .size(400)
            .crossfade(true)
            .build(),
        contentDescription = image.displayName,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio),
        contentScale = ContentScale.Fit
    )
}