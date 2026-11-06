package com.red.note

import com.red.note.protocol.WebSigner
import org.json.JSONObject
import java.nio.charset.StandardCharsets.UTF_8

internal object BrowserContext {
    fun capture(xs: String, common: String, ua: String, width: Int, height: Int, pageStart: Long): JSONObject {
        require(xs.startsWith("XYS_") && xs.length < 65536) { "网页签名版本暂不支持" }
        val envelope = JSONObject(String(WebSigner.decode(xs.substring(4), WebSigner.COMMON), UTF_8))
        val shared = JSONObject(String(WebSigner.decode(common, WebSigner.COMMON), UTF_8))
        val bytes = WebSigner.unpackMns(envelope.getString("x3"))
        var offset = 44
        fun readString(): String {
            require(offset < bytes.size) { "网页签名数据不完整" }
            val n = bytes[offset++].toInt() and 255
            require(offset + n <= bytes.size) { "网页签名身份不完整" }
            return String(bytes, offset, n, UTF_8).also { offset += n }
        }
        val a1 = readString()
        val appId = readString()
        require(offset + 36 == bytes.size) { "网页签名结构已变化" }
        require(bytes.copyOfRange(offset + 16, offset + 20).contentEquals(byteArrayOf(2,97,51,16))) { "网页签名版本不匹配" }
        require(shared.getString("x5") == a1 && shared.getString("x3") == appId && envelope.getString("x1") == appId) { "网页身份不一致" }
        require(envelope.getString("x0") == shared.getString("x1") && envelope.getString("x2") == shared.getString("x2")) { "网页签名环境不一致" }
        val platform = shared.getString("x2")
        require(compatible(ua, platform)) { "网页 UA 与签名平台不一致，请重新登录" }
        val x12 = shared.getString("x12")
        require(x12.contains(';')) { "网页环境尚未就绪" }
        return JSONObject().put("a1", a1).put("platform", platform).put("appId", appId)
            .put("sdk", shared.getString("x1")).put("webBuild", shared.getString("x4"))
            .put("shared", shared).put("environment", WebSigner.encode(bytes.copyOfRange(offset,offset+16),WebSigner.STANDARD))
            .put("loadTime", WebSigner.le(bytes,16,8)).put("mnsCount",WebSigner.le(bytes,24,4))
            .put("propertyCount",WebSigner.le(bytes,28,4)).put("width",width).put("height",height).put("pageStart",pageStart)
    }
    fun compatible(ua: String, platform: String): Boolean = ua.isNotBlank() && when(platform) {
        "Android" -> ua.contains("Android")
        "Windows" -> ua.contains("Windows NT")
        "Mac OS" -> ua.contains("Macintosh") || ua.contains("Mac OS")
        "Linux" -> ua.contains("Linux")
        else -> false
    }
    fun signer(json: JSONObject): WebSigner.Context {
        val shared = json.getJSONObject("shared")
        val x12 = shared.getString("x12").split(';', limit=2)
        return WebSigner.Context().apply {
            a1=json.getString("a1"); platform=json.getString("platform"); appId=json.getString("appId")
            sdk=json.getString("sdk");webBuild=json.getString("webBuild");fingerprint=shared.getString("x8")
            commonX0=shared.getString("x0");commonX6=shared.getString("x6");commonX7=shared.getString("x7")
            mode=shared.getString("x11");dsllt=x12[0];dsl=x12[1];platformCode=shared.getInt("s0")
            mnsCount=json.getInt("mnsCount");commonCount=shared.getInt("x10");propertyCount=json.getInt("propertyCount")
            loadTime=json.getLong("loadTime");pageStart=json.optLong("pageStart",loadTime)
            width=json.optInt("width");height=json.optInt("height")
            environment=WebSigner.decode(json.getString("environment"),WebSigner.STANDARD)
        }
    }
}
