package com.pyqcr.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import com.pyqcr.data.model.ImageItem

data class JustifiedItem(
    val index: Int,
    val weight: Float
)

/**
 * Justified/Uniform grid — all rows have the same fixed height,
 * each image width is proportional to its aspect ratio, filling the row width exactly.
 *
 * Supports click and long-press callbacks.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JustifiedGrid(
    images: List<ImageItem>,
    rowHeight: Dp = 120.dp,
    spacing: Dp = 2.dp,
    isMultiSelectMode: Boolean = false,
    selectedImageUris: Set<String> = emptySet(),
    onImageClick: ((String) -> Unit)? = null,
    onLongPress: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current

    val aspectRatios = remember(images) {
        images.map { item ->
            if (item.width > 0 && item.height > 0)
                item.width.toFloat() / item.height.toFloat()
            else 1f
        }
    }

    val rows = remember(images, aspectRatios, config, rowHeight, spacing) {
        val screenWidthDp = config.screenWidthDp.toFloat()
        layoutIntoRows(aspectRatios, screenWidthDp, spacing.value)
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
                    val isSelected = image.uri in selectedImageUris
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(item.weight)
                            .background(Color.Black)
                            .then(
                                Modifier.combinedClickable(
                                    onClick = { onImageClick?.invoke(image.uri) },
                                    onLongClick = { onLongPress?.invoke(image.uri) }
                                )
                            )
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
                        if (isMultiSelectMode && isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x80000000))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(Color.Green)
                                    .padding(3.dp)
                            ) {
                                Text(
                                    text = "✓",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun layoutIntoRows(
    ratios: List<Float>,
    screenWidthDp: Float,
    spacingDp: Float
): List<List<JustifiedItem>> {
    val rows = mutableListOf<MutableList<JustifiedItem>>()
    var currentRow = mutableListOf<Pair<Int, Float>>()
    var currentSum = 0f

    for ((index, ratio) in ratios.withIndex()) {
        val itemWidth = ratio

        if (currentSum + itemWidth > screenWidthDp && currentRow.isNotEmpty()) {
            rows.add(normaliseRow(currentRow, screenWidthDp))
            currentRow = mutableListOf()
            currentSum = 0f
        }
        currentRow.add(index to itemWidth)
        currentSum += itemWidth
    }

    if (currentRow.isNotEmpty()) {
        rows.add(normaliseRow(currentRow, screenWidthDp))
    }

    return rows
}

private fun normaliseRow(
    row: List<Pair<Int, Float>>,
    screenWidthDp: Float
): MutableList<JustifiedItem> {
    val rowSum = row.sumOf { it.second.toDouble() }.toFloat()
    return row.map { (idx, ratio) ->
        JustifiedItem(index = idx, weight = ratio / rowSum)
    }.toMutableList()
}