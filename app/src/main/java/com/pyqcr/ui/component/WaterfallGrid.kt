package com.pyqcr.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.pyqcr.data.model.ImageItem

/**
 * Pinterest-style waterfall/staggered grid.
 * Each column scrolls independently; images keep their original aspect ratio.
 * Uses LazyVerticalStaggeredGrid (Compose 1.6+ / Material3 built-in).
 */
@Composable
fun WaterfallGrid(
    images: List<ImageItem>,
    columns: Int = 2,
    modifier: Modifier = Modifier
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredCells.Fixed(columns),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalItemSpacing = 4.dp,
        modifier = modifier
    ) {
        items(images, key = { it.uri }) { image ->
            WaterfallTile(image = image)
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
            .aspectRatio(ratio),  // Keep original aspect ratio
        contentScale = ContentScale.Fit
    )
}