package com.red.note

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class CommentInputTest {
    @Test
    fun insertsWholeEmojiTokenAtSelectionAndMovesCursor() {
        val draft = TextFieldValue("前一段后", TextRange(3, 1))
        val inserted = insertCommentInput(draft, "[笑哭R]")!!
        assertEquals("前[笑哭R]后", inserted.text)
        assertEquals(TextRange(6), inserted.selection)
        val fullDraft = TextFieldValue("字".repeat(1999), TextRange(1999))
        assertNull(insertCommentInput(fullDraft, "[笑哭R]"))
        assertEquals(2000, insertCommentInput(fullDraft, "@")!!.text.length)
    }

    @Test
    fun typedLimitDoesNotSplitGraphemeAndLeavesNormalCompositionAlone() {
        val draft = TextFieldValue("a".repeat(1999) + "👩‍💻", TextRange(2004))
        val trimmed = limitCommentInput(draft)
        assertEquals("a".repeat(1999), trimmed.text)
        assertEquals(TextRange(1999), trimmed.selection)
        val composing = TextFieldValue("ni", TextRange(2), TextRange(0, 2))
        assertSame(composing, limitCommentInput(composing))
    }
}
