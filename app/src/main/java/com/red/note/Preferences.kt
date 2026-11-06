package com.red.note

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.*
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import org.json.JSONArray

internal class Preferences(context: Context) {
    private val current = context.getSharedPreferences("compose_preferences", 0)
    private val legacy = context.getSharedPreferences("FlutterSharedPreferences", 0)
    var revision by mutableIntStateOf(0)
        private set

    fun value(key: String): Any? {
        revision
        return if (current.contains(key)) current.all[key] else legacy.all["flutter.$key"]
    }

    fun bool(key: String, fallback: Boolean = false) = value(key) as? Boolean ?: fallback

    fun int(key: String, fallback: Int = 0) = (value(key) as? Number)?.toInt() ?: fallback

    fun text(key: String, fallback: String = "") = value(key) as? String ?: fallback

    fun number(key: String, fallback: Double = 1.0): Double =
        (when (val v = value(key)) {
                is Number -> v.toDouble()
                is String ->
                    v.removePrefix("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu").toDoubleOrNull()
                        ?: fallback
                else -> fallback
            })
            .takeIf { it.isFinite() } ?: fallback

    fun strings(key: String): List<String> {
        val raw = text(key)
        if (raw.isBlank() || raw.length > 65536) return emptyList()
        val prefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
        return runCatching {
                if (raw.startsWith(prefix) && !raw.startsWith("$prefix!")) {
                    val bytes = Base64.decode(raw.removePrefix(prefix), Base64.DEFAULT)
                    val stream =
                        object : ObjectInputStream(ByteArrayInputStream(bytes)) {
                            override fun resolveClass(desc: java.io.ObjectStreamClass): Class<*> {
                                require(
                                    desc.name in
                                        setOf(
                                            "java.util.Arrays\$ArrayList",
                                            "java.util.ArrayList",
                                            "java.lang.String",
                                            "[Ljava.lang.String;",
                                        )
                                ) {
                                    "Invalid preference class"
                                }
                                return super.resolveClass(desc)
                            }
                        }
                    stream.use {
                        (it.readObject() as? List<*>)
                            ?.filterIsInstance<String>()
                            ?.take(100)
                            .orEmpty()
                    }
                } else {
                    val array = JSONArray(raw.removePrefix("$prefix!"))
                    (0 until array.length().coerceAtMost(100)).map { array.getString(it) }
                }
            }
            .getOrDefault(emptyList())
    }

    fun set(key: String, value: Any) {
        val edit = current.edit()
        when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Double -> edit.putString(key, value.toString())
            is List<*> -> edit.putString(key, JSONArray(value).toString())
            else -> edit.putString(key, value.toString())
        }
        edit.apply()
        revision++
    }
}
