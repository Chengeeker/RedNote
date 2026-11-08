package com.red.note

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun AccountPage(
    state: RedState,
    activity: MainActivity,
    open: (Note) -> Unit,
    author: (Map<String, Any?>) -> Unit,
    settings: () -> Unit,
    albums: () -> Unit,
    onViewsSynced: (Int) -> Unit,
) {
    val memory = state.accountProfile
    var busy by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var failure by memory::failure
    val scope = rememberCoroutineScope()
    fun refresh(sync: Boolean) {
        if (busy || !state.loggedIn) return
        busy = true
        failure = ""
        val account = state.userId
        val generation = state.epoch
        scope.launch {
            try {
                supervisorScope {
                    val details = async {
                        val self = state.call("selfInfo")
                        val full = state.call("profile", mapOf("id" to account))
                        self.keys().forEach { key ->
                            if (!full.has(key)) full.put(key, self.get(key))
                        }
                        full
                    }
                    val stats =
                        if (sync)
                            async {
                                val cached =
                                    state.feeds["userPosts:${JSONObject(mapOf("id" to account))}"]
                                        ?.notes
                                        .orEmpty()
                                val ids =
                                    if (cached.isNotEmpty()) cached.map { it.id }.toSet()
                                    else {
                                        val posts = state.call("userPosts", mapOf("id" to account))
                                        posts
                                            .rows(if (posts.has("items")) "items" else "notes")
                                            .mapNotNull { Note.parse(it, "pc_user")?.id }
                                            .toSet()
                                    }
                                if (ids.isEmpty()) emptyMap() else activity.syncStats(ids)
                            }
                        else null
                    try {
                        val result = details.await()
                        if (state.epoch == generation) memory.profile = result
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failure = e.message.orEmpty()
                    }
                    if (stats != null)
                        try {
                            val counts = stats.await()
                            if (state.epoch != generation) throw CancellationException("账号已变化")
                            val key = "viewCounts:$account"
                            val old =
                                runCatching { JSONObject(state.preferences.text(key, "{}")) }
                                    .getOrDefault(JSONObject())
                            counts.forEach { (id, count) -> if (count >= 0) old.put(id, count) }
                            state.preferences.set(key, old.toString())
                            onViewsSynced(counts.size)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            state.message = e.message.orEmpty()
                        }
                }
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(memory) { if (memory.profile == null && failure.isEmpty()) refresh(false) }
    val header: @Composable () -> Unit = {
        Column {
            ProfileHeader(
                state,
                memory.profile ?: state.session.optJSONObject("user") ?: JSONObject(),
                false,
                actions = {
                    Spacer(Modifier.weight(1f))
                    if (state.loggedIn) ProfileRefreshButton(busy) { refresh(true) }
                    GlyphButton("settings_outlined", "设置", action = settings)
                },
            )
            if (failure.isNotEmpty()) Failure(failure) { refresh(false) }
            if (state.loggedIn)
                Row {
                    listOf("笔记", "收藏", "赞过").forEachIndexed { i, label ->
                        Column(
                            Modifier.weight(1f).clickable { selected = i },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                label,
                                Modifier.padding(vertical = 14.dp),
                                fontSize = 15.sp,
                                fontWeight =
                                    if (selected == i)
                                        androidx.compose.ui.text.font.FontWeight.SemiBold
                                    else androidx.compose.ui.text.font.FontWeight.Normal,
                                color =
                                    if (selected == i) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(2.dp)
                                    .background(
                                        if (selected == i) MaterialTheme.colorScheme.primary
                                        else Color.Transparent
                                    )
                            )
                        }
                    }
                }
            if (selected == 1 && state.loggedIn)
                OriginalListItem(
                    headlineContent = { Text("收藏专辑") },
                    leadingContent = { Glyph("folder_outlined") },
                    trailingContent = { Glyph("chevron_right") },
                    modifier = Modifier.clickable(onClick = albums),
                )
        }
    }
    if (state.loggedIn)
        key(selected) {
            NoteFeed(
                state,
                listOf("userPosts", "collections", "likedNotes")[selected],
                mapOf("id" to state.userId),
                "pc_user",
                open,
                author,
                compact = true,
                showViews = selected == 0,
                header = header,
                viewCounts =
                    if (selected == 0)
                        runCatching {
                                JSONObject(
                                    state.preferences.text("viewCounts:${state.userId}", "{}")
                                )
                            }
                            .getOrDefault(JSONObject())
                    else JSONObject(),
            )
        }
    else
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
            header()
            LoginPrompt(activity, "登录后查看个人笔记", fill = false)
        }
}

@Composable
internal fun ProfileRefreshButton(busy: Boolean, refresh: () -> Unit) {
    val angle = if (busy) {
        val motion = rememberInfiniteTransition(label = "profile-refresh")
        motion.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "profile-refresh-angle",
        )
    } else rememberUpdatedState(0f)
    IconButton(
        onClick = tapAction(refresh),
        enabled = !busy,
        colors = IconButtonDefaults.iconButtonColors(disabledContentColor = LocalContentColor.current),
    ) {
        Box(Modifier.graphicsLayer { rotationZ = angle.value }) {
            Glyph("refresh", "刷新个人资料")
        }
    }
}

@Composable
internal fun ProfileHeader(
    state: RedState,
    profile: JSONObject,
    details: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
) {
    val basic = profile.optJSONObject("basic_info") ?: profile.optJSONObject("basicInfo") ?: profile
    val background = profileBackgroundUrl(profile)
    val foreground = if (background.isEmpty()) MaterialTheme.colorScheme.onSurface else Color.White
    val clipboard = LocalClipboardManager.current
    Box {
        if (background.isNotEmpty()) {
            Picture(background, null, Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = .35f), Color.Black.copy(alpha = .78f))
                        )
                    )
            )
        }
        CompositionLocalProvider(LocalContentColor provides foreground) {
            Column(
                Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (details) 0.dp else 8.dp,
                    bottom = if (details) 12.dp else 24.dp,
                )
            ) {
                Row(Modifier.fillMaxWidth(), content = actions)
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = if (details) Alignment.Top else Alignment.CenterVertically
                ) {
                    Box(Modifier.testTag("profile-avatar")) {
                        Avatar(basic, if (details) 80.dp else 64.dp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            basic.optString("nickname", basic.optString("nick_name")).ifEmpty {
                                if (state.loggedIn) "我的主页" else "欢迎使用 RedNote"
                            },
                            fontSize = 20.sp,
                            lineHeight = 24.sp,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("profile-name"),
                        )
                        val id = basic.optString("red_id", basic.optString("redId"))
                        val ip =
                            basic
                                .optString("ip_location")
                                .replace(Regex("^IP(?:属地)?\\s*[:：]\\s*"), "")
                        if (id.isNotEmpty()) {
                            Spacer(Modifier.height(if (details) 4.dp else 6.dp))
                            if (details)
                                Column(
                                    Modifier.heightIn(min = 48.dp).clickable {
                                        clipboard.setText(AnnotatedString(id))
                                        state.message = "已复制小红书号"
                                    },
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "小红书号：$id",
                                            fontSize = 12.sp,
                                            lineHeight = 16.2.sp,
                                            color =
                                                if (background.isEmpty())
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                else Color.White.copy(alpha = .82f),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Glyph(
                                            "copy_outlined",
                                            null,
                                            14.dp,
                                            if (background.isEmpty())
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            else Color.White.copy(alpha = .82f),
                                        )
                                    }
                                    if (ip.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "IP：$ip",
                                            fontSize = 12.sp,
                                            lineHeight = 16.2.sp,
                                            color =
                                                if (background.isEmpty())
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                else Color.White.copy(alpha = .82f),
                                        )
                                    }
                                }
                            else
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        "小红书号：$id",
                                        fontSize = 12.sp,
                                        color =
                                            if (background.isEmpty())
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            else Color.White.copy(alpha = .82f),
                                    )
                                }
                        }
                        if (details && id.isEmpty() && ip.isNotEmpty())
                            Text(
                                "IP：$ip",
                                fontSize = 12.sp,
                                color =
                                    if (background.isEmpty())
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else Color.White.copy(alpha = .82f),
                            )
                    }
                }
                val stats = profile.rows("interactions")
                if (stats.isNotEmpty())
                    FlowRow(
                        Modifier.testTag("profile-stats").padding(top = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        stats.forEach { stat ->
                            val label =
                                stat.optString("name").ifEmpty {
                                    mapOf(
                                            "follows" to "关注",
                                            "fans" to "粉丝",
                                            "interaction" to "获赞与收藏",
                                        )[stat.optString("type")]
                                        .orEmpty()
                                }
                            Text(
                                androidx.compose.ui.text.buildAnnotatedString {
                                    pushStyle(
                                        androidx.compose.ui.text.SpanStyle(
                                            fontWeight =
                                                androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                    )
                                    append(stat.optString("i18n_count", stat.optString("count")))
                                    pop()
                                    append(" $label")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                val desc = basic.optString("desc", basic.optString("description"))
                if (desc.isNotEmpty())
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        RichText(
                            desc,
                            Modifier.testTag("profile-description").padding(top = 18.dp),
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = foreground,
                                    lineHeight = 19.6.sp,
                                ),
                        )
                    }
                val tags = profile.rows("tags").filter { it.optString("name").isNotEmpty() }
                if (tags.isNotEmpty())
                    FlowRow(
                        Modifier.padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            Text(
                                tag.optString("name"),
                                Modifier.background(
                                        if (background.isEmpty())
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        else Color.White.copy(alpha = .16f),
                                        RoundedCornerShape(20.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = foreground,
                            )
                        }
                    }
                if (footer != null) {
                    Spacer(Modifier.height(if (details) 10.dp else 16.dp))
                    footer()
                }
            }
        }
    }
}
