package io.github.jolewis.echoes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(
    initialQuery: String,
    episodes: List<Episode>,
    cfg: LlmConfig,
    onBack: () -> Unit,
    onPlay: (Episode) -> Unit,
    onDetail: (Episode) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var q by remember { mutableStateOf(initialQuery) }
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(SearchPhase.LOCAL_ONLY) }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var intent by remember { mutableStateOf<Intent?>(null) }
    var index by remember { mutableStateOf<SmartSearch.Index?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun runSearch(query: String) {
        if (query.isBlank()) return
        running = true; error = null; result = null; intent = null
        phase = if (cfg.ready) SearchPhase.UNDERSTAND else SearchPhase.LOCAL_ONLY
        runCatching {
            val idx = index ?: withContext(Dispatchers.Default) {
                SmartSearch.Index(episodes).also { index = it }
            }
            SmartSearch.run(
                cfg, idx, query,
                onPhase = { phase = it },
                onIntent = { intent = it },
            )
        }.onSuccess {
            result = it
            if (it == null) error = "库内没有符合条件的节目"
        }.onFailure {
            error = it.message ?: "检索失败"
        }
        running = false
    }

    LaunchedEffect(initialQuery) { if (initialQuery.isNotBlank()) runSearch(initialQuery) }

    Column(Modifier.fillMaxSize().background(Tokens.Bg).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = Tokens.OnBg) }
            Text("智能点播", color = Tokens.OnBg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        // 输入
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                placeholder = { Text("今天想听些什么？", color = Tokens.Muted, fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { scope.launch { runSearch(q) } }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { scope.launch { runSearch(q) } },
                enabled = !running && q.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tokens.Primary)
            ) { Text("听", color = Tokens.Bg, fontWeight = FontWeight.Bold) }
        }

        if (!cfg.ready) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp)).background(Tokens.Surface)
                    .clickable { onOpenSettings() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Info, null, tint = Tokens.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("未配置 AI，当前是本地关键词匹配", color = Tokens.Muted, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                Text("去配置", color = Tokens.Primary, fontSize = 11.sp)
            }
        }

        // 进度
        if (running) {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                val steps = if (cfg.ready)
                    listOf(SearchPhase.UNDERSTAND, SearchPhase.RECALL, SearchPhase.RERANK)
                else listOf(SearchPhase.LOCAL_ONLY)
                steps.forEach { s ->
                    val active = s == phase
                    val done = steps.indexOf(s) < steps.indexOf(phase)
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                                color = Tokens.Primary
                            )
                        } else {
                            Icon(
                                if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                null, tint = if (done) Tokens.Primary else Tokens.Muted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(s.label, color = if (active || done) Tokens.OnBg else Tokens.Muted, fontSize = 12.sp)
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Tokens.Accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }

        result?.let { r ->
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                item {
                    Spacer(Modifier.height(14.dp))
                    if (r.usedLlm) {
                        Text(
                            if (r.confidence >= 0.6) "已为你找到" else "比较接近的一期",
                            color = Tokens.Muted, fontSize = 11.sp
                        )
                    } else {
                        Text("本地匹配结果", color = Tokens.Muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                item {
                    CardBox(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CoverImage(r.pick, 64, 14)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.pick.title, color = Tokens.OnBg, fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text("${r.pick.minutes} 分钟 · ${r.pick.contentType}",
                                    color = Tokens.Muted, fontSize = 11.sp)
                            }
                        }
                        if (r.reason.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text("「${r.reason}」", color = Tokens.Accent, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        if (r.note.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(r.note, color = Tokens.Accent, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onPlay(r.pick) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Tokens.Primary)
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, tint = Tokens.Bg)
                                Spacer(Modifier.width(4.dp))
                                Text("播放", color = Tokens.Bg, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onDetail(r.pick) },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("详情", color = Tokens.Primary, fontSize = 13.sp) }
                        }
                    }
                }

                // 理解结果
                intent?.let { it0 ->
                    val chips = buildList {
                        addAll(it0.topics)
                        addAll(it0.domain)
                        if (it0.contentType.isNotBlank()) add(it0.contentType)
                        addAll(it0.tone)
                        if (it0.humorMin > 0) add("有趣≥${it0.humorMin}")
                        if (it0.loadMax > 0) add("动脑≤${it0.loadMax}")
                        if (it0.durationMin > 0 || it0.durationMax > 0) {
                            add("${it0.durationMin / 60}-${it0.durationMax / 60} 分钟")
                        }
                    }
                    if (chips.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(14.dp))
                            Text("理解成了", color = Tokens.Muted, fontSize = 11.sp)
                            Spacer(Modifier.height(6.dp))
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(chips.size) { i ->
                                    AssistChip(onClick = {}, label = { Text(chips[i], fontSize = 10.sp) })
                                }
                            }
                        }
                    }
                }

                if (r.queue.isNotEmpty()) {
                    item { SectionTitle("接下来可以听") }
                    items(r.queue, key = { "q" + it.id }) { ep ->
                        EpisodeRow(ep) { onDetail(ep) }
                    }
                }

                item { SectionTitle("其它候选") }
                items(r.candidates.drop(1).take(8), key = { "c" + it.first.id }) { (ep, score) ->
                    EpisodeRow(ep) { onDetail(ep) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ------------------------------------------------------------------ AI 设置

@Composable
fun AiSettingsPage(
    cfg: LlmConfig,
    onSave: (LlmConfig) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var base by remember { mutableStateOf(cfg.baseUrl) }
    var key by remember { mutableStateOf(cfg.apiKey) }
    var model by remember { mutableStateOf(cfg.model) }
    var testState by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var savedAt by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    fun dismissInput() {
        kb?.hide()
        focus.clearFocus()
    }

    SubPage("AI 设置", onBack) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()                 // 键盘弹起时把按钮顶上来
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "密钥只保存在本机，不会上传也不会进代码仓库。" +
                    "任何 OpenAI 兼容接口都能用（DeepSeek、本地 Ollama、自建网关）。",
                color = Tokens.Muted, fontSize = 12.sp, lineHeight = 19.sp
            )
            Spacer(Modifier.height(18.dp))

            Field("接口地址", base, "https://api.deepseek.com") { base = it }
            Spacer(Modifier.height(12.dp))
            Field("API Key", key, "sk-…", password = true) { key = it }
            Spacer(Modifier.height(12.dp))
            Field("模型", model, "deepseek-chat") { model = it }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        dismissInput()
                        val c = LlmConfig(base, key, model)
                        onSave(c)
                        testState = null
                        savedAt = System.currentTimeMillis()
                        android.widget.Toast.makeText(ctx, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tokens.Primary)
                ) { Text("保存", color = Tokens.Bg, fontWeight = FontWeight.Bold) }

                OutlinedButton(
                    onClick = {
                        dismissInput()
                        testing = true
                        testState = null
                        val c = LlmConfig(base, key, model)
                        scope.launch {
                            testState = runCatching {
                                Llm.chat(c, "只输出 JSON。", "{\"ok\":true}", maxTokens = 20)
                                "连接正常"
                            }.getOrElse { "失败：${it.message?.take(90)}" }
                            testing = false
                            android.widget.Toast.makeText(ctx, testState, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !testing && key.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (testing) "测试中…" else "测试连接", color = Tokens.Primary, fontSize = 13.sp) }
            }

            // 状态放在按钮下方：按钮位置固定，不会因为提示出现而移位
            Spacer(Modifier.height(14.dp))
            val statusText = when {
                testing -> "正在连接…"
                testState != null -> testState
                savedAt > 0 -> "已保存"
                else -> null
            }
            if (statusText != null) {
                val ok = !statusText.startsWith("失败") && !statusText.startsWith("正在")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Tokens.Surface).padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when {
                            testing -> Icons.Filled.Sync
                            ok -> Icons.Filled.CheckCircle
                            else -> Icons.Filled.ErrorOutline
                        },
                        null,
                        tint = when {
                            testing -> Tokens.Muted
                            ok -> Tokens.Primary
                            else -> Tokens.Accent
                        },
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        statusText,
                        color = if (testing) Tokens.Muted else if (ok) Tokens.Primary else Tokens.Accent,
                        fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "提示：模型要支持 JSON 输出（response_format=json_object）。" +
                    "如果选的是推理模型，单次解析会慢几秒。",
                color = Tokens.Muted, fontSize = 11.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column {
        Text(label, color = Tokens.Muted, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(hint, color = Tokens.Muted, fontSize = 12.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password)
            else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
