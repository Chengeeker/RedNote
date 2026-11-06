package com.red.note

import android.icu.text.BreakIterator
import java.util.Locale

internal fun takeCharacters(value: String, limit: Int): String {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
    var end = iterator.first()
    repeat(limit) {
        val next = iterator.next()
        if (next == BreakIterator.DONE) return value
        end = next
    }
    return value.substring(0, end)
}

internal fun characterCount(value: String): Int {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
    var count = 0
    while (iterator.next() != BreakIterator.DONE) count++
    return count
}
