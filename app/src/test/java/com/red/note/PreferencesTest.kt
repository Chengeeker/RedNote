package com.red.note

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class PreferencesTest {
    @Test
    fun flutterSettingsReadWithoutWritingLegacyFile() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        legacy
            .edit()
            .clear()
            .putLong("flutter.theme", 2)
            .putString("flutter.fontScale", "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu1.25")
            .putString(
                "flutter.searchHistory",
                "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu![\"one\",\"two\"]",
            )
            .commit()
        context.getSharedPreferences("compose_preferences", 0).edit().clear().commit()
        val prefs = Preferences(context)
        assertEquals(2, prefs.int("theme"))
        assertEquals(1.25, prefs.number("fontScale"), 0.0)
        assertEquals(listOf("one", "two"), prefs.strings("searchHistory"))
        val before = legacy.all.toMap()
        prefs.set("theme", 1)
        prefs.set("fontScale", 1.1)
        prefs.set("searchHistory", listOf("new"))
        assertEquals(before, legacy.all)
        assertEquals(1, prefs.int("theme"))
        assertEquals(listOf("new"), prefs.strings("searchHistory"))
    }

    @Test
    fun legacySerializedHistoryAndMalformedValues() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(bytes).use { it.writeObject(arrayListOf("主题", "相册")) }
        val legacy = context.getSharedPreferences("FlutterSharedPreferences", 0)
        legacy
            .edit()
            .putString(
                "flutter.serialized",
                "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu" +
                    android.util.Base64.encodeToString(
                        bytes.toByteArray(),
                        android.util.Base64.NO_WRAP,
                    ),
            )
            .putString("flutter.bad", "broken")
            .commit()
        val prefs = Preferences(context)
        assertEquals(listOf("主题", "相册"), prefs.strings("serialized"))
        assertTrue(prefs.strings("bad").isEmpty())
    }

    @Test
    fun imageCacheThresholdDefaultsAndReadsFlutterPreference() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        legacy.edit().clear().commit()
        context.getSharedPreferences("compose_preferences", 0).edit().clear().commit()
        val preferences = Preferences(context)
        assertEquals(200, ImageCacheMaintenance.threshold(preferences))

        legacy.edit().putInt("flutter.auto_clear_image_cache_threshold_mb", 500).commit()
        assertEquals(500, ImageCacheMaintenance.threshold(preferences))
        preferences.set(ImageCacheMaintenance.thresholdPreference, 100)
        assertEquals(100, ImageCacheMaintenance.threshold(preferences))
        assertEquals(500, legacy.getInt("flutter.auto_clear_image_cache_threshold_mb", -1))
    }

    @org.junit.Test
    fun nonFiniteLegacyFontScaleFallsBackSafely() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val legacy = app.getSharedPreferences("FlutterSharedPreferences", 0)
        legacy
            .edit()
            .putString("flutter.fontScale", "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUuNaN")
            .commit()
        app.getSharedPreferences("compose_preferences", 0).edit().clear().commit()
        org.junit.Assert.assertEquals(1.0, Preferences(app).number("fontScale"), 0.0)
    }

    @Test
    fun textLimitsKeepEmojiAndCombiningCharactersIntact() {
        val value = "好😀e\u0301尾"
        assertEquals(4, characterCount(value))
        assertEquals("好😀e\u0301", takeCharacters(value, 3))
        assertEquals("", takeCharacters(value, 0))
        assertEquals(value, takeCharacters(value, 2000))
    }
}
