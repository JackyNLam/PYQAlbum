package com.pyqcr.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import com.pyqcr.data.model.ImageItem

data class JustifiedItem(
    val index: Int,
    val weight: Float   // coefficient for Modifier.weight() in Row
)

/**
 * Justified/Uniform grid — all rows have the same height,
 * each image width is proportional to its aspect ratio, filling the row.
 * Fixed: images are now properly weighted and displayed using Box + fillMaxHeight + weight.
 */
@Composable
fun JustifiedGrid(
    images: List<ImageItem>,
    rowHeight: Dp = 120.dp,
    spacing: Dp = 4.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current

    // Pre-compute aspect ratios (height/width — since weights are proportional to width)
    val aspectRatios = remember(images) {
        images.map { item ->
            if (item.width > 0 && item.height > 0) {
                item.width.toFloat() / item.height.toFloat()  // width / height
            } else {
                1f
            }
        }
    }

    // Layout into rows
    val rows = remember(images, aspectRatios, config, rowHeight, spacing) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(item.weight)
                            .background(Color.Black)
                    ) {
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
                }
            }
        }
    }
}

/**
 * Greedy algorithm: accumulate images into a row until total aspect ratio (width/height)
 * sum exceeds screen width / row height, then start a new row.
 * The last row is stretched to fill.
 */
private fun layoutIntoRows(
    ratios: List<Float>,  // width / height
    screenWidthDp: Float,
    rowHeightDp: Float,
    spacingDp: Float
): List<List<JustifiedItem>> {
    val maxRowWidth = screenWidthDp
    val rows = mutableListOf<MutableList<JustifiedItem>>()
    var currentRow = mutableListOf<Pair<Int, Float>>()
    var currentSum = 0f

    for ((index, ratio) in ratios.withIndex()) {
        // Each image's width = ratio * rowHeight (since width = aspectRatio * height)
        val itemWidth = ratio

        if (currentSum + itemWidth > maxRowWidth && currentRow.isNotEmpty()) {
            // Finalize current row — scale weights to sum to maxRowWidth
            val rowSum = currentRow.sumOf { it.second.toDouble() }.toFloat()
            rows.add(
                currentRow.map { (idx, r) ->
                    JustifiedItem(index = idx, weight = r / rowSum * maxRowWidth)
                }.toMutableList()
            )
            currentRow = mutableListOf()
            currentSum = 0f
        }
        currentRow.add(index to itemWidth)
        currentSum += itemWidth
    }

    // Last row — scale to fill remaining width
    if (currentRow.isNotEmpty()) {
        val rowSum = currentRow.sumOf { it.second.toDouble() }.toFloat()
        rows.add(
            currentRow.map { (idx, r) ->
                JustifiedItem(index = idx, weight = r / rowSum * maxRowWidth)
            }.toMutableList()
        )
    }

    return rows
}