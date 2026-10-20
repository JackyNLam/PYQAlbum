package com.pyqcr.ui.component

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Plays a plain video file (MP4/WebM/MOV…) with [MediaPlayer] on a
 * [TextureView] — the same approach as [MotionPhotoPlayer] (TextureView
 * renders inside Compose dialogs/overlays where a SurfaceView would punch
 * a black rectangle through). Accepts a file path OR a content:// URI.
 * Audio plays at full volume; a transient first-prepare failure is retried
 * up to three times automatically.
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

    // Automatic retries: keying the AndroidView by [attempt] forces a fresh
    // TextureView + MediaPlayer for each retry.
    LaunchedEffect(failed, attempt, data) {
        if (failed && attempt < 3) {
            attempt += 1
            ready = false
            failed = false
        }
    }

    val isContentUri = data.startsWith("content://")

    Box(modifier = Modifier.fillMaxSize()) {
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
                                                ready = true
                                                p.start()
                                            }
                                        }
                                        setOnErrorListener { _, _, _ ->
                                            failed = true
                                            true
                                        }
                                        setOnCompletionListener { }
                                        prepareAsync()
                                    }
                                    tag = mp
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
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
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

        // Optional close button over the video
        if (showCloseButton) {
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