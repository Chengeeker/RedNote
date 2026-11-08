@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    coil.annotation.ExperimentalCoilApi::class,
)

package com.red.note

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import coil.imageLoader
import kotlin.math.roundToInt
import kotlinx.coroutines.*

@Composable
private fun Section(
    title: String = "",
    outside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        if (outside && title.isNotEmpty()) {
            Text(
                title,
                Modifier.padding(horizontal = 16.dp),
                style =
                    MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 21.sp),
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        Surface(
            Modifier.fillMaxWidth().testTag("settings-card:$title"),
            shape = RoundedCornerShape(24.dp),
            color =
                if (scheme.surface == Color.Black) scheme.surfaceContainer
                else scheme.surfaceContainerLow,
        ) {
            Column {
                if (!outside && title.isNotEmpty())
                    Text(
                        title,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurfaceVariant,
                    )
                content()
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    icon: String,
    subtitle: String? = null,
    trailingLabel: String? = null,
    action: () -> Unit,
) {
    OriginalListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent =
            subtitle?.let {
                {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        leadingContent = { Glyph(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailingLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Glyph("chevron_right_rounded", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clickable(onClick = action),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun SettingsContent(
    state: RedState,
    activity: MainActivity,
    section: String,
    open: (String) -> Unit,
) {
    val p = state.preferences
    var dialog by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var cacheFailure by remember { mutableStateOf("") }
    var pendingThresholdCheck by remember { mutableIntStateOf(-1) }
    fun path(type: Int, video: Boolean): String =
        (if (video) "Movies" else "Pictures") +
            "/RedNote/" +
            when (type) {
                1 ->
                    state.session.optJSONObject("user")?.optString("nickname").orEmpty().ifEmpty {
                        "我的昵称"
                    } + "/"
                2 -> "笔记作者/"
                else -> ""
            }
    fun cache(clear: Boolean, thresholdCheck: Int? = null) {
        if (busy) {
            if (thresholdCheck != null) pendingThresholdCheck = thresholdCheck
            return
        }
        busy = true
        cacheFailure = ""
        scope.launch {
            try {
                if (clear) ImageCacheMaintenance.clear(context, context.imageLoader)
                if (thresholdCheck != null)
                    ImageCacheMaintenance.clearIfAboveThreshold(
                        context,
                        context.imageLoader,
                        thresholdCheck,
                    )
                cacheSize = ImageCacheMaintenance.diskUsage(context, context.imageLoader)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                cacheFailure = e.message.orEmpty()
                state.message = cacheFailure
            } finally {
                busy = false
                if (pendingThresholdCheck >= 0) {
                    val threshold = pendingThresholdCheck
                    pendingThresholdCheck = -1
                    cache(false, threshold)
                }
            }
        }
    }
    fun selectCacheThreshold(value: Int) {
        p.set(ImageCacheMaintenance.thresholdPreference, value)
        dialog = ""
        state.message =
            if (value == 0) "已关闭自动清理"
            else "已设置自动清理阈值：${ImageCacheMaintenance.labelForThreshold(value)}"
        cache(false, value)
    }
    LaunchedEffect(section) {
        if (section == "缓存管理") cache(false)
        if (section == "账号与连接")
            runCatching { state.refreshSession() }
                .onFailure { state.message = it.message.orEmpty() }
    }
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = if (section == "设置") 20.dp else 12.dp)
            .navigationBarsPadding()
    ) {
        when (section) {
            "设置" -> {
                Section("偏好与功能", true) { SettingRow("个性化", "palette_outlined") { open("个性化") } }
                Spacer(Modifier.height(32.dp))
                Section("存储与备份", true) {
                    SettingRow("存储设置", "folder_open_outlined") { open("存储设置") }
                    SettingRow("缓存管理", "cleaning_services_outlined") { open("缓存管理") }
                }
                Spacer(Modifier.height(32.dp))
                Section("账号设置", true) {
                    SettingRow("账号与连接", "manage_accounts_outlined") { open("账号与连接") }
                }
                Spacer(Modifier.height(32.dp))
                Section("关于与支持", true) {
                    SettingRow(
                        "关于 RedNote",
                        "info_outline_rounded",
                        "版本 ${BuildConfig.VERSION_NAME}",
                    ) {
                        dialog = "about"
                    }
                }
            }
            "个性化" -> PersonalizationContent(state)

            "存储设置" ->
                Section {
                    listOf(false, true).forEach { video ->
                        val key = if (video) "videoPath" else "imagePath"
                        SettingRow(
                            if (video) "视频存储路径" else "图片存储路径",
                            if (video) "videocam_outlined" else "image_outlined",
                            path(p.int(key).coerceIn(0, 2), video),
                        ) {
                            dialog = key
                        }
                    }
                }
            "缓存管理" -> {
                Section {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(56.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Glyph(
                                "cleaning_services_rounded",
                                null,
                                30.dp,
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        if (busy)
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        else
                            Text(
                                if (cacheFailure.isEmpty())
                                    "${"%.1f".format(cacheSize/1048576.0)} MB"
                                else cacheFailure,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        Spacer(Modifier.height(4.dp))
                        Text("可管理的媒体磁盘缓存", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Section {
                    Button(
                        enabled = !busy && cacheFailure.isEmpty(),
                        onClick = tapAction { dialog = "cache" },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
                    ) {
                        Glyph("delete_sweep_rounded")
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "正在读取..." else "清理媒体缓存")
                    }
                }
                Spacer(Modifier.height(24.dp))
                Section("自动清理规则") {
                    val threshold = ImageCacheMaintenance.threshold(p)
                    SettingRow(
                        "自动清理缓存阈值",
                        "cleaning_services_outlined",
                        "启动时达到阈值自动清理；更改后立即检查一次",
                        ImageCacheMaintenance.labelForThreshold(threshold),
                    ) {
                        dialog = "cacheThreshold"
                    }
                }
                Spacer(Modifier.height(24.dp))
                Section {
                    Text(
                        "仅清理应用可重新下载的图片缓存。最近一分钟内写入的原图会保留；已保存到相册的媒体、账号资料及外观设置不会被删除。",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                TextButton(
                    enabled = !busy,
                    onClick = tapAction { cache(false) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Glyph("refresh_rounded")
                    Text("刷新缓存占用")
                }
            }
            "账号与连接" -> {
                Section("登录与会话", true) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (state.loggedIn) "已保存登录状态" else "尚未登录",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("请在官方网页完成扫码或验证码。登录校验通过后才保存新会话。")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = tapAction { activity.openLogin() }) {
                            Glyph("login")
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.loggedIn) "重新网页登录" else "网页登录")
                        }
                    }
                }
                if (state.loggedIn) {
                    Spacer(Modifier.height(16.dp))
                    Section {
                        OriginalListItem(
                            headlineContent = { Text("账号 ID") },
                            supportingContent = {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(state.userId)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        OriginalListItem(
                            headlineContent = { Text("站点") },
                            supportingContent = {
                                Text(
                                    if (state.session.optString("region") == "rednote") "Rednote"
                                    else "小红书"
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
                state.session.optJSONObject("lastRequest")?.let { last ->
                    if (last.length() > 0) {
                        Spacer(Modifier.height(16.dp))
                        Section {
                            Column(Modifier.padding(16.dp)) {
                                Text("最近一次请求", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(last.optString("path"))
                                Text(
                                    "HTTP ${last.optString("http")} · ${last.optString("elapsedMs")} ms"
                                )
                                if (
                                    state.session.has("blocked") && !state.session.isNull("blocked")
                                )
                                    Text("请求已暂停，请重新网页登录并完成验证。")
                            }
                        }
                    }
                }
                if (state.loggedIn) {
                    Spacer(Modifier.height(24.dp))
                    Section {
                        OriginalListItem(
                            headlineContent = {
                                Text(
                                    "退出登录",
                                    color = Color(0xFFFF5252),
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            leadingContent = { Glyph("logout_rounded", tint = Color(0xFFFF5252)) },
                            modifier = Modifier.clickable { dialog = "logout" },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
    when (dialog) {
        "imagePath",
        "videoPath" ->
            ModalBottomSheet(onDismissRequest = { dialog = "" }) {
                val video = dialog == "videoPath"
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (video) "视频存储路径" else "图片存储路径",
                            Modifier.weight(1f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        GlyphButton("close_rounded", "关闭") { dialog = "" }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "保存${if(video)"视频"else "图片"}时，文件将自动归类并存储至相册对应文件夹下。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    listOf("默认路径", "按本人昵称前缀归类", "按博主昵称前缀归类").forEachIndexed { i, label ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                p.set(dialog, i)
                                dialog = ""
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                p.int(dialog) == i,
                                {
                                    p.set(dialog, i)
                                    dialog = ""
                                },
                            )
                            Column(Modifier.padding(vertical = 10.dp)) {
                                Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(path(i, video), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        "cacheThreshold" -> {
            val selected = ImageCacheMaintenance.threshold(p)
            AppAlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("自动清理阈值") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        ImageCacheMaintenance.thresholdOptions.forEach { value ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(onClick = tapAction { selectCacheThreshold(value) }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected == value,
                                    onClick = tapAction { selectCacheThreshold(value) },
                                )
                                Text(
                                    ImageCacheMaintenance.labelForThreshold(value),
                                    Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = tapAction { dialog = "" }) { Text("取消") } },
            )
        }
        "cache",
        "logout" ->
            AppAlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text(if (dialog == "cache") "清理媒体缓存？" else "退出账号？") },
                text = {
                    Text(
                        if (dialog == "cache") "只清理可重新下载的图片缓存。最近一分钟内写入的原图会保留；已保存到相册的媒体、账号与设置不受影响。"
                        else "将清除本应用保存的登录状态，外观设置保留。"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick =
                            tapAction {
                                if (dialog == "cache") cache(true) else activity.logout()
                                dialog = ""
                            }
                    ) {
                        Text("确认")
                    }
                },
                dismissButton = { TextButton(onClick = tapAction { dialog = "" }) { Text("取消") } },
            )
        "about" -> AboutDialog(state, { dialog = "" })
    }
}

@Composable
internal fun PersonalizationContent(state: RedState) {
    val p = state.preferences
    val translucent = p.bool("translucentNavigationGlass", false)
    Column {
        Section("明暗模式") {
            listOf("跟随系统", "浅色模式", "深色模式").forEachIndexed { i, label ->
                OriginalListItem(
                    headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
                    leadingContent = { RadioButton(p.int("theme") == i, null) },
                    modifier = Modifier.clickable { p.set("theme", i) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            SettingToggle(state, "纯黑深色模式", "pureBlack", "contrast_rounded")
        }
        Spacer(Modifier.height(16.dp))
        Section("色彩方案") {
            val dynamicAvailable = android.os.Build.VERSION.SDK_INT >= 31
            SettingToggle(
                state,
                "动态取色",
                "dynamicColor",
                "auto_awesome_outlined",
                enabled = dynamicAvailable,
            )
            if (!dynamicAvailable || !p.bool("dynamicColor")) {
                Text(
                    "Material 3 预置主题色",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf("经典红", "活力橙", "极光蓝", "翡翠绿", "优雅紫", "樱花粉", "青碧色", "经典黑灰")
                        .forEachIndexed { i, label ->
                            FilterChip(
                                p.int("color") == i,
                                tapAction { p.set("color", i) },
                                label = { Text(label) },
                                leadingIcon = {
                                    Box(
                                        Modifier.size(20.dp)
                                            .background(
                                                Color(
                                                    listOf(
                                                        0xFFFA2F3A,
                                                        0xFFFF8200,
                                                        0xFF1976D2,
                                                        0xFF2E7D32,
                                                        0xFF7B1FA2,
                                                        0xFFE91E63,
                                                        0xFF00897B,
                                                        0xFF455A64,
                                                    )[i]
                                                ),
                                                CircleShape,
                                            )
                                    )
                                },
                            )
                        }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Section("字体粗细") {
            val customWeight = p.bool("customWeight")
            val committedDelta = if (customWeight) p.int("weightDelta").coerceIn(-300, 300)
                else state.session.optInt("systemFontWeight")
            var weight by remember(p.revision, committedDelta) {
                mutableFloatStateOf(committedDelta.toFloat().coerceIn(-300f, 300f))
            }
            val weightLabel = if (!customWeight && weight == committedDelta.toFloat()) "跟随系统"
                else listOf("极细", "细", "偏细", "默认", "中等", "偏粗", "加粗")[
                    ((weight + 300) / 100).roundToInt().coerceIn(0, 6)
                ]
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("format_bold_rounded", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(weightLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(
                            onClick = tapAction { p.set("customWeight", false) },
                            enabled = customWeight,
                        ) { Text("重置") }
                    }
                    Slider(
                        value = weight,
                        onValueChange = { weight = it },
                        modifier = Modifier.semantics { contentDescription = "字体粗细" },
                        valueRange = -300f..300f,
                        steps = 5,
                        onValueChangeFinished = {
                            p.set("weightDelta", weight.roundToInt())
                            p.set("customWeight", true)
                        },
                    )
                }
            }
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .3f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp)
            ) {
                Text(
                    "效果预览 ($weightLabel)",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "RedNote 极简现代体验：这是一段用于预览字体粗细的示例文本。@开发者 #话题#",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 19.6.sp,
                        fontWeight = FontWeight(
                            ((MaterialTheme.typography.bodyMedium.fontWeight?.weight ?: 400) -
                                committedDelta + weight.roundToInt()).coerceIn(100, 900)
                        ),
                    ),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Section("字体大小") {
            var scale by
                remember(p.revision) { mutableFloatStateOf(p.number("fontScale").toFloat()) }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("format_size_rounded", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("${(scale*100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        scale,
                        { scale = it },
                        modifier = Modifier.semantics { contentDescription = "字体大小" },
                        valueRange = .85f..1.5f,
                        steps = 12,
                        onValueChangeFinished = { p.set("fontScale", scale.toDouble()) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Section("底部导航") { SettingToggle(state, "使用悬浮底栏", "floating", "dock_rounded", true) }
        Spacer(Modifier.height(16.dp))
        Section("显示效果") {
            SettingToggle(
                state,
                "使用液态玻璃效果",
                "liquidGlass",
                "blur_on_rounded",
                false,
                "用于悬浮底栏和图库操作按钮",
            )
            if (p.bool("liquidGlass") && p.bool("floating", true)) {
                NavigationGlassChoice(translucent, fontScale = appTextSize(14.sp).value / 14f,
                    hapticsEnabled = p.bool("haptics", true)) { p.set("translucentNavigationGlass", it) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Section("触感与震动") { SettingToggle(state, "触感与震动反馈", "haptics", "vibration_rounded", true) }
    }

}

@Composable
private fun SettingToggle(
    state: RedState,
    title: String,
    key: String,
    icon: String,
    fallback: Boolean = false,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val p = state.preferences
    val toggle = tapAction { p.set(key, !p.bool(key, fallback)) }
    OriginalListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent =
            summary?.let {
                {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        leadingContent = { Glyph(icon, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Switch(enabled && p.bool(key, fallback), { toggle() }, enabled = enabled)
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = toggle),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun AboutDialog(state: RedState, close: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    fun project() {
        runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Chengeeker/RedNote"))
                )
            }
            .onFailure { state.message = "没有可用浏览器" }
    }
    AppDialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh) {
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.rednote_launcher),
                    "RedNote",
                    Modifier.size(72.dp)
                        .then(androidx.compose.ui.Modifier.clip(RoundedCornerShape(18.dp))),
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "RedNote",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = .2.sp,
                    )
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        Modifier.background(scheme.primaryContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.5.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "个人使用的第三方客户端，非官方应用",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(30.dp))
                listOf(
                        "palette_outlined" to "Material You 动态配色与个性化字体",
                        "photo_library_outlined" to "笔记浏览、高清图库与视频播放",
                        "lock_outline_rounded" to "官方网页登录，不接收密码；登录过期或验证码请在网页处理，网页与接口变化可能影响功能。",
                    )
                    .forEachIndexed { i, (icon, label) ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        Row {
                            Glyph(icon, null, 16.dp, scheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                Modifier.weight(1f),
                                fontSize = 12.5.sp,
                                lineHeight = 16.875.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                Spacer(Modifier.height(26.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            scheme.surfaceContainerHighest.copy(alpha = .5f),
                            RoundedCornerShape(12.dp),
                        )
                        .border(
                            1.dp,
                            scheme.outlineVariant.copy(alpha = .4f),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { project() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "\uf5dd",
                        scaleFont = false,
                        fontFamily =
                            androidx.compose.ui.text.font.FontFamily(
                                androidx.compose.ui.text.font.Font(R.font.cupertino_icons)
                            ),
                        fontSize =
                            with(androidx.compose.ui.platform.LocalDensity.current) {
                                20.dp.toSp()
                            },
                        color = scheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("GitHub 项目地址", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "github.com/Chengeeker/RedNote",
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Glyph("open_in_new_rounded", null, 16.dp, scheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = tapAction(close),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("我知道了")
                }
            }
        }
    }
}
