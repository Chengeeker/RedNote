package com.red.note

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject

internal fun notificationNote(row: JSONObject): Note? {
    var item = row.optJSONObject("item_info") ?: return null
    if (item.optString("type") == "board_info")
        item = item.optJSONObject("attach_item_info") ?: return null
    val link = runCatching { java.net.URI(item.optString("link")) }.getOrNull()
    val query =
        link
            ?.rawQuery
            .orEmpty()
            .split('&')
            .mapNotNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size != 2) null
                else
                    runCatching { pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8") }
                        .getOrNull()
            }
            .toMap()
    val id =
        item.optString("note_id", item.optString("id")).ifEmpty {
            link?.path.orEmpty().substringAfterLast('/')
        }
    val token = item.optString("xsec_token").ifEmpty { query["xsec_token"].orEmpty() }
    if (!id.matches(Regex("[A-Za-z0-9]{1,128}")) || token.isBlank() || token.length > 8192)
        return null
    return Note(
        id,
        token,
        query["xsec_source"].orEmpty().ifEmpty { "pc_notice" },
        JSONObject(item.toString()).put("user", row.optJSONObject("user_info") ?: JSONObject()),
    )
}

@Composable
internal fun MessageDetail(
    state: RedState,
    activity: MainActivity,
    type: String,
    open: (Note) -> Unit,
    profile: (Map<String, Any?>) -> Unit,
    back: () -> Unit,
) {
    var editorRevision by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Triple<Note, String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        Column {
            PageBar(
                mapOf("likes" to "赞和收藏", "mentions" to "评论和@", "connections" to "新增关注")[type]
                    .orEmpty(),
                back,
                actions = {
                    if (type != "connections")
                        TextButton(
                            enabled = !busy,
                            onClick =
                                tapAction {
                                    busy = true
                                    scope.launch {
                                        try {
                                            state.call("readMessages", mapOf("type" to type))
                                            state.message = "已标记已读"
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                            state.message = e.message.orEmpty()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                        ) {
                            Text("标记已读")
                        }
                },
            )
            JsonList(state, type, emptyMap(), "message_list") { row ->
                val user =
                    row.optJSONObject("user_info") ?: row.optJSONObject("user") ?: JSONObject()
                val comment = row.optJSONObject("comment_info") ?: JSONObject()
                val note = notificationNote(row)
                val id = user.optString("user_id", user.optString("userid"))
                val cid = comment.optString("id", comment.optString("comment_id"))
                var followed by remember {
                    mutableStateOf(user.optString("fstatus") in listOf("both", "follows"))
                }
                var liked by remember { mutableStateOf(comment.optBoolean("liked")) }
                var pending by remember { mutableStateOf(false) }
                fun action(method: String, args: Map<String, Any?>, done: () -> Unit) {
                    if (pending) return
                    pending = true
                    scope.launch {
                        try {
                            state.call(method, args)
                            done()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            state.message = e.message.orEmpty()
                        } finally {
                            pending = false
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 22.dp)) {
                    Avatar(user, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            user.optString("nickname"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        RichText(
                            listOf(
                                    row.optString("title").ifEmpty {
                                        if (type == "connections") "开始关注你了" else ""
                                    },
                                    notificationTime(row.opt("time") ?: row.opt("create_time")),
                                )
                                .filter(String::isNotEmpty)
                                .joinToString("  "),
                            Modifier.padding(top = 10.dp),
                            style =
                                LocalTextStyle.current.copy(
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                        if (comment.optString("content").isNotEmpty())
                            RichText(
                                comment.optString("content"),
                                Modifier.padding(top = 12.dp),
                                style =
                                    LocalTextStyle.current.copy(
                                        fontSize = 15.sp,
                                        lineHeight = 22.5.sp,
                                    ),
                            )
                        if (type == "mentions" && note != null && cid.isNotEmpty())
                            FlowRow(
                                Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CompactAction("回复", "chat_bubble_outline") {
                                    editorRevision++
                                    reply = Triple(note, cid, user.optString("nickname"))
                                }
                                CompactAction(
                                    "赞",
                                    if (liked) "favorite" else "favorite_border",
                                    !pending,
                                ) {
                                    action(
                                        "likeComment",
                                        note.args + mapOf("commentId" to cid, "value" to !liked),
                                    ) {
                                        liked = !liked
                                    }
                                }
                            }
                    }
                    if (type == "connections" && id.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(76.dp, 44.dp), contentAlignment = Alignment.Center) {
                            if (followed)
                                Text(
                                    "已关注",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                )
                            else
                                OutlinedButton(
                                    enabled = !pending,
                                    onClick =
                                        tapAction {
                                            action("followUser", mapOf("id" to id)) {
                                                followed = true
                                            }
                                        },
                                    modifier = Modifier.size(76.dp, 32.dp),
                                    border = BorderStroke(1.dp, Color(0xFFFF2442)),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(
                                        if (user.optString("fstatus") == "fans") "回关" else "关注",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFFFF2442),
                                    )
                                }
                        }
                    } else {
                        val item = row.optJSONObject("item_info") ?: JSONObject()
                        val raw = note?.raw ?: item.optJSONObject("attach_item_info") ?: item
                        val thumbnail =
                            userImageUrl(
                                JSONObject()
                                    .put(
                                        "images",
                                        raw.opt("image")
                                            ?: raw.opt("cover")
                                            ?: raw.opt("image_info")
                                            ?: raw.opt("image_url"),
                                    )
                            )
                        if (thumbnail.isNotEmpty()) {
                            Spacer(Modifier.width(12.dp))
                            Picture(
                                thumbnail,
                                "查看笔记",
                                Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).clickable(
                                    enabled = note != null
                                ) {
                                    note?.let(open)
                                },
                            )
                        } else if (note != null) GlyphButton("chevron_right", "查看笔记") { open(note) }
                    }
                }
            }
        }
        reply?.let { (note, target, name) ->
            key(editorRevision) {
                CommentEditor(state, note, target, name, { reply = null }, {})
            }
        }
    }
}

@Composable
internal fun CompactAction(
    label: String,
    icon: String,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp)
                .background(
                    if (enabled) colors.secondaryContainer else colors.onSurface.copy(alpha = .12f),
                    RoundedCornerShape(999.dp),
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Glyph(
                icon,
                null,
                14.dp,
                if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = .38f),
            )
            Text(
                label,
                fontSize = 12.sp,
                lineHeight = 14.4.sp,
                color =
                    if (enabled) colors.onSecondaryContainer
                    else colors.onSurface.copy(alpha = .38f),
            )
        }
    }
}
