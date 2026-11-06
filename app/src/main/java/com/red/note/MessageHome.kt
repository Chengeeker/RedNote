package com.red.note

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun MessageHome(state: RedState, activity: MainActivity, open: (String) -> Unit) {
    var counts by remember { mutableStateOf(JSONObject()) }
    LaunchedEffect(state.epoch) {
        if (state.loggedIn) {
            try {
                counts = state.call("unread")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }
    Column {
        MessageHomeHeader(counts, state.loggedIn, open)
        if (!state.loggedIn) LoginPrompt(activity, "登录后查看消息")
        else
            JsonList(state, "privateChats", emptyMap(), "chats") { row ->
                val info = row.optJSONObject("info") ?: JSONObject()
                val name =
                    listOf(
                            info.optString("nickname"),
                            info.optString("nick_name"),
                            info.optString("user_name"),
                            row.optString("user_name"),
                        )
                        .firstOrNull(String::isNotBlank)
                        .orEmpty()
                MessageConversationRow(
                    name = name.ifEmpty { "小红书用户" },
                    preview = row.optString("last_msg_content").ifEmpty { "暂无新消息" },
                    time = notificationTime(row.opt("last_msg_time")),
                    leading = { Avatar(info, 48.dp) },
                ) {
                    activity.chat(row.optString("chat_user_id", row.optString("user_id")), name)
                }
            }
    }
}

@Composable
internal fun MessageHomeHeader(
    counts: JSONObject,
    showCategories: Boolean,
    open: (String) -> Unit,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Text("消息", fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
    if (!showCategories) return
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)) {
        listOf(
                Triple("likes", "赞和收藏", Color(0xFFF76F72)),
                Triple("connections", "新增关注", Color(0xFF5B97F1)),
                Triple("mentions", "评论和@", Color(0xFF58CFA1)),
            )
            .forEachIndexed { index, (type, label, tint) ->
                Column(
                    Modifier.weight(1f).clickable(role = Role.Button) { open(type) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BadgedBox(
                        badge = {
                            if (counts.optInt(type) > 0) Badge { Text(counts.optString(type)) }
                        }
                    ) {
                        val tile = tint.copy(alpha = .12f)
                        val dots = MaterialTheme.colorScheme.surface
                        Box(
                            Modifier.size(48.dp)
                                .testTag("message-category:$type")
                                .background(tile, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (index) {
                                0 -> Glyph("favorite_rounded", null, 28.dp, tint)
                                1 -> Glyph("person_rounded", null, 32.dp, tint)
                                else ->
                                    Canvas(Modifier.size(22.dp)) {
                                        drawCircle(tint)
                                        drawPath(
                                            Path().apply {
                                                moveTo(size.width * .1f, size.height * .65f)
                                                lineTo(size.width * .02f, size.height * .98f)
                                                lineTo(size.width * .4f, size.height * .9f)
                                                close()
                                            },
                                            tint,
                                        )
                                        drawCircle(
                                            dots,
                                            size.width * .08f,
                                            Offset(size.width * .31f, center.y),
                                        )
                                        drawCircle(
                                            dots,
                                            size.width * .08f,
                                            Offset(size.width * .69f, center.y),
                                        )
                                    }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        label,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
    }
}

@Composable
internal fun MessageConversationRow(
    name: String,
    preview: String,
    time: String = "",
    leading: @Composable () -> Unit,
    open: () -> Unit,
) {
    val secondary =
        MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (LocalScaffoldColor.current.luminance() < .5f) .68f else .9f
        )
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = open)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.testTag("message-avatar")) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    Modifier.weight(1f),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (time.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(time, fontSize = 12.sp, color = secondary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            RichText(
                preview,
                maxLines = 1,
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = secondary,
                    ),
            )
        }
    }
}
