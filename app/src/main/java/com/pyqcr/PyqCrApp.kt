package com.pyqcr

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
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

    lateinit var imageLoader: ImageLoader
        private set

    override fun onCreate() {
        super.onCreate()
        imageLoader = newImageLoader(this)
        SingletonImageLoader.setSafe(imageLoader)
    }

    private fun newImageLoader(context: Context): ImageLoader {
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
            .logger(DebugLogger())
            .build()
    }
}