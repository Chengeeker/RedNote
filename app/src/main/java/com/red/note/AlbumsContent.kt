package com.red.note

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun AlbumsContent(
    state: RedState,
    album: JSONObject?,
    openNote: (Note) -> Unit,
    open: (JSONObject) -> Unit,
    back: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var metadata by remember { mutableStateOf(album) }
    var loading by remember { mutableStateOf(false) }
    val pageScope = rememberCoroutineScope()
    fun edit() {
        if (loading) return
        if (album == null) {
            metadata = null
            editing = true
            return
        }
        loading = true
        pageScope.launch {
            try {
                val data = state.call("boardInfo", mapOf("id" to album.optString("id")))
                require(data.optString("id") == album.optString("id")) { "专辑信息不匹配" }
                metadata = data
                editing = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.message = e.message.orEmpty()
            } finally {
                loading = false
            }
        }
    }
    Column {
        PageBar(
            album?.optString("name") ?: "收藏专辑",
            back,
            actions = {
                GlyphButton(
                    if (album == null) "create_new_folder_outlined" else "edit_outlined",
                    if (album == null) "新建专辑" else "编辑专辑",
                    !loading,
                ) {
                    edit()
                }
            },
        )
        if (album == null)
            key(refresh) {
                JsonList(state, "boards", emptyMap(), "boards") { row ->
                    OriginalListItem(
                        headlineContent = { Text(row.optString("name")) },
                        supportingContent = {
                            Text(
                                "${row.optInt("total")} 篇笔记 · ${if(row.optInt("privacy")==0)"公开" else "私密"}"
                            )
                        },
                        leadingContent = { Glyph("folder_outlined") },
                        trailingContent = { Glyph("chevron_right") },
                        modifier = Modifier.clickable { open(row) },
                    )
                }
            }
        else
            NoteFeed(state, "boardNotes", mapOf("id" to album.optString("id")), "pc_user", openNote)
    }
    if (editing) {
        val album = metadata
        var name by remember { mutableStateOf(album?.optString("name").orEmpty()) }
        var desc by remember { mutableStateOf(album?.optString("desc").orEmpty()) }
        var private by remember { mutableStateOf(album != null && album.optInt("privacy") != 0) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var deleting by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        fun save(delete: Boolean) {
            if (busy) return
            if (
                !delete &&
                    (name.trim().isEmpty() ||
                        characterCount(name) > 12 ||
                        characterCount(desc) > 1000)
            ) {
                error = "名称需为1至12字，简介最多1000字"
                return
            }
            busy = true
            scope.launch {
                try {
                    state.call(
                        if (delete) "deleteBoard" else "saveBoard",
                        mapOf(
                            "id" to album?.optString("id").orEmpty(),
                            "name" to name.trim(),
                            "desc" to desc,
                            "private" to private,
                        ),
                    )
                    state.jsonLists.remove("boards:{}")
                    editing = false
                    if (album == null) refresh++ else back()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = e.message.orEmpty()
                } finally {
                    busy = false
                }
            }
        }
        AppAlertDialog(
            onDismissRequest = { if (!busy) editing = false },
            properties =
                androidx.compose.ui.window.DialogProperties(
                    dismissOnClickOutside = false,
                    dismissOnBackPress = !busy,
                ),
            title = { Text(if (album == null) "新建专辑" else "编辑专辑") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextField(
                        name,
                        { name = takeCharacters(it, 12) },
                        enabled = !busy,
                        textStyle = appTextStyle(MaterialTheme.typography.bodyLarge),
                        label = { Text("名称") },
                        supportingText = { Text("${characterCount(name)}/12") },
                    )
                    TextField(
                        desc,
                        { desc = takeCharacters(it, 1000) },
                        enabled = !busy,
                        textStyle = appTextStyle(MaterialTheme.typography.bodyLarge),
                        label = { Text("简介") },
                        minLines = 3,
                        maxLines = 3,
                        supportingText = { Text("${characterCount(desc)}/1000") },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("私密专辑", Modifier.weight(1f))
                        val changePrivacy = tapAction { private = !private }
                        Switch(private, { changePrivacy() }, enabled = !busy)
                    }
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (busy) CircularProgressIndicator()
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = tapAction { save(false) }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (album != null)
                        TextButton(enabled = !busy, onClick = tapAction { deleting = true }) {
                            Text("删除专辑")
                        }
                    TextButton(enabled = !busy, onClick = tapAction { editing = false }) {
                        Text("取消")
                    }
                }
            },
        )
        if (deleting)
            AppAlertDialog(
                onDismissRequest = { deleting = false },
                title = { Text("删除专辑？") },
                text = { Text("专辑删除后无法恢复，里面的笔记仍会保留在全部收藏中。") },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        onClick =
                            tapAction {
                                deleting = false
                                save(true)
                            },
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = tapAction { deleting = false }) { Text("取消") }
                },
            )
    }
}
