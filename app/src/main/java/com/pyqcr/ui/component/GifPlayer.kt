package com.pyqcr.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pyqcr.ui.util.FileOperationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Plays an animated GIF with full user control: play/pause, a draggable
 * progress bar, ±10-second jump buttons, and a screenshot button that saves
 * the current frame as a PNG into Pictures/PYQAlbum/Screenshots. Unlike Coil's
 * inline GIF playback (no pause/seek API), this component decodes the GIF into
 * an android.graphics.Movie — deprecated, but still the only SDK-internal way
 * to step an animated GIF to an arbitrary frame — and repaints the current
 * frame into a [GifFrameView] on a ~25 Hz tick while playing.
 */
@Composable
fun GifPlayer(
    data: String,
    onClose: () -> Unit = {},
    showCloseButton: Boolean = true
) {
    val context = LocalContext.current
    var movie by remember(data) { mutableStateOf<Movie?>(null) }
    var decodeFailed by remember(data) { mutableStateOf(false) }
    var frameView by remember(data) { mutableStateOf<GifFrameView?>(null) }
    var positionMs by remember(data) { mutableFloatStateOf(0f) }
    var isPlaying by remember(data) { mutableStateOf(false) }
    var isSeeking by remember(data) { mutableStateOf(false) }

    val durationMs = remember(movie) {
        val d = movie?.duration()
        if (d != null && d > 0) d else 0
    }

    // Decode the GIF bytes off the main thread. Movie.decodeByteArray returns
    // null for anything that is not a GIF (or when the stream cannot be read).
    LaunchedEffect(data) {
        decodeFailed = false
        movie = withContext(Dispatchers.IO) {
            try {
                var bytes: ByteArray? = null
                try {
                    bytes = context.contentResolver.openInputStream(Uri.parse(data))
                        ?.use { it.readBytes() }
                } catch (e: Exception) {
                    bytes = null
                }
                if (bytes == null) null
                else if (bytes.isEmpty()) null
                else {
                    try {
                        Movie.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        if (movie == null) decodeFailed = true
    }

    // Playback tick: advance the frame while playing. The frame view is
    // updated directly so animation stays smooth; positionMs also drives the
    // slider/time label and triggers the AndroidView update (re-invalidate).
    // Keyed on (movie, isPlaying) so the loop stops repainting while paused
    // and resumes from the current position when play is toggled.
    LaunchedEffect(movie, isPlaying) {
        while (isPlaying && movie != null && durationMs > 0) {
            delay(40)
            if (!isSeeking) {
                positionMs = (positionMs + 40f) % durationMs.toFloat()
            }
            frameView?.frameTime = positionMs.toInt()
            frameView?.invalidate()
        }
    }

    /** Save EXACTLY what is on screen as a PNG screenshot: the live frame
     * view is redrawn at the current frameTime into a view-sized bitmap, so
     * the capture can never differ from the moment the user is looking at. */
    val captureScreenshot: () -> Unit = {
        val v = frameView
        if (v == null || movie == null || v.width <= 0 || v.height <= 0) {
            Toast.makeText(context, "GIF is not loaded yet", Toast.LENGTH_SHORT).show()
        } else {
            try {
                v.frameTime = positionMs.toInt()
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                val saved = FileOperationHelper.savePngScreenshot(context, bmp, "PYQAlbum_GIF")
                Toast.makeText(
                    context,
                    if (saved != null) "Screenshot saved" else "Could not save screenshot",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Could not save screenshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Fit the GIF frame (ContentScale.Fit-style framing) using its native
    // pixel ratio, like the video players do for the TextureView.
    val gifModifier = run {
        val m = movie
        if (m != null && m.width() > 0 && m.height() > 0) {
            Modifier.aspectRatio(m.width().toFloat() / m.height().toFloat())
        } else {
            Modifier.fillMaxSize()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val m = movie
        if (m != null && !decodeFailed) {
            AndroidView(
                factory = { ctx ->
                    GifFrameView(ctx).apply {
                        this.movie = m
                        frameTime = positionMs.toInt()
                    }
                },
                update = { view ->
                    frameView = view
                    view.movie = m
                    view.frameTime = positionMs.toInt()
                    view.invalidate()
                },
                modifier = gifModifier
            )
        }

        // Loading spinner while decoding
        if (m == null && !decodeFailed) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // Not a GIF / unreadable stream
        if (decodeFailed) {
            Text(
                text = "This GIF could not be played",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000))
                    .padding(12.dp)
            )
        }

        // Playback controls overlay — shown once the GIF is decoded
        if (m != null && !decodeFailed) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%d:%02d / %d:%02d",
                        (positionMs.toInt() / 1000) / 60,
                        (positionMs.toInt() / 1000) % 60,
                        (durationMs / 1000) / 60,
                        (durationMs / 1000) % 60
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = positionMs,
                    onValueChange = { newValue ->
                        isSeeking = true
                        positionMs = newValue
                    },
                    onValueChangeFinished = {
                        isSeeking = false
                        frameView?.frameTime = positionMs.toInt()
                        frameView?.invalidate()
                    },
                    valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.4f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        val target = (positionMs - 10_000f).coerceAtLeast(0f)
                        positionMs = target
                        frameView?.frameTime = target.toInt()
                        frameView?.invalidate()
                    }) {
                        Icon(
                            Icons.Filled.Replay10,
                            contentDescription = "Back 10 seconds",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = {
                        isPlaying = !isPlaying
                    }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = {
                        val target = (positionMs + 10_000f)
                            .coerceAtMost(durationMs.toFloat().coerceAtLeast(1f))
                        positionMs = target
                        frameView?.frameTime = target.toInt()
                        frameView?.invalidate()
                    }) {
                        Icon(
                            Icons.Filled.Forward10,
                            contentDescription = "Forward 10 seconds",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = captureScreenshot) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = "Save screenshot",
                            tint = Color.White
                        )
                    }
                }
                if (showCloseButton) {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("✕ Close GIF", color = Color.White)
                    }
                }
            }
        } else if (showCloseButton && m == null) {
            // Fallback close button while still loading / after failure
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Text("✕ Close GIF", color = Color.White)
            }
        }
    }
}

/** android.view.View that repaints the current GIF frame from a [Movie]. */
private class GifFrameView(context: Context) : View(context) {
    var movie: Movie? = null
    var frameTime: Int = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = movie ?: return
        if (m.width() <= 0 || m.height() <= 0) return
        val scale = minOf(
            width.toFloat() / m.width(),
            height.toFloat() / m.height()
        )
        canvas.save()
        canvas.scale(scale, scale)
        m.setTime(frameTime)
        m.draw(canvas, 0f, 0f)
        canvas.restore()
    }
}