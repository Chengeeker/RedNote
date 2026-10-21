package com.red.note

import android.Manifest
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

internal class MediaBridge(private val activity:MainActivity,engine:FlutterEngine) {
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var connection:HttpURLConnection?=null
    private val channel=MethodChannel(engine.dartExecutor.binaryMessenger,"com.red.note/media")
    init {
        engine.platformViewsController.registry.registerViewFactory("com.red.note/hdrImage",HdrImageViewFactory(channel))
        channel.setMethodCallHandler {call,result ->
            when(call.method) {
                "isHdrDisplaySupported" -> result.success(Build.VERSION.SDK_INT>=34 && activity.display?.isHdr==true)
                "setWindowHdr" -> {
                    if(Build.VERSION.SDK_INT>=34)activity.window.colorMode=if(call.argument<Boolean>("enabled")==true)ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
                    result.success(null)
                }
                "setImageGalleryStatusBarVisible" -> {
                    val visible=call.argument<Boolean>("visible") ?: true
                    if(Build.VERSION.SDK_INT>=30)activity.window.insetsController?.apply {
                        systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        if(visible)show(WindowInsets.Type.statusBars())else hide(WindowInsets.Type.statusBars())
                    } else activity.window.decorView.apply {
                        systemUiVisibility=if(visible)systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN.inv() else systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN
                    }
                    result.success(null)
                }
                "setBrightness" -> {activity.window.attributes=activity.window.attributes.apply {screenBrightness=(call.argument<Double>("value") ?: -1.0).toFloat().coerceIn(-1f,1f)};result.success(null)}
                "saveRemoteMedia" -> {
                    if(Build.VERSION.SDK_INT<29 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
                        activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),1002)
                        result.error("permission","请允许相册权限后再次点击保存",null)
                    } else worker.execute {
                        try {
                            val saved=save(call.argument<String>("url") ?: "",call.argument<String>("subDir") ?: "RedNote",call.argument<Boolean>("video")==true,call.argument<String>("audioUrl").orEmpty())
                            activity.runOnUiThread {if(!activity.isDestroyed)result.success(saved)}
                        } catch(e:Exception) {activity.runOnUiThread {if(!activity.isDestroyed)result.error("save",if(e is IllegalArgumentException)e.message else "保存失败，请检查网络和可用存储空间",null)}}
                    }
                }
                "cacheStats", "clearCache" -> worker.execute {
                    val dir=File(activity.cacheDir,"cacheimage")
                    val files=dir.listFiles()?.filter {it.isFile && it.name.matches(Regex("[a-fA-F0-9]{32}"))}.orEmpty() +
                        activity.cacheDir.listFiles()?.filter {it.isFile && it.name.matches(Regex("rn_(mux|track)_.+\\.(mp4|tmp)"))}.orEmpty()
                    val before=files.sumOf {it.length()}
                    if(call.method=="clearCache")files.forEach {it.delete()}
                    val remaining=files.filter {it.exists()}.sumOf {it.length()}
                    activity.runOnUiThread {if(!activity.isDestroyed)result.success(mapOf("bytes" to remaining,"cleared" to before-remaining))}
                }
                else -> result.notImplemented()
            }
        }
    }
    private fun save(url:String,subDir:String,video:Boolean,audioUrl:String):String {
        require(MediaRemuxer.trustedUrl(url)) {"此媒体地址暂不支持保存"}
        require(audioUrl.isEmpty() || video){"图片不能包含音轨"}
        val segments=subDir.split('/').filter {it.isNotBlank()}
        require(segments.isNotEmpty() && segments.size<=2 && segments[0]=="RedNote" && segments.all {it!="." && it!=".." && it.length<=50 && !it.contains(Regex("[\\\\:*?\"<>|\\r\\n]"))}) {"保存路径无效"}
        val conn=URL(url).openConnection() as HttpURLConnection
        connection=conn;conn.connectTimeout=15000;conn.readTimeout=30000;conn.instanceFollowRedirects=false
        conn.setRequestProperty("Referer","https://www.xiaohongshu.com/")
        var pending:android.net.Uri?=null;var legacy:File?=null
        var merged:File?=null
        try {
            require(conn.responseCode in 200..299) {"媒体下载失败，请刷新笔记后重试"}
            val mime=conn.contentType?.substringBefore(';')?.lowercase() ?: ""
            val ext=when(mime){"image/jpeg"->"jpg";"image/png"->"png";"image/webp"->"webp";"image/gif"->"gif";"image/avif"->"avif";"video/mp4"->"mp4";"video/webm"->"webm";else->throw IllegalArgumentException("此媒体格式暂不支持保存，可继续在线查看")}
            require(if(video)mime.startsWith("video/") else mime.startsWith("image/")) {"媒体格式与保存类型不一致"}
            val limit=if(video)512L*1024*1024 else 40L*1024*1024
            require(conn.contentLengthLong<=limit) {"媒体文件过大"}
            if(audioUrl.isNotEmpty())merged=MediaRemuxer.merge(activity.cacheDir,conn,audioUrl)
            val name="rn_${System.currentTimeMillis()}.${if(audioUrl.isNotEmpty())"mp4" else ext}"
            val base=if(video)Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val relative="$base/${segments.joinToString("/") }"
            val output=if(Build.VERSION.SDK_INT>=29) {
                val values=ContentValues().apply {put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,if(audioUrl.isNotEmpty())"video/mp4" else mime);put(MediaStore.MediaColumns.RELATIVE_PATH,relative);put(MediaStore.MediaColumns.IS_PENDING,1)}
                val collection=if(video)MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                pending=activity.contentResolver.insert(collection,values) ?: error("media insert")
                activity.contentResolver.openOutputStream(pending!!) ?: error("media output")
            } else {
                val root=Environment.getExternalStoragePublicDirectory(base).canonicalFile
                val dir=File(root,segments.joinToString("/")).canonicalFile
                require(dir.path.startsWith(root.path+File.separator)) {"保存路径无效"}
                check(dir.isDirectory || dir.mkdirs());legacy=File(dir,name);legacy!!.outputStream()
            }
            output.use {target -> (merged?.inputStream() ?: conn.inputStream).use {input ->
                val bytes=ByteArray(65536);var written=0L
                while(true){val n=input.read(bytes);if(n<0)break;if(Thread.currentThread().isInterrupted)error("cancelled");written+=n;require(written<=limit){"媒体文件过大"};target.write(bytes,0,n)}
                require(written>0){"媒体内容为空"}
            }}
            if(Build.VERSION.SDK_INT>=29)activity.contentResolver.update(pending!!,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            legacy?.let {MediaScannerConnection.scanFile(activity,arrayOf(it.path),arrayOf(if(audioUrl.isNotEmpty())"video/mp4" else mime),null)}
            return relative
        } catch(e:Exception) {
            pending?.let {activity.contentResolver.delete(it,null,null)};legacy?.delete();throw e
        } finally {merged?.delete();conn.disconnect();connection=null}
    }
    fun close(){connection?.disconnect();worker.shutdownNow();channel.setMethodCallHandler(null)}
}
