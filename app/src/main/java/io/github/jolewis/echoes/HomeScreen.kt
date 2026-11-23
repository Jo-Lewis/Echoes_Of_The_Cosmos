package io.github.jolewis.echoes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    episodes: List<Episode>,
    recs: List<ListenRec>,
    current: Episode?,
    onPlay: (Episode) -> Unit,
    onDetail: (Episode) -> Unit,
    onOpenPlayer: () -> Unit,
    onGoLibrary: (String) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val suggestions = listOf("天文故事", "轻松有趣", "半小时以内", "睡前听")

    val listenedIds = remember(recs) { recs.map { it.id }.toSet() }
    val recent = remember(recs, episodes) {
        ListenLog.recentEpisodes(recs, 3).mapNotNull { id -> episodes.firstOrNull { it.id == id } }
    }
    // 「为你发现」：优先挑没听过的、时长适中、标签有区分度的
    val discover = remember(episodes, listenedIds) {
        episodes.asSequence()
            .filter { it.id !in listenedIds }
            .filter { it.durationSec in 480..1800 }
            .filter { it.images.isNotEmpty() || it.oneLine.isNotBlank() }
            .sortedByDescending { it.humor * 2 + it.year }
            .take(3).toList()
    }

    fun run(kind: String) {
        val key = if (kind == "auto") q.trim() else kind
        if (key.isBlank()) return
        val hits = Library.search(episodes, key)
        if (hits.size == 1) onPlay(hits.first()) else onGoLibrary(key)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("星河回响", color = Tokens.OnBg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("${episodes.size} 期 · 已听 ${fmtShort(ListenLog.totalSec(recs))}",
                color = Tokens.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }

        // 主输入卡
        item {
            CardBox(Modifier.fillMaxWidth()) {
                Text("今天想听些什么？", color = Tokens.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    placeholder = { Text("今天想听火星 / 想听轻松有趣的", color = Tokens.Muted, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run("auto") }),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { run("auto") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Primary)
                    ) { Text("开始听", color = Tokens.Bg, fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { if (q.isNotBlank()) onGoLibrary(q.trim()) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("只查看结果", color = Tokens.Primary, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(suggestions.size) { i ->
                        val s = suggestions[i]
                        AssistChip(
                            onClick = { q = s },
                            label = { Text(s, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("目前为本地关键词匹配，AI 语义理解尚未接入",
                    color = Tokens.Muted, fontSize = 10.sp)
            }
        }

        // 继续收听
        if (current != null) {
            item {
                SectionTitle("继续收听")
                CardBox(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(current, 64, 14)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(current.title, color = Tokens.OnBg, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Text("${current.minutes} 分钟 · ${current.contentType.ifBlank { "未标注" }}",
                                color = Tokens.Muted, fontSize = 11.sp)
                        }
                        IconButton(onClick = onOpenPlayer) {
                            Icon(Icons.Filled.PlayCircle, null, tint = Tokens.Primary,
                                modifier = Modifier.size(36.dp))
                        }
                    }
                }
            }
        }

        // 为你发现
        if (discover.isNotEmpty()) {
            item { SectionTitle("为你发现") }
            items(discover, key = { "d" + it.id }) { ep ->
                CardBox(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { onDetail(ep) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(ep, 56, 12)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ep.title, color = Tokens.OnBg, fontSize = 14.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                buildString {
                                    append("${ep.minutes} 分钟")
                                    ep.topics.firstOrNull()?.let { append(" · $it") }
                                    if (ep.tone.isNotBlank()) append(" · ${ep.tone}")
                                },
                                color = Tokens.Muted, fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onPlay(ep) }) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Tokens.Primary)
                        }
                    }
                }
            }
        }

        // 最近在听
        if (recent.isNotEmpty()) {
            item { SectionTitle("最近在听") }
            items(recent, key = { "r" + it.id }) { ep ->
                EpisodeRow(ep) { onDetail(ep) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
