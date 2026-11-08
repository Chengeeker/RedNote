package com.red.note

import android.app.Application
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.intercept.Interceptor
import coil.network.HttpException
import coil.request.ErrorResult
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

class RedApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader() =
        ImageLoader.Builder(this)
            .respectCacheHeaders(false)
            .components {
                add(Interceptor { chain ->
                    var request = chain.request
                    var result = chain.proceed(request)
                    val url = request.data as? String
                    val error = (result as? ErrorResult)?.throwable
                    if (url != null && error is HttpException && error.response.code in listOf(403, 404)) {
                        val original = originalImageUrl(JSONObject().put("url", url))
                        if (original.isNotEmpty() && original != url) {
                            // Signed cover URLs can expire while the saved feed is still current.
                            request = request.newBuilder().data(original).diskCacheKey(url).build()
                            result = chain.proceed(request)
                        }
                    }
                    repeat(2) { attempt ->
                        val failure = (result as? ErrorResult)?.throwable
                        val retry = when (failure) {
                            is HttpException -> failure.response.code == 408 ||
                                failure.response.code == 429 || failure.response.code in 500..599
                            is IOException -> true
                            else -> false
                        }
                        if (!retry) return@Interceptor result
                        delay(350L * (attempt + 1))
                        currentCoroutineContext().ensureActive()
                        result = chain.proceed(request)
                    }
                    result
                })
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .build()
}

internal fun mediaHeaders(
    context: Context,
    url: String,
    preserveHdr: Boolean = false,
): Map<String, String> {
    val (region, agent) = ProtocolRuntime.get(context).mediaIdentity()
    return buildMap {
        put(
            "Referer",
            if (region == "rednote") "https://www.rednote.com/" else "https://www.xiaohongshu.com/",
        )
        put(
            "Accept",
            if (preserveHdr) "image/jpeg,image/*;q=0.8,*/*;q=0.5"
            else "image/avif,image/webp,image/*,*/*;q=0.8",
        )
        if (MediaRemuxer.trustedUrl(url) && agent.isNotBlank()) put("User-Agent", agent)
    }
}
