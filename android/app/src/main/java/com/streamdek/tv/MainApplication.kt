package com.streamdek.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.streamdek.tv.nativeapp.data.CloudStreamPlugins
import com.streamdek.tv.nativeapp.data.PlaybackCodecOptions
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Read once, into the copy the player consults when it is built.
        PlaybackCodecOptions.initialize(this)
        // The `.cs3` engine, so a collection synced from the phone or the portal has somewhere to
        // load into. Cheap: it only opens a preferences file until a source is switched on.
        CloudStreamPlugins.initialize(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    // OkHttp allows five concurrent requests per host by default, and every
                    // poster, backdrop and episode still comes from image.tmdb.org — one host. So
                    // the whole app loaded artwork five images at a time however many rows were on
                    // screen, and a row often only filled in as it was scrolled to: scrolling did
                    // not start those requests, it gave the queue time to reach them.
                    //
                    // Lower than the phone's. A stick has a slower CPU to decode on and less heap
                    // to hold the results in, and past a point more parallel downloads just means
                    // more bitmaps arriving at once than it can decode.
                    .dispatcher(
                        Dispatcher().apply {
                            maxRequests = 48
                            maxRequestsPerHost = 12
                        },
                    )
                    .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(60L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
    }
}
