package io.github.jolewis.echoes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放队列。只存节目 id，进度/收藏走 prefs，队列跟着 prefs 一起镜像到媒体库。
 * 播放单位是"一期"（多段节目内部仍由 Media3 连播），一期放完由 App 推进到下一期。
 */
class QueueState {
    var ids by mutableStateOf<List<String>>(emptyList())
    var index by mutableStateOf(-1)

    val currentId: String? get() = ids.getOrNull(index)
    fun isEmpty(): Boolean = ids.isEmpty()
    fun hasNext(): Boolean = index + 1 in ids.indices

    fun replace(list: List<String>, startAt: Int = 0) {
        ids = list
        index = startAt.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    fun enqueue(id: String) {
        if (id in ids) return
        ids = ids + id
    }

    fun removeAt(i: Int) {
        if (i !in ids.indices) return
        ids = ids.filterIndexed { k, _ -> k != i }
        if (i < index) index -= 1
        else if (i == index) index = index.coerceAtMost(ids.size - 1)
    }

    fun clear() {
        ids = emptyList()
        index = -1
    }

    // ---------- 持久化（放 prefs，会被 Profile.dump 一起镜像到 state/） ----------
    companion object {
        private const val SEP = "\u0001"
        private const val K_IDS = "q_ids"
        private const val K_IDX = "q_idx"

        fun load(ctx: Context): QueueState {
            val p = ctx.getSharedPreferences("echoes", 0)
            val raw = p.getString(K_IDS, "").orEmpty()
            val q = QueueState()
            q.ids = raw.split(SEP).filter { it.isNotBlank() }
            q.index = p.getInt(K_IDX, if (q.ids.isEmpty()) -1 else 0)
            return q
        }

        fun save(ctx: Context, q: QueueState) {
            ctx.getSharedPreferences("echoes", 0).edit()
                .putString(K_IDS, q.ids.joinToString(SEP))
                .putInt(K_IDX, q.index)
                .apply()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    episodes: List<Episode>,
    qs: QueueState,
    onDismiss: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val byId = remember(episodes) { episodes.associateBy { it.id } }
    val items = remember(qs.ids) { qs.ids.mapNotNull { byId[it] } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Tokens.Surface,
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.width(36.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(Tokens.Muted)
                )
            }
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("播放队列", color = Tokens.OnBg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            if (items.isNotEmpty()) {
                Text("${items.size} 期 · 第 ${qs.index + 1} 期", color = Tokens.Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("清空", color = Tokens.Accent, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))

        if (items.isEmpty()) {
            EmptyHint("队列是空的。搜索结果的「播放」或详情页「加入队列」会往这里加")
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                itemsIndexed(items, key = { _, e -> e.id }) { i, ep ->
                    val playing = i == qs.index
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (playing) Tokens.Elevated else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable { onPlayAt(i) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (playing) "▶" else "${i + 1}",
                            color = if (playing) Tokens.Primary else Tokens.Muted,
                            fontSize = 12.sp, modifier = Modifier.width(24.dp)
                        )
                        CoverImage(ep, 44, 10)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                ep.title,
                                color = if (playing) Tokens.Primary else Tokens.OnBg,
                                fontSize = 13.sp,
                                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text("${ep.minutes} 分钟 · ${ep.contentType}",
                                color = Tokens.Muted, fontSize = 10.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemove(i) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, null, tint = Tokens.Muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}
