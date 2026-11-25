package io.github.jolewis.echoes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一个系列：名字 + 按集数排好的节目。 */
data class Collection(val name: String, val eps: List<Episode>) {
    val totalMin: Int get() = eps.sumOf { it.durationSec } / 60
    val latest: String get() = eps.maxOfOrNull { it.date } ?: ""
}

/** 把库里的系列聚合成合集列表，按集数从多到少。 */
fun buildCollections(episodes: List<Episode>): List<Collection> =
    episodes.filter { it.inCollection }
        .groupBy { it.collection!! }
        .map { (name, list) -> Collection(name, list.sortedBy { it.collSeq }) }
        .sortedByDescending { it.eps.size }

@Composable
fun CollectionsPage(
    episodes: List<Episode>,
    onBack: () -> Unit,
    onOpenEpisode: (Episode) -> Unit,
    onPlayAll: (List<Episode>) -> Unit,
) {
    val cols = remember(episodes) { buildCollections(episodes) }
    var open by remember { mutableStateOf<String?>(null) }
    val cur = cols.firstOrNull { it.name == open }

    if (cur == null) {
        SubPage("系列合集", onBack) {
            Text(
                "共 ${cols.size} 个系列 · ${cols.sumOf { it.eps.size }} 期",
                color = Tokens.Muted, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(cols, key = { it.name }) { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(16.dp)).background(Tokens.Surface)
                            .clickable { open = c.name }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverImage(c.eps.first(), 56, 12)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = Tokens.OnBg, fontSize = 15.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${c.eps.size} 期 · 共 ${c.totalMin} 分钟" +
                                    if (c.latest.length >= 7) " · 最近 ${c.latest.take(7)}" else "",
                                color = Tokens.Muted, fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(Icons.Filled.KeyboardArrowRight, null, tint = Tokens.Muted)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        return
    }

    SubPage(cur.name, { open = null }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${cur.eps.size} 期 · 共 ${cur.totalMin} 分钟",
                color = Tokens.Muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onPlayAll(cur.eps) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tokens.Primary),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Tokens.Bg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("全部播放", color = Tokens.Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(cur.eps, key = { it.id }) { ep ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${ep.collSeq}".padStart(2, '0'),
                        color = Tokens.Primary, fontSize = 11.sp,
                        modifier = Modifier.width(24.dp)
                    )
                    EpisodeRow(ep) { onOpenEpisode(ep) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
