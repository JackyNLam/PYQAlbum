package com.pyqcr.ui.component

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
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
 * Plays a motion-photo video extracted by [com.pyqcr.util.MotionPhotoExtractor]
 * using [MediaPlayer] on a [TextureView] (AndroidView interop). The player is
 * released when the composable leaves composition. Shared by the full-screen
 * image detail view and the Photo Vault viewer.
 *
 * TextureView (not SurfaceView) is used because SurfaceView punches through to
 * a separate window layer, which renders as a BLACK rectangle inside Compose
 * dialogs/overlays on several devices — the classic "motion photo shows a
 * black screen" bug. TextureView renders into the composable layer itself.
 *
 * A transient first-prepare failure is retried once automatically: the
 * AndroidView is recreated (key = attempt), building a fresh player; only the
 * second failure surfaces the error message.
 */
@Composable
fun MotionPhotoPlayer(
    videoPath: String,
    onClose: () -> Unit
) {
    var ready by remember(videoPath) { mutableStateOf(false) }
    var failed by remember(videoPath) { mutableStateOf(false) }
    var attempt by remember(videoPath) { mutableIntStateOf(1) }

    // One automatic retry: the first prepare can fail with a transient decoder
    // error on some devices; keying the AndroidView by [attempt] forces a fresh
    // TextureView + MediaPlayer for the second try.
    LaunchedEffect(failed, attempt, videoPath) {
        if (failed && attempt < 2) {
            attempt += 1
            ready = false
            failed = false
        }
    }

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
                                        setDataSource(videoPath)
                                        setSurface(Surface(surface))
                                        isLooping = true
                                        setVolume(0f, 0f) // motion clips are silent
                                        setOnPreparedListener { p ->
                                            // Only start when THIS view's player is
                                            // still alive (tag is cleared on release).
                                            if (tag == p) {
                                                ready = true
                                                p.start()
                                            }
                                        }
                                        setOnErrorListener { _, what, extra ->
                                            failed = true
                                            true
                                        }
                                        setOnCompletionListener { }
                                        prepareAsync()
                                    }
                                    // Keep the player alive as long as this view lives;
                                    // released when the surface goes away or the
                                    // composable leaves composition.
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

        // Loading spinner while the video prepares (surface is transparent
        // until the first frame, so the still frame shows through meanwhile)
        if (!ready && !failed) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // Playback failed twice (initial + retry) → tell the user instead of
        // showing a silent black box
        if (failed && attempt >= 2) {
            Text(
                text = "This motion photo could not be played",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000))
                    .padding(12.dp)
            )
        }

        // Close button over the video
        TextButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        ) {
            Text("✕ Close motion", color = Color.White)
        }
    }
}