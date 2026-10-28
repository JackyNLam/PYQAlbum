package com.pyqcr

import android.app.Application
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.gif.GifDecoder
import coil3.video.VideoFrameDecoder
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.pyqcr.data.db.AppDatabase
import okio.Path.Companion.toOkioPath

class PyqCrApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize notification channels for background AI rating and AI edit
        com.pyqcr.ai.NotificationHelper.createChannel(this)
        com.pyqcr.ai.NotificationHelper.createAiEditChannel(this)
    }

    fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(100 * 1024 * 1024) // 100 MB
                    .build()
            }
            .crossfade(true)
            .components {
                // Animated GIF / animated WebP support (coil-gif)
                add(GifDecoder.Factory())
                // Video frame thumbnails: a video URI renders a real frame
                // instead of failing to decode (coil-video)
                add(VideoFrameDecoder.Factory())
            }
            .logger(DebugLogger())
            .build()
    }
}