package com.pyqcr.ui.component

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
 * using [MediaPlayer] on a [SurfaceView] (AndroidView interop). The player is
 * released when the composable leaves composition. Shared by the full-screen
 * image detail view and the Photo Vault viewer.
 */
@Composable
fun MotionPhotoPlayer(
    videoPath: String,
    onClose: () -> Unit
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            try {
                                val mp = MediaPlayer().apply {
                                    setDataSource(videoPath)
                                    setDisplay(holder)
                                    isLooping = true
                                    setVolume(0f, 0f) // motion clips are silent
                                    prepare()
                                    start()
                                }
                                player = mp
                            } catch (e: Exception) {
                                // Unsupported codec or corrupt clip — the still
                                // frame behind stays visible
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            try { player?.release() } catch (e: Exception) {}
                            player = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = {
                try { player?.release() } catch (e: Exception) {}
                player = null
            }
        )

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