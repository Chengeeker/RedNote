package com.red.note

import android.content.Context
import android.webkit.WebSettings
import java.util.regex.Pattern

internal object DesktopUaUtil {
    fun toDesktopUa(rawUa: String): String {
        var ua = rawUa.trim()
        if (ua.contains("Android", ignoreCase = true)) {
            val p1 = Pattern.compile("\\(Linux;\\s*Android[^)]*\\)", Pattern.CASE_INSENSITIVE)
            ua = p1.matcher(ua).replaceAll("(X11; Linux x86_64)")
            val p2 = Pattern.compile("\\s+Version/4\\.0", Pattern.CASE_INSENSITIVE)
            ua = p2.matcher(ua).replaceAll("")
            val p3 = Pattern.compile("\\s+Mobile(?=\\s)", Pattern.CASE_INSENSITIVE)
            ua = p3.matcher(ua).replaceAll("")
            ua = ua.replace(Regex("\\s+"), " ").trim()
        }
        return if (ua.isNotBlank()) ua else "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    fun getDesktopUa(context: Context): String {
        val defaultUa = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        }
        return toDesktopUa(defaultUa)
    }
}
