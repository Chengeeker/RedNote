@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.red.note

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.*

@Composable
internal fun TopicPage(
    state: RedState,
    open: (Note) -> Unit,
    profile: (Map<String, Any?>) -> Unit,
    back: () -> Unit,
    topic: String,
) {
    val searchId = remember(topic) { UUID.randomUUID().toString().replace("-", "") }
    Column {
        PageBar("#$topic#", back, center = true)
        NoteFeed(
            state,
            "search",
            mapOf("keyword" to topic, "searchId" to searchId, "sort" to "general", "noteType" to 0),
            "pc_search",
            open,
            profile,
        )
    }
}

@Composable
internal fun SearchPage(
    state: RedState,
    open: (Note) -> Unit,
    profile: (Map<String, Any?>) -> Unit,
    onBack: () -> Unit = {},
    initial: String = "",
) {
    var input by rememberSaveable { mutableStateOf(initial) }
    var query by rememberSaveable { mutableStateOf(initial) }
    var searchId by rememberSaveable {
        mutableStateOf(UUID.randomUUID().toString().replace("-", ""))
    }
    var users by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("general") }
    var type by rememberSaveable { mutableIntStateOf(0) }
    var busySuggestions by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var history by remember { mutableStateOf(state.preferences.strings("searchHistory")) }
    var filtering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun submit(value: String = input) {
        if (value.trim().isBlank()) return
        input = value.trim()
        query = input
        users = false
        suggestions = emptyList()
        searchId = UUID.randomUUID().toString().replace("-", "")
        history = (listOf(input) + history).distinct().take(20)
        state.preferences.set("searchHistory", history)
        keyboard?.hide()
        focus.clearFocus()
    }
    LaunchedEffect(input, query) {
        if (query.isNotEmpty() || input.trim().isEmpty()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        busySuggestions = true
        try {
            suggestions =
                state
                    .call("suggestions", mapOf("keyword" to input.trim()))
                    .rows("sug_items")
                    .map {
                        it.optString(
                            "query",
                            it.optString("keyword", it.optString("text", it.optString("name"))),
                        )
                    }
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(12)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            suggestions = emptyList()
        } finally {
            busySuggestions = false
        }
    }
    LaunchedEffect(state.epoch) {
        if (state.loggedIn)
            try {
                val remote =
                    state
                        .call("searchHistory")
                        .rows("records")
                        .map {
                            it.optString("query", it.optString("keyword", it.optString("text")))
                        }
                        .filter(String::isNotEmpty)
                history = (history + remote).distinct().take(20)
                state.preferences.set("searchHistory", history)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
    }
    Column {
        Row(
            Modifier.padding(start = 4.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton("arrow_back_ios_new", "返回发现", action = onBack)
            Surface(
                Modifier.weight(1f),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.text.BasicTextField(
                        input,
                        { input = it.take(200) },
                        Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 16.dp),
                        singleLine = true,
                        textStyle =
                            appTextStyle(
                                LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        decorationBox = { inner ->
                            if (input.isEmpty())
                                Text(
                                    "搜索感兴趣的笔记或用户",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            inner()
                        },
                    )
                    if (input.isNotEmpty())
                        GlyphButton("cancel", "清除搜索词") {
                            input = ""
                            query = ""
                            users = false
                            suggestions = emptyList()
                        }
                    VerticalDivider(Modifier.height(24.dp))
                    TextButton(onClick = tapAction { submit() }) {
                        Text("搜索", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        if (query.isEmpty())
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
            ) {
                if (history.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "历史记录",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        GlyphButton("delete_outline", "清空本机搜索记录") {
                            history = emptyList()
                            state.preferences.set("searchHistory", emptyList<String>())
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        history.forEach { value ->
                            SuggestionChip(
                                tapAction { submit(value) },
                                { Text(value, maxLines = 1) },
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                if (suggestions.isNotEmpty() || busySuggestions) {
                    Text("猜你想搜", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (busySuggestions)
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    else
                        BoxWithConstraints {
                            val itemWidth = (maxWidth - 20.dp) / 2
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                suggestions.forEach { value ->
                                    TextButton(
                                        onClick = tapAction { submit(value) },
                                        modifier = Modifier.width(itemWidth),
                                        contentPadding = PaddingValues(vertical = 10.dp),
                                    ) {
                                        Text(
                                            value,
                                            Modifier.fillMaxWidth(),
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            overflow =
                                                androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                } else if (history.isEmpty())
                    Text(
                        "搜索后，记录会显示在这里",
                        Modifier.fillMaxWidth().padding(top = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        else {
            Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("全部", "用户").forEachIndexed { i, label ->
                    TextButton(
                        onClick = tapAction { users = i == 1 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            label,
                            color =
                                if (users == (i == 1)) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (!users)
                    GlyphButton("tune", "排序与类型筛选") {
                        scope.launch {
                            try {
                                state.call(
                                    "searchFilters",
                                    mapOf("keyword" to query, "searchId" to searchId),
                                )
                                filtering = true
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                state.message = e.message.orEmpty()
                            }
                        }
                    }
            }
            key(searchId, users) {
                val args =
                    mapOf(
                        "keyword" to query,
                        "searchId" to searchId,
                        "requestId" to searchId,
                        "sort" to sort,
                        "noteType" to type,
                    )
                if (users)
                    JsonList(state, "searchUsers", args, "users") { row ->
                        val u = row.optJSONObject("user_info") ?: row
                        OriginalListItem(
                            headlineContent = {
                                Text(u.optString("nickname", u.optString("name")))
                            },
                            supportingContent = {
                                RichText(
                                    u.optString("desc", u.optString("description")),
                                    maxLines = 2,
                                )
                            },
                            leadingContent = { Avatar(u, 44.dp) },
                            modifier =
                                Modifier.clickable {
                                    profile(
                                        mapOf(
                                            "id" to u.optString("user_id", u.optString("id")),
                                            "token" to u.optString("xsec_token"),
                                            "source" to "pc_search",
                                        )
                                    )
                                },
                        )
                    }
                else NoteFeed(state, "search", args, "pc_search", open, profile)
            }
        }
    }
    if (filtering)
        AppDialog(onDismissRequest = { filtering = false }) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier.widthIn(min = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 24.dp, bottom = 12.dp)
                ) {
                    Text(
                        "排序与类型",
                        Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    listOf(
                            "general" to "综合",
                            "time_descending" to "最新",
                            "popularity_descending" to "最多点赞",
                            "comment_descending" to "最多评论",
                            "collect_descending" to "最多收藏",
                            "type:0" to "全部类型",
                            "type:1" to "视频笔记",
                            "type:2" to "图文笔记",
                        )
                        .forEach { (value, label) ->
                            Box(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (value.startsWith("type:"))
                                            type = value.last().digitToInt()
                                        else sort = value
                                        filtering = false
                                        submit()
                                    }
                                    .padding(horizontal = 24.dp, vertical = 8.dp)
                            ) {
                                Text(label)
                            }
                        }
                }
            }
        }
}
