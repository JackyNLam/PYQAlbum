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
import androidx.compose.runtime.getValue
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
 */
@Composable
fun MotionPhotoPlayer(
    videoPath: String,
    onClose: () -> Unit
) {
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
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
                                        if (!released) {
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
                            released = true
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
            }
        )

        // Loading spinner while the video prepares (surface is transparent
        // until the first frame, so the still frame shows through meanwhile)
        if (!ready && !failed) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // Playback failure → tell the user instead of showing a silent black box
        if (failed) {
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