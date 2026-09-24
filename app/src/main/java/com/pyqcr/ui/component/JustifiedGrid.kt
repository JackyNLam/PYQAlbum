package com.pyqcr.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.pyqcr.data.model.ImageItem

data class JustifiedItem(
    val index: Int,
    val weight: Float   // coefficient for Modifier.weight() in Row
)

/**
 * Justified/Uniform grid — all rows have the same height,
 * each image width is proportional to its aspect ratio, filling the row.
 * Inspiration: Google Photos uniform grid.
 *
 * Images that don't fit exactly get scaled to fill the row (ContentScale.Crop).
 */
@Composable
fun JustifiedGrid(
    images: List<ImageItem>,
    rowHeight: Dp = 120.dp,
    spacing: Dp = 4.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current

    // Pre-compute aspect ratios
    val aspectRatios = remember(images) {
        images.map { item ->
            if (item.height > 0) item.width.toFloat() / item.height.toFloat() else 1f
        }
    }

    // Layout into rows
    val rows = remember(images, aspectRatios, density, config, rowHeight, spacing) {
        val screenWidthDp = config.screenWidthDp.toFloat()
        val spacingDpValue = spacing.value
        layoutIntoRows(aspectRatios, screenWidthDp, rowHeight.value, spacingDpValue)
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        items(rows) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                row.forEach { item ->
                    val image = images.getOrNull(item.index) ?: return@forEach
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(image.uri)
                            .size(400)
                            .crossfade(true)
                            .build(),
                        contentDescription = image.displayName,
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(item.weight),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

/**
 * Greedy algorithm: accumulate images into a row until total width exceeds screen width,
 * then start a new row. The last row is stretched to fill.
 */
private fun layoutIntoRows(
    ratios: List<Float>,
    screenWidthDp: Float,
    rowHeightDp: Float,
    spacingDp: Float
): List<List<JustifiedItem>> {
    val rows = mutableListOf<MutableList<JustifiedItem>>()
    var currentRow = mutableListOf<Pair<Int, Float>>()
    var currentSum = 0f

    for ((index, ratio) in ratios.withIndex()) {
        val itemWidth = ratio
        if (currentSum + itemWidth > screenWidthDp && currentRow.isNotEmpty()) {
            // Finalize current row
            rows.add(
                currentRow.map { (idx, r) ->
                    JustifiedItem(index = idx, weight = r)
                }.toMutableList()
            )
            currentRow = mutableListOf()
            currentSum = 0f
        }
        currentRow.add(index to ratio)
        currentSum += ratio
    }

    // Last row
    if (currentRow.isNotEmpty()) {
        rows.add(
            currentRow.map { (idx, r) ->
                JustifiedItem(index = idx, weight = r)
            }.toMutableList()
        )
    }

    return rows
}