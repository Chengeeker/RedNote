@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package com.red.note

import android.content.Context
import coil.ImageLoader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object ImageCacheMaintenance {
    const val thresholdPreference = "auto_clear_image_cache_threshold_mb"
    const val defaultThresholdMb = 200
    val thresholdOptions = listOf(0, 50, 100, 200, 500, 1024, 2048)

    private const val bytesPerMb = 1024L * 1024L
    private const val recentImageMs = 60_000L
    private val maintenance = Mutex()
    private val cachedImageName = Regex("[a-f0-9]{64}")

    fun threshold(preferences: Preferences): Int =
        preferences.int(thresholdPreference, defaultThresholdMb).takeIf { it in thresholdOptions }
            ?: defaultThresholdMb

    fun labelForThreshold(value: Int): String =
        when {
            value == 0 -> "不限制（关闭自动清理）"
            value >= 1024 -> {
                val gb = value / 1024.0
                "${gb.toString().removeSuffix(".0")} GB"
            }
            else -> "$value MB"
        }

    fun shouldClear(bytes: Long, thresholdMb: Int): Boolean =
        thresholdMb > 0 && bytes >= thresholdMb * bytesPerMb

    suspend fun diskUsage(context: Context, loader: ImageLoader): Long =
        maintenance.withLock {
            withContext(Dispatchers.IO) {
                loader.diskCache?.size.orZero() + cachedImageBytes(imageDirectory(context))
            }
        }

    suspend fun clear(context: Context, loader: ImageLoader): Long =
        maintenance.withLock {
            withContext(Dispatchers.IO) {
                val coilBytes = loader.diskCache?.size.orZero()
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
                coilBytes +
                    deleteOldCompletedImages(imageDirectory(context), System.currentTimeMillis())
            }
        }

    suspend fun clearIfAboveThreshold(
        context: Context,
        loader: ImageLoader,
        thresholdMb: Int,
    ): Boolean =
        if (thresholdMb <= 0) false
        else
            maintenance.withLock {
                withContext(Dispatchers.IO) {
                    val directory = imageDirectory(context)
                    val bytes = loader.diskCache?.size.orZero() + cachedImageBytes(directory)
                    if (!shouldClear(bytes, thresholdMb)) false
                    else {
                        val coilCache = loader.diskCache?.size.orZero()
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        deleteOldCompletedImages(directory, System.currentTimeMillis())
                        coilCache > 0 || bytes > 0
                    }
                }
            }

    suspend fun trimOnStartup(context: Context, loader: ImageLoader, thresholdMb: Int) {
        try {
            clearIfAboveThreshold(context, loader, thresholdMb)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    internal fun cachedImageBytes(directory: File): Long =
        directory.listFiles().orEmpty().sumOf { file ->
            if (file.isFile && cachedImageName.matches(file.name)) file.length() else 0L
        }

    internal fun deleteOldCompletedImages(directory: File, now: Long): Long =
        directory.listFiles().orEmpty().sumOf { file ->
            if (
                file.isFile &&
                    cachedImageName.matches(file.name) &&
                    now - file.lastModified() >= recentImageMs
            ) {
                val size = file.length()
                if (file.delete()) size else 0L
            } else 0L
        }

    private fun imageDirectory(context: Context) = File(context.cacheDir, "image_cache")

    private fun Long?.orZero() = this ?: 0L
}
