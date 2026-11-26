package io.github.jolewis.echoes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LibraryScreen(
    episodes: List<Episode>,
    recs: List<ListenRec>,
    query: String,
    onPlay: (Episode) -> Unit,
    onDetail: (Episode) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenCollections: () -> Unit = {},
) {
    var q by remember(query) { mutableStateOf(query) }
    var scope by remember { mutableStateOf("全部") }
    var sort by remember { mutableStateOf("最新") }
    var year by remember { mutableStateOf("全部") }
    var type by remember { mutableStateOf("全部") }

    val listened = remember(recs) { recs.map { it.id }.toSet() }
    val years = remember(episodes) {
        listOf("全部") + episodes.mapNotNull { it.date.take(4).toIntOrNull() }
            .distinct().sortedDescending().map { it.toString() }
    }
    val types = remember(episodes) {
        listOf("全部") + episodes.mapNotNull { it.contentType.takeIf { t -> t.isNotBlank() } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    val shown = remember(q, scope, sort, year, type, episodes, listened) {
        val tf = android.os.SystemClock.elapsedRealtime()
        var list = if (q.isBlank()) episodes else Library.search(episodes, q)
        when (scope) {
            "未听" -> list = list.filter { it.id !in listened }
            "已听" -> list = list.filter { it.id in listened }
            "专题" -> list = list.filter { it.series != null }
        }
        if (year != "全部") list = list.filter { it.date.startsWith(year) }
        if (type != "全部") list = list.filter { it.contentType == type }
        // 搜索时默认保持相关度顺序；只有用户明确选了「最长/最短」才重排
        when {
            sort == "最长" -> list = list.sortedByDescending { it.durationSec }
            sort == "最短" -> list = list.sortedBy { it.durationSec }
            q.isNotBlank() -> Unit
            else -> list = list.sortedByDescending { it.date }
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "echoes",
                "perf libraryCompose filter=" + (android.os.SystemClock.elapsedRealtime() - tf) +
                    "ms q=" + q + " shown=" + list.size
            )
        }
        list
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("节目库", color = Tokens.OnBg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            val seriesCount = remember(episodes) {
                episodes.filter { it.inCollection }.mapNotNull { it.collection }.distinct().size
            }
            if (seriesCount > 0) {
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Tokens.Surface)
                        .clickable { onOpenCollections() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("系列合集 $seriesCount", color = Tokens.Primary, fontSize = 11.sp)
                    Icon(Icons.Filled.KeyboardArrowRight, null, tint = Tokens.Primary,
                        modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = q,
            onValueChange = { q = it; onQueryChange(it) },
            placeholder = { Text("搜标题、主题、关键词", color = Tokens.Muted, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Tokens.Muted) },
            trailingIcon = {
                if (q.isNotEmpty()) IconButton(onClick = { q = ""; onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, null, tint = Tokens.Muted)
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        ChipRow(listOf("全部", "专题", "未听", "已听"), scope) { scope = it }
        Spacer(Modifier.height(4.dp))
        ChipRow(listOf("最新", "最长", "最短"), sort) { sort = it }
        Spacer(Modifier.height(4.dp))
        ChipRow(years, year) { year = it }
        Spacer(Modifier.height(4.dp))
        ChipRow(types, type) { type = it }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (q.isNotBlank() && sort == "最新") "${shown.size} 条 · 按相关度排序"
                else "${shown.size} 条",
                color = Tokens.Muted, fontSize = 11.sp
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown.take(500), key = { it.dir.absolutePath }) { ep ->
                EpisodeRow(ep) { onDetail(ep) }
            }
        }
    }
}
