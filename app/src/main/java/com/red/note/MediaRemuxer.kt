package com.red.note

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer

internal object MediaRemuxer {
    fun trustedUrl(url:String):Boolean = try {
        val parsed=URI(url);val host=parsed.host?.lowercase().orEmpty()
        parsed.scheme=="https" && parsed.userInfo==null &&
            (host.endsWith(".xhscdn.com") || host.endsWith(".xiaohongshu.com") || host.endsWith(".rednote.com"))
    } catch(_:Exception){false}

    // Remux compressed samples on disk; never decode an entire movie into memory.
    fun merge(cache:File, video:HttpURLConnection, audioUrl:String):File {
        require(trustedUrl(audioUrl)){"独立音轨地址不支持保存"}
        val inputs=mutableListOf<File>()
        val extractors=mutableListOf<MediaExtractor>()
        val output=File.createTempFile("rn_mux_",".mp4",cache)
        var muxer:MediaMuxer?=null
        var complete=false
        try {
            val limit=512L*1024*1024
            fun download(connection:HttpURLConnection, remaining:Long):File {
                val file=File.createTempFile("rn_track_",".tmp",cache).also { inputs.add(it) }
                require(connection.responseCode in 200..299){"音视频下载失败，请刷新笔记重试"}
                require(connection.contentLengthLong<=remaining){"媒体文件过大"}
                var size=0L
                file.outputStream().use { target -> connection.inputStream.use { source ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        val n=source.read(buffer);if(n<0)break
                        check(!Thread.currentThread().isInterrupted)
                        size+=n;require(size<=remaining){"媒体文件过大"};target.write(buffer,0,n)
                    }
                }}
                require(size>0){"音视频内容为空"}
                return file
            }
            val videoFile=download(video,limit)
            val audio=URL(audioUrl).openConnection() as HttpURLConnection
            audio.connectTimeout=15000;audio.readTimeout=30000;audio.instanceFollowRedirects=false
            audio.setRequestProperty("Referer",if(URI(audioUrl).host.endsWith(".rednote.com"))"https://www.rednote.com/" else "https://www.xiaohongshu.com/")
            val audioFile=try {download(audio,limit-videoFile.length())}finally {audio.disconnect()}
            muxer=MediaMuxer(output.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val targetMuxer=muxer
            val tracks=listOf(videoFile to "video/",audioFile to "audio/").map { (file,prefix) ->
                val extractor=MediaExtractor().also {extractors.add(it);it.setDataSource(file.path)}
                val track=(0 until extractor.trackCount).firstOrNull {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix)==true}
                    ?: throw IllegalArgumentException("音视频轨道格式不受支持")
                val format=extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                Triple(extractor,targetMuxer.addTrack(format),format)
            }
            targetMuxer.start()
            val info=MediaCodec.BufferInfo()
            for((extractor,track,format) in tracks) {
                val requested=if(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                val buffer=ByteBuffer.allocate(requested.coerceIn(1024*1024,16*1024*1024))
                while(true) {
                    check(!Thread.currentThread().isInterrupted)
                    buffer.clear()
                    val size=extractor.readSampleData(buffer,0);if(size<0)break
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0){"加密媒体不支持保存"}
                    info.set(0,size,extractor.sampleTime,if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    targetMuxer.writeSampleData(track,buffer,info)
                    require(output.length()<=limit){"媒体文件过大"}
                    if(!extractor.advance())break
                }
            }
            targetMuxer.stop();complete=true
            return output
        } finally {
            extractors.forEach {it.release()}
            try {muxer?.release()}finally {
                inputs.forEach {it.delete()}
                if(!complete)output.delete()
            }
        }
    }
}
