package com.pyqcr.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A rating bar that supports 0.5-star increments.
 * Uses Material Icons for star display.
 */
@Composable
fun RatingBar(
    rating: Float,
    onRatingChange: ((Float) -> Unit)? = null,
    starSize: Dp = 32.dp,
    starColor: Color = Color(0xFFFFB300),
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 1..5) {
            val starValue = i.toFloat()
            val icon = when {
                rating >= starValue -> Icons.Filled.Star
                rating >= starValue - 0.5f -> Icons.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }

            IconButton(
                onClick = {
                    onRatingChange?.let { change ->
                        val newRating = if (rating == starValue) {
                            starValue - 0.5f  // Toggle half
                        } else {
                            starValue
                        }
                        change(newRating.coerceIn(0f, 5f))
                    }
                },
                modifier = Modifier.size(starSize)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "Star $i",
                    tint = starColor,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = String.format("%.1f", rating),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}