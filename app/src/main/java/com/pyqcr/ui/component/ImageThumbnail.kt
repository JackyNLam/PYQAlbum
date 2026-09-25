package com.pyqcr.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/**
 * Square fit-center thumbnail with white background — no cropping.
 * The image is centered within a square box, maintaining aspect ratio.
 * Empty/non-filling area in the square is filled with white.
 * Shows rating badge if rating > 0.
 */
@Composable
fun ImageThumbnail(
    imageUri: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    backgroundColor: Color = Color.White,
    rating: Float = 0f
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .aspectRatio(1f)          // Force square
            .background(backgroundColor)
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUri)
                .size(400)             // Limit decode size
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale
        )

        // Rating badge
        if (rating > 0f) {
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
                    text = String.format("%.1f", rating),
                    color = Color(0xFFFFD700),
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}