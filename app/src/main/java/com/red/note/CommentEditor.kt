@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.red.note

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.icu.text.BreakIterator
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.clickable as foundationClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val commentLimit = 2000

internal fun limitCommentInput(value: TextFieldValue): TextFieldValue {
    if (value.text.length <= commentLimit) return value
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(value.text)
    return TextFieldValue(value.text.take(iterator.preceding(commentLimit + 1)), value.selection)
}

internal fun insertCommentInput(value: TextFieldValue, input: String): TextFieldValue? {
    val start = value.selection.min
    val end = value.selection.max
    if (value.text.length - (end - start) + input.length > commentLimit) return null
    return TextFieldValue(
        value.text.replaceRange(start, end, input),
        TextRange(start + input.length),
    )
}

@Composable
internal fun CommentEditor(
    state: RedState,
    note: Note,
    target: String,
    author: String,
    close: () -> Unit,
    sent: () -> Unit,
) {
    var draft by
        rememberSaveable(note.id, target, state.epoch, stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue())
        }
    var busy by remember { mutableStateOf(false) }
    var hiding by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }
    var inputHint by remember { mutableStateOf("") }
    var emojiPanel by rememberSaveable { mutableStateOf(false) }
    var voicePending by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    val canDismiss by rememberUpdatedState(!busy && !voicePending && !hiding)
    val latestClose by rememberUpdatedState(close)
    val latestSent by rememberUpdatedState(sent)
    val editable = !busy && !hiding
    val focus = remember { FocusRequester() }
    val emojis = rememberEmojiCodes()
    val allEmojis = remember(emojis) { emojis.keys().asSequence().toList() }
    val quickEmojis =
        remember(emojis) {
            listOf("[笑哭R]", "[偷笑R]", "[哭惹R]", "[飞吻R]", "[害羞R]", "[微笑R]", "[黄金薯R]")
                .filter(emojis::has)
        }
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "语音转文字")
    }
    val voiceAvailable =
        remember(activity) { voiceIntent.resolveActivity(activity.packageManager) != null }
    fun insert(input: String) {
        if (busy || hiding) return
        val next = insertCommentInput(draft, input)
        if (next == null) inputHint = "评论内容过长"
        else {
            draft = next
            inputHint = ""
        }
    }
    val voice =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            voicePending = false
            if (it.resultCode == Activity.RESULT_OK) {
                val recognized =
                    it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!recognized.isNullOrBlank()) insert(recognized)
            }
        }

    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val windowInfo = LocalWindowInfo.current
    val keyboardMode = !emojiPanel && !voicePending && !activity.loginBusy && !hiding
    fun dismiss() {
        if (hiding) return
        hiding = true
        keyboard?.hide()
        latestClose()
    }

    BackHandler(enabled = !hiding) { if (canDismiss) dismiss() }
    LaunchedEffect(keyboardMode) {
        if (!keyboardMode) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        focus.requestFocus()
        keyboard?.show()
        var shown = false
        snapshotFlow {
                Triple(
                    ime.getBottom(density),
                    imeSource.getBottom(density),
                    imeTarget.getBottom(density),
                )
            }
            .collect { (current, source, target) ->
                if (!keyboardMode || emojiPanel || voicePending || activity.loginBusy || hiding)
                    return@collect
                if (
                    shown &&
                        target == 0 &&
                        (current == 0 || current < source) &&
                        canDismiss &&
                        windowInfo.isWindowFocused
                ) {
                    dismiss()
                } else if (current > 0 && (target > 0 || source == 0)) {
                    shown = true
                } else if (current == 0) {
                    shown = false
                }
            }
    }
    if (hiding) return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.matchParentSize()
                .background(Color.Black.copy(alpha = .25f))
                .testTag("comment-scrim")
                .foundationClickable(
                    enabled = canDismiss,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "关闭评论",
                    onClick = { dismiss() },
                )
        )
        Box(
            Modifier.matchParentSize().windowInsetsPadding(WindowInsets.safeDrawing).clipToBounds()
        ) {
            Surface(
                modifier =
                    Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp)
                ) {
                    val fieldColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    TextField(
                        value = draft,
                        onValueChange = {
                            draft = limitCommentInput(it)
                            inputHint = ""
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .focusRequester(focus)
                                .onFocusChanged { if (it.isFocused) emojiPanel = false },
                        readOnly = !editable,
                        minLines = 2,
                        maxLines = 4,
                        textStyle = appTextStyle(MaterialTheme.typography.bodyLarge),
                        placeholder = {
                            Text(if (author.isBlank()) "有话要说，快来评论" else "回复 $author", maxLines = 1)
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = fieldColor,
                                unfocusedContainerColor = fieldColor,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (voiceAvailable)
                            GlyphButton(
                                "mic_none_rounded",
                                "语音转文字",
                                enabled = editable && !voicePending,
                            ) {
                                voicePending = true
                                try {
                                    voice.launch(voiceIntent)
                                } catch (_: ActivityNotFoundException) {
                                    voicePending = false
                                    inputHint = "设备没有可用的语音识别服务"
                                } catch (_: SecurityException) {
                                    voicePending = false
                                    inputHint = "设备无法打开语音识别服务"
                                }
                            }
                        GlyphButton("alternate_email", "插入 @ 符号", enabled = editable) {
                            insert("@")
                        }
                        GlyphButton(
                            if (emojiPanel) "keyboard_alt_outlined"
                            else "sentiment_satisfied_alt_rounded",
                            if (emojiPanel) "返回键盘" else "更多表情",
                            enabled = editable,
                        ) {
                            emojiPanel = !emojiPanel
                            if (emojiPanel) {
                                focusManager.clearFocus()
                                keyboard?.hide()
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = editable && draft.text.isNotBlank(),
                            onClick =
                                tapAction {
                                    if (busy || hiding) return@tapAction
                                    busy = true
                                    failure = ""
                                    val content = draft.text.trim()
                                    scope.launch {
                                        try {
                                            try {
                                                state.call(
                                                    "postComment",
                                                    note.args +
                                                        mapOf(
                                                            "targetId" to target,
                                                            "content" to content,
                                                        ),
                                                )
                                            } catch (e: Exception) {
                                                if (e is CancellationException) throw e
                                                failure = e.message.orEmpty().ifBlank { "发送失败，请重试" }
                                                return@launch
                                            }
                                            latestSent()
                                            dismiss()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                        ) {
                            Text(if (busy) "发送中…" else "发送")
                        }
                    }
                    if (emojiPanel) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(48.dp),
                            modifier = Modifier.fillMaxWidth().height(184.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            items(allEmojis, key = { it }) { token ->
                                CommentEmoji(token, emojis.optString(token), editable) {
                                    insert(token)
                                }
                            }
                        }
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(quickEmojis, key = { it }) { token ->
                                CommentEmoji(token, emojis.optString(token), editable) {
                                    insert(token)
                                }
                            }
                        }
                    }
                    if (inputHint.isNotEmpty() || draft.text.length >= 1900)
                        Text(
                            inputHint.ifEmpty { "${draft.text.length}/$commentLimit" },
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    if (failure.isNotEmpty()) {
                        Text(
                            failure,
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (
                            state.lastFailure?.kind in listOf("login", "signature", "verification")
                        ) {
                            TextButton(
                                onClick =
                                    tapAction {
                                        activity.openLogin(
                                            state.lastFailure?.kind == "verification"
                                        )
                                    },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                Text("网页登录后重试")
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun CommentEmoji(token: String, url: String, enabled: Boolean, insert: () -> Unit) {
    Box(
        Modifier.size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = insert)
            .semantics { contentDescription = token },
        contentAlignment = Alignment.Center,
    ) {
        Picture(
            mediaUrl(url),
            null,
            Modifier.size(28.dp),
            ContentScale.Fit,
            background = Color.Transparent,
        )
    }
}
