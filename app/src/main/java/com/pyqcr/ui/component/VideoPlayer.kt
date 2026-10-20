package com.pyqcr.ui.component

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Plays a plain video file (MP4/WebM/MOV…) with [MediaPlayer] on a
 * [TextureView] — the same approach as [MotionPhotoPlayer] (TextureView
 * renders inside Compose dialogs/overlays where a SurfaceView would punch
 * a black rectangle through). Accepts a file path OR a content:// URI.
 * Audio plays at full volume; a transient first-prepare failure is retried
 * up to three times automatically.
 *
 * Playback controls (play/pause, a draggable seek bar, and ±10-second
 * jump buttons) overlay the bottom of the video while it is ready.
 */
@Composable
fun VideoPlayer(
    data: String,
    onClose: () -> Unit = {},
    showCloseButton: Boolean = true
) {
    var ready by remember(data) { mutableStateOf(false) }
    var failed by remember(data) { mutableStateOf(false) }
    var attempt by remember(data) { mutableIntStateOf(1) }
    // Native video frame size (null until the first onPrepared) — the view is
    // sized to this ratio so the video is never stretched across the screen.
    var videoSize by remember(data) { mutableStateOf<Pair<Int, Int>?>(null) }

    // Live playback state, driven by the MediaPlayer instance held in
    // [playerRef] (set when the view's player is built, cleared on release).
    var playerRef by remember(data) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(data) { mutableStateOf(false) }
    var positionMs by remember(data) { mutableFloatStateOf(0f) }
    var durationMs by remember(data) { mutableFloatStateOf(0f) }
    var isSeeking by remember(data) { mutableStateOf(false) }

    // Automatic retries: keying the AndroidView by [attempt] forces a fresh
    // TextureView + MediaPlayer for each retry.
    LaunchedEffect(failed, attempt, data) {
        if (failed && attempt < 3) {
            attempt += 1
            ready = false
            failed = false
            positionMs = 0f
            durationMs = 0f
        }
    }

    // Follow playback progress while playing (skipped while the seek bar is
    // being dragged — the drag keeps its own position).
    LaunchedEffect(ready, isPlaying) {
        while (ready && isPlaying) {
            val p = playerRef
            if (p != null && !isSeeking) {
                try {
                    positionMs = p.currentPosition.toFloat()
                } catch (e: Exception) {
                }
            }
            delay(250)
        }
    }

    val isContentUri = data.startsWith("content://")

    // Fit the video frame: size the TextureView to the video's native aspect
    // ratio (ContentScale.Fit-style framing) instead of stretching it across
    // the whole screen, which distorts the video.
    val videoModifier = run {
        val size = videoSize
        if (size == null) {
            Modifier.fillMaxSize()
        } else {
            Modifier.aspectRatio(size.first.toFloat() / size.second.toFloat())
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        key(attempt) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surface: SurfaceTexture,
                                width: Int,
                                height: Int
                            ) {
                                try {
                                    val mp = MediaPlayer().apply {
                                        if (isContentUri) {
                                            setDataSource(ctx, Uri.parse(data))
                                        } else {
                                            setDataSource(data)
                                        }
                                        setSurface(Surface(surface))
                                        isLooping = true
                                        setVolume(1f, 1f)
                                        setOnPreparedListener { p ->
                                            if (tag == p) {
                                                val w = p.videoWidth
                                                val h = p.videoHeight
                                                if (w > 0 && h > 0) {
                                                    videoSize = w to h
                                                }
                                                ready = true
                                                durationMs = p.duration.toFloat()
                                                isPlaying = true
                                                p.start()
                                            }
                                        }
                                        setOnErrorListener { _, _, _ ->
                                            failed = true
                                            isPlaying = false
                                            true
                                        }
                                        setOnCompletionListener { }
                                        prepareAsync()
                                    }
                                    tag = mp
                                    playerRef = mp
                                } catch (e: Exception) {
                                    failed = true
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surface: SurfaceTexture,
                                width: Int,
                                height: Int
                            ) {
                            }

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                val mp = tag as? MediaPlayer
                                try {
                                    mp?.apply {
                                        setOnPreparedListener(null)
                                        setOnErrorListener(null)
                                        stop()
                                        release()
                                    }
                                } catch (e: Exception) {
                                }
                                tag = null
                                playerRef = null
                                isPlaying = false
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                            }
                        }
                    }
                },
                modifier = videoModifier,
                onRelease = {
                    val mp = it.tag as? MediaPlayer
                    try {
                        mp?.apply {
                            setOnPreparedListener(null)
                            setOnErrorListener(null)
                            stop()
                            release()
                        }
                    } catch (e: Exception) {
                    }
                    it.tag = null
                    playerRef = null
                    isPlaying = false
                }
            )
        }

        // Loading spinner while the video prepares
        if (!ready && !failed) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // Playback failed after all automatic retries
        if (failed && attempt >= 2) {
            Text(
                text = "This video could not be played",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000))
                    .padding(12.dp)
            )
        }

        // Playback controls overlay — shown once the video is ready
        if (ready && !failed) {
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
                        (durationMs.toInt() / 1000) / 60,
                        (durationMs.toInt() / 1000) % 60
                    ),
                    color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = positionMs,
                    onValueChange = { newValue ->
                        isSeeking = true
                        positionMs = newValue
                    },
                    onValueChangeFinished = {
                        playerRef?.seekTo(positionMs.toInt())
                        isSeeking = false
                    },
                    valueRange = 0f..durationMs.coerceAtLeast(1f),
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
                        playerRef?.seekTo(target.toInt())
                        positionMs = target
                    }) {
                        Icon(
                            Icons.Filled.Replay10,
                            contentDescription = "Back 10 seconds",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = {
                        val p = playerRef
                        if (p != null) {
                            if (isPlaying) {
                                p.pause()
                                isPlaying = false
                            } else {
                                p.start()
                                isPlaying = true
                            }
                        }
                    }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = {
                        val target = (positionMs + 10_000f).coerceAtMost(durationMs.coerceAtLeast(1f))
                        playerRef?.seekTo(target.toInt())
                        positionMs = target
                    }) {
                        Icon(
                            Icons.Filled.Forward10,
                            contentDescription = "Forward 10 seconds",
                            tint = Color.White
                        )
                    }
                }
                if (showCloseButton) {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("✕ Close video", color = Color.White)
                    }
                }
            }
        } else if (showCloseButton && !ready) {
            // Fallback close button while still loading / after failure
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Text("✕ Close video", color = Color.White)
            }
        }
    }
}