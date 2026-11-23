package com.p2049.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.json.JSONObject

/** 节目详情（剧集档案）。 */
@Composable
fun EpisodeDetailScreen(
    ep: Episode,
    all: List<Episode>,
    progressSec: Long,
    favorite: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpen: (Episode) -> Unit,
) {
    val paragraphs = remember(ep.id) { previewParagraphs(ep, 3) }
    val related = remember(ep.id, all) { relatedTo(ep, all, 4) }

    LazyColumn(Modifier.fillMaxSize().background(Tokens.Bg)) {
        // 头图
        item {
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                val hero = ep.images.firstOrNull()?.file ?: ep.cover
                if (hero != null) {
                    AsyncImage(
                        model = hero, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Tokens.Bg.copy(alpha = 0.75f), Tokens.Bg)
                        )
                    )
                )
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .statusBarsPadding().padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, null, tint = Tokens.OnBg)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        null,
                        tint = if (favorite) Tokens.Accent else Tokens.OnBg
                    )
                }
                }
            }
        }

        // 标题区
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(ep.title, color = Tokens.OnBg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        if (ep.date.isNotBlank()) append("${ep.date} · ")
                        append("${ep.minutes} 分钟")
                        ep.series?.let { append(" · $it") }
                    },
                    color = Tokens.Muted, fontSize = 12.sp
                )
                if (ep.oneLine.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(ep.oneLine, color = Tokens.OnBg, fontSize = 14.sp, lineHeight = 21.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Primary)
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Tokens.Bg)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (progressSec > 5) "继续播放 · ${fmtShort(progressSec)}" else "播放本期",
                            color = Tokens.Bg, fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Completeness(ep)
            }
        }

        // 标签
        item {
            val chips = buildList {
                ep.contentType.takeIf { it.isNotBlank() }?.let { add(it) }
                ep.tone.takeIf { it.isNotBlank() }?.let { add(it) }
                add("难度 ${ep.level}")
                addAll(ep.topics.take(4))
            }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chips.size) { i ->
                        AssistChip(onClick = {}, label = { Text(chips[i], fontSize = 11.sp) })
                    }
                }
            }
        }

        // 摘要
        if (ep.summary.isNotBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SectionTitle("讲了什么")
                    Text(ep.summary, color = Tokens.OnBg, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
        }

        // 配图
        if (ep.images.isNotEmpty()) {
            item {
                SectionTitle(
                    "配图 ${ep.images.size} 张",
                    mod = Modifier.padding(horizontal = 20.dp)
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ep.images.size) { i ->
                        Column(Modifier.width(150.dp)) {
                            AsyncImage(
                                model = ep.images[i].file, contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(150.dp).height(100.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            if (ep.images[i].caption.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    ep.images[i].caption, color = Tokens.Muted, fontSize = 10.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 文稿预览
        if (paragraphs.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SectionTitle("文稿预览")
                    paragraphs.forEach { p ->
                        Text(p, color = Tokens.Muted, fontSize = 13.sp, lineHeight = 21.sp,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }

        // 相关
        if (related.isNotEmpty()) {
            item { SectionTitle("相关节目", mod = Modifier.padding(horizontal = 20.dp)) }
            items(related, key = { "rel" + it.id }) { r ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    EpisodeRow(r) { onOpen(r) }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun Completeness(ep: Episode) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniBadge("音频", true)
        MiniBadge("文稿", ep.blocks != null)
        MiniBadge("配图 ${ep.images.size}", ep.images.isNotEmpty())
        MiniBadge("时间同步", ep.blocks != null)
    }
}

@Composable
private fun MiniBadge(label: String, ok: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (ok) Tokens.Elevated else Tokens.Surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (ok) Icons.Filled.Check else Icons.Filled.Remove,
            null,
            tint = if (ok) Tokens.Primary else Tokens.Muted,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = if (ok) Tokens.OnBg else Tokens.Muted, fontSize = 10.sp)
    }
}

private fun previewParagraphs(ep: Episode, n: Int): List<String> = runCatching {
    val root = JSONObject(ep.blocks!!.readText())
    val arr = root.getJSONArray("blocks")
    val out = ArrayList<String>()
    for (i in 0 until arr.length()) {
        if (out.size >= n) break
        val o = arr.getJSONObject(i)
        val t = o.optString("type")
        if (t == "p" || t == "h2") {
            val s = o.optString("text").trim()
            if (s.isNotEmpty()) out.add(if (s.length > 120) s.take(120) + "…" else s)
        }
    }
    out
}.getOrDefault(emptyList())

/** 相关：同系列优先，其次共同主题词多的。 */
private fun relatedTo(ep: Episode, all: List<Episode>, n: Int): List<Episode> {
    val series = ep.series
    val topics = ep.topics.toSet()
    return all.asSequence()
        .filter { it.id != ep.id }
        .map { it to (if (series != null && it.series == series) 100 else 0) + it.topics.count { t -> t in topics } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(n)
        .map { it.first }
        .toList()
}
