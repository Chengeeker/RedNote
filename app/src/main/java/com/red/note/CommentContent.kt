package com.red.note

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject

internal fun commentTimeMillis(time: Long): Long =
    if (time < 100_000_000_000L) time.coerceAtLeast(0) * 1000 else time

internal fun publicationMeta(raw: JSONObject): String {
    val value = raw.optString("time").toDoubleOrNull()
    var date = ""
    if (value != null && value.isFinite() && value > 0 && value < 8640000000000000) {
        val calendar =
            java.util.Calendar.getInstance().apply {
                timeInMillis = (if (value < 100000000000) value * 1000 else value).toLong()
            }
        val year = calendar.get(java.util.Calendar.YEAR)
        date =
            (if (year == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) ""
            else "${year}年") +
                "${calendar.get(java.util.Calendar.MONTH)+1}月${calendar.get(java.util.Calendar.DAY_OF_MONTH)}日"
    }
    return listOf(date, raw.optString("ip_location").replace(Regex("^IP属地[:：]\\s*"), "").trim())
        .filter(String::isNotEmpty)
        .joinToString("  ")
}

@Composable
internal fun CommentRow(
    state: RedState,
    note: Note,
    initial: JSONObject,
    child: Boolean = false,
    reply: (String, String) -> Unit,
    gallery: (Page.Gallery) -> Unit,
    deleted: (String) -> Unit = {},
) {
    val id = initial.optString("id")
    val key = "${note.id}:$id"
    val comment = state.commentValues[key] ?: initial
    if (key in state.deletedComments) return
    val user = comment.optJSONObject("user_info") ?: JSONObject()
    val name = user.optString("nickname").ifEmpty { "用户" }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun mutate(method: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val liked = !comment.optBoolean("liked")
                state.call(method, note.args + mapOf("commentId" to id, "value" to liked))
                if (method == "deleteComment") {
                    state.deletedComments.add(key)
                    deleted(id)
                } else {
                    val result = JSONObject(comment.toString()).put("liked", liked)
                    comment.optString("like_count").toIntOrNull()?.let {
                        result.put(
                            "like_count",
                            (it + if (liked) 1 else -1).coerceAtLeast(0).toString(),
                        )
                    }
                    state.commentValues[key] = result
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.message = e.message.orEmpty()
            } finally {
                busy = false
            }
        }
    }
    Row(
        Modifier.padding(
            start = if (child) 68.dp else 16.dp,
            end = 16.dp,
            top = if (child) 10.dp else 12.dp,
            bottom = if (child) 0.dp else 10.dp,
        )
    ) {
        Avatar(user, if (child) 30.dp else 40.dp)
        Spacer(Modifier.width(if (child) 8.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(if (child) 3.dp else 6.dp))
            RichText(
                comment.optString("content"),
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = if (child) 22.4.sp else 23.2.sp
                    ),
            )
            if (!child) {
                val pictures =
                    remember(comment) {
                        comment.rows("pictures").filter { imageUrl(it).isNotEmpty() }
                    }
                val anchors = remember(initial) { mutableMapOf<Int, GalleryAnchor>() }
                pictures.forEachIndexed { index, pic ->
                    val original = originalImageUrl(pic)
                    var image by remember(pic) { mutableStateOf<ImageBitmap?>(null) }
                    var visible by remember(pic) { mutableStateOf(false) }
                    LaunchedEffect(original, visible) {
                        if (visible && !state.hdrImages.containsKey(original)) {
                            delay(250)
                            state.inspectHdr(original)
                        }
                    }
                    Box(
                        Modifier.padding(top = 8.dp)
                            .size(120.dp, 100.dp)
                            .onGloballyPositioned {
                                anchors[index] = GalleryAnchor(it.galleryViewport(), crop = true)
                                visible = it.boundsInWindow().height >= it.size.height * .5f
                            }
                            .hideGallerySource(initial, index)
                            .clickable {
                                gallery(
                                    Page.Gallery(
                                        pictures.map(::originalImageUrl),
                                        index,
                                        name,
                                        previews = pictures.map(::imageUrl),
                                        heroScope = initial,
                                        flight = GalleryFlight(image, anchors.toMap()::get, index),
                                    )
                                )
                            }
                    ) {
                        Picture(
                            imageUrl(pic),
                            "评论图片 ${index+1}",
                            Modifier.fillMaxSize(),
                            onImageReady = { image = it },
                        )
                        if (state.hdrImages[original] == true)
                            InlineHdrBadge(Modifier.align(Alignment.BottomEnd).padding(4.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val rawTime = comment.optString("create_time").toLongOrNull() ?: 0
                val milliseconds = commentTimeMillis(rawTime)
                val days = (System.currentTimeMillis() - milliseconds) / 86400000
                val time =
                    if (rawTime > 0 && days in 7..29) "${days}天前" else notificationTime(rawTime)
                Text(
                    listOf(
                            time,
                            comment
                                .optString("ip_location")
                                .replace(Regex("^IP属地[:：]\\s*"), "")
                                .trim(),
                        )
                        .filter(String::isNotEmpty)
                        .joinToString("  "),
                    Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = tapAction { reply(id, name) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("回复", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (
                    !child && state.userId.isNotEmpty() && user.optString("user_id") == state.userId
                )
                    TextButton(
                        enabled = !busy,
                        onClick = tapAction { confirmDelete = true },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                TextButton(
                    enabled = !busy,
                    onClick = tapAction { mutate("likeComment") },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Glyph(
                        if (comment.optBoolean("liked")) "favorite_rounded"
                        else "favorite_border_rounded",
                        if (child) "点赞回复" else "点赞评论",
                        19.dp,
                        if (comment.optBoolean("liked")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        comment.optString("like_count"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirmDelete)
        AppAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条评论？") },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick =
                        tapAction {
                            confirmDelete = false
                            mutate("deleteComment")
                        },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = tapAction { confirmDelete = false }) { Text("取消") }
            },
        )
}

@Composable
internal fun RepliesPage(
    state: RedState,
    note: Note,
    rootId: String,
    back: () -> Unit,
    gallery: (Page.Gallery) -> Unit,
    replies: (Note, String) -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var editorRevision by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<Pair<String, String>?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column {
            PageBar("回复", back)
            key(refresh) {
                JsonList(state, "subComments", note.args + ("rootId" to rootId), "comments") { row
                    ->
                    if ("${note.id}:${row.optString("id")}" !in state.deletedComments)
                        Column {
                            CommentRow(
                                state,
                                note,
                                row,
                                reply = { id, name ->
                                    editorRevision++
                                    editor = id to name
                                },
                                gallery = gallery,
                                deleted = { state.adjustComments(note, -1) },
                            )
                            val children = row.rows("sub_comments").take(3)
                            children.forEach { child ->
                                CommentRow(
                                    state,
                                    note,
                                    child,
                                    child = true,
                                    reply = { id, name ->
                                        editorRevision++
                                        editor = id to name
                                    },
                                    gallery = gallery,
                                )
                            }
                            val count = row.optInt("sub_comment_count", children.size)
                            if (count > children.size)
                                CommentExpansion(count) { replies(note, row.optString("id")) }
                        }
                }
            }
        }
        editor?.let { (id, name) ->
            key(editorRevision) {
                CommentEditor(
                    state,
                    note,
                    id,
                    name,
                    { editor = null },
                    {
                        state.adjustComments(note, 1)
                        state.jsonLists.remove(
                            "subComments:${JSONObject(note.args+("rootId" to rootId))}"
                        )
                        refresh++
                    },
                )
            }
        }
    }
}

@Composable
internal fun CommentExpansion(count: Int, expand: () -> Unit) {
    TextButton(
        onClick = tapAction(expand),
        modifier = Modifier.padding(start = 68.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Box(Modifier.width(24.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(8.dp))
        Text("展开${count}条回复", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
