package com.red.note

import android.app.Application
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

class RedApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader() =
        ImageLoader.Builder(this)
            .components {
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
