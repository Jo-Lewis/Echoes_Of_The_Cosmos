package io.github.jolewis.echoes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ 我的（总入口）

@Composable
fun MineScreen(
    episodes: List<Episode>,
    recs: List<ListenRec>,
    favs: Set<String>,
    sleepMin: Int,
    aiReady: Boolean,
    sceneCount: Int,
    onOpenPage: (String) -> Unit,
) {
    val todaySec = ListenLog.todaySec(recs)
    val streak = ListenLog.streak(recs)
    val totalSec = ListenLog.totalSec(recs)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("我的", color = Tokens.OnBg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            CardBox(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("今天听了 ${fmtShort(todaySec)}", color = Tokens.OnBg,
                            fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("连续 $streak 天 · 累计 ${fmtShort(totalSec)}",
                            color = Tokens.Muted, fontSize = 11.sp)
                    }
                    AssistChip(
                        onClick = { onOpenPage("stats") },
                        label = { Text("看报告", fontSize = 11.sp) }
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        item {
            MenuGroup {
                MenuRow(Icons.Filled.BarChart, "收听统计", "时长、时段、偏好、完播率") { onOpenPage("stats") }
                MenuRow(Icons.Filled.Favorite, "我的收藏", "${favs.size} 期") { onOpenPage("favs") }
                MenuRow(Icons.Filled.History, "收听历史", "最近听过") { onOpenPage("history") }
            }
        }

        item {
            MenuGroup {
                MenuRow(Icons.Filled.AutoAwesome, "情景模式", "$sceneCount 个模式，可改偏好") { onOpenPage("scenes") }
                MenuRow(Icons.Filled.Timer, "定时关闭",
                    if (sleepMin == 0) "关" else "$sleepMin 分钟") { onOpenPage("settings") }
                MenuRow(Icons.Filled.Settings, "播放设置", "定时、跳过、语速") { onOpenPage("settings") }
                MenuRow(Icons.Filled.AutoAwesome, "AI 设置",
                    if (aiReady) "已配置，可用自然语言点播" else "未配置，当前为本地匹配") { onOpenPage("ai") }
            }
        }

        item {
            MenuGroup {
                MenuRow(Icons.Filled.Storage, "媒体库", "${episodes.size} 期") { onOpenPage("library") }
                MenuRow(Icons.Filled.Info, "关于", "v0.1.0") { onOpenPage("about") }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MenuGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Tokens.Surface).padding(vertical = 4.dp),
        content = content
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Tokens.Primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Tokens.OnBg, fontSize = 14.sp)
            if (sub.isNotBlank()) Text(sub, color = Tokens.Muted, fontSize = 11.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Tokens.Muted, modifier = Modifier.size(18.dp))
    }
}

// ------------------------------------------------------------------ 子页面外壳

@Composable
fun SubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Tokens.Bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = Tokens.OnBg) }
            Text(title, color = Tokens.OnBg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

// ------------------------------------------------------------------ 收听统计

@Composable
fun StatsPage(episodes: List<Episode>, recs: List<ListenRec>, onBack: () -> Unit) {
    val todaySec = ListenLog.todaySec(recs)
    val totalSec = ListenLog.totalSec(recs)
    val streak = ListenLog.streak(recs)
    val distinct = ListenLog.distinctEpisodes(recs)
    val avg = if (recs.isEmpty()) 0L else totalSec / recs.size
    val days = ListenLog.lastDays(recs, 14)
    val maxDay = (days.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    val hours = ListenLog.hourBuckets(recs)
    val maxHour = (hours.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    val types = ListenLog.typeTop(recs, episodes, 3)
    val maxType = (types.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    val top = ListenLog.topEpisodes(recs, 5)
    val night = ListenLog.latestNight(recs)
    val finish = ListenLog.finishRate(recs)
    val longest = recs.maxOfOrNull { it.sec } ?: 0L

    SubPage("收听统计", onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCell("今天", fmtShort(todaySec), Modifier.weight(1f))
                    StatCell("连续", "$streak 天", Modifier.weight(1f))
                    StatCell("听过", "$distinct 期", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCell("累计", fmtShort(totalSec), Modifier.weight(1f))
                    StatCell("平均每次", fmtShort(avg), Modifier.weight(1f))
                    StatCell("完播率", finish?.let { "${(it * 100).toInt()}%" } ?: "—", Modifier.weight(1f))
                }
            }
            if (recs.isEmpty()) {
                item { EmptyHint("还没有收听记录，听一期就有了") }
            } else {
                item {
                    SectionTitle("近 14 天")
                    Row(
                        Modifier.fillMaxWidth().height(64.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        days.forEach { (_, sec) ->
                            val h = (sec.toFloat() / maxDay * 58f).coerceAtLeast(if (sec > 0) 4f else 2f)
                            Box(
                                Modifier.weight(1f).height(h.dp).clip(RoundedCornerShape(3.dp))
                                    .background(if (sec > 0) Tokens.Primary else Tokens.Elevated)
                            )
                        }
                    }
                }
                item {
                    SectionTitle("什么时段听")
                    hours.forEach { (label, sec) -> BarRow(label, sec, maxHour, fmtShort(sec)) }
                }
                if (types.isNotEmpty()) {
                    item {
                        SectionTitle("偏好的类型")
                        types.forEach { (label, sec) -> BarRow(label, sec, maxType, fmtShort(sec)) }
                    }
                }
                item {
                    SectionTitle("一些记录")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCell("最晚一次", night?.second ?: "—", Modifier.weight(1f))
                        StatCell("最长一段", fmtShort(longest), Modifier.weight(1f))
                    }
                }
                if (top.isNotEmpty()) {
                    item { SectionTitle("听得最多") }
                    items(top, key = { "st" + it.first }) { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(t.second, color = Tokens.OnBg, fontSize = 13.sp, maxLines = 1,
                                modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                            Text(fmtShort(t.third), color = Tokens.Primary, fontSize = 11.sp)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, mod: Modifier) {
    Column(mod.clip(RoundedCornerShape(14.dp)).background(Tokens.Surface).padding(12.dp)) {
        Text(label, color = Tokens.Muted, fontSize = 10.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Tokens.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun BarRow(label: String, value: Long, max: Long, right: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tokens.Muted, fontSize = 11.sp, modifier = Modifier.width(56.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Tokens.Elevated)) {
            val f = if (max > 0) (value.toFloat() / max).coerceIn(0.001f, 1f) else 0.001f
            if (value > 0) {
                Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Tokens.Primary))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(right, color = Tokens.OnBg, fontSize = 11.sp, modifier = Modifier.width(62.dp))
    }
}

// ------------------------------------------------------------------ 收藏 / 历史

@Composable
fun FavoritesPage(
    episodes: List<Episode>,
    favs: Set<String>,
    onBack: () -> Unit,
    onOpen: (Episode) -> Unit,
) {
    val list = remember(episodes, favs) { episodes.filter { it.id in favs } }
    SubPage("我的收藏", onBack) {
        if (list.isEmpty()) {
            EmptyHint("还没有收藏。在节目详情页点右上角的爱心")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(list, key = { "fv" + it.id }) { ep -> EpisodeRow(ep) { onOpen(ep) } }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun HistoryPage(
    episodes: List<Episode>,
    recs: List<ListenRec>,
    onBack: () -> Unit,
    onOpen: (Episode) -> Unit,
) {
    val items = remember(recs, episodes) {
        recs.groupBy { it.id }
            .map { (id, rs) -> Triple(id, rs.maxOf { it.at }, rs.sumOf { it.sec }) }
            .sortedByDescending { it.second }
            .take(100)
            .mapNotNull { (id, at, sec) ->
                episodes.firstOrNull { it.id == id }?.let { Triple(it, at, sec) }
            }
    }
    SubPage("收听历史", onBack) {
        if (items.isEmpty()) {
            EmptyHint("还没有收听记录")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(items, key = { "hi" + it.first.id }) { (ep, _, sec) ->
                    EpisodeRow(ep, progressSec = sec) { onOpen(ep) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ------------------------------------------------------------------ 设置 / 媒体库 / 关于

@Composable
fun SettingsPage(sleepMin: Int, onSleep: (Int) -> Unit, onBack: () -> Unit) {
    SubPage("播放设置", onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                SectionTitle("定时关闭")
                CardBox(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 15, 30, 45, 60).forEach { m ->
                            FilterChip(
                                selected = sleepMin == m,
                                onClick = { onSleep(m) },
                                label = { Text(if (m == 0) "关" else "${m}分", fontSize = 11.sp) },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("到点前 30 秒渐弱，然后暂停", color = Tokens.Muted, fontSize = 10.sp)
                }
            }
            item {
                SectionTitle("尚未实现")
                CardBox(Modifier.fillMaxWidth()) {
                    listOf("跳过片头（每期固定开场白）", "倍速播放", "响度归一化开关（正增益）").forEach {
                        Text("· $it", color = Tokens.Muted, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
fun LibraryPage(episodes: List<Episode>, onBack: () -> Unit) {
    val withAudio = episodes.count { it.audioFiles.isNotEmpty() }
    val withText = episodes.count { it.blocks != null }
    val withImages = episodes.count { it.images.isNotEmpty() }
    val noText = episodes.size - withText
    val series = episodes.count { it.series != null }

    SubPage("媒体库", onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                CardBox(Modifier.fillMaxWidth()) {
                    KV("节目总数", "${episodes.size} 期")
                    KV("音频可播放", "$withAudio 期")
                    KV("含文稿", "$withText 期")
                    KV("含配图", "$withImages 期")
                    KV("无文稿（待转录）", "$noText 期")
                    KV("属于专题", "$series 期")
                }
            }
            item {
                SectionTitle("存放位置")
                Text("/sdcard/2049lib/shows/2049/<年>/<日期 标题>/",
                    color = Tokens.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text("用 adb push 更新；改完记得重启 App 让缓存失效",
                    color = Tokens.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                SectionTitle("收听记录存在哪")
                Text("/sdcard/2049lib/state/", color = Tokens.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text("listens.jsonl 是收听流水，profile.json 是进度和收藏。"
                    + "放这里是为了卸载重装、换手机都不丢；API Key 不在这里。",
                    color = Tokens.Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, color = Tokens.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(v, color = Tokens.OnBg, fontSize = 12.sp)
    }
}

@Composable
fun AboutPage(onBack: () -> Unit) {
    SubPage("关于", onBack) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(10.dp))
            Text("星河回响", color = Tokens.OnBg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("v0.1.0 · 本地优先", color = Tokens.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "所有音频、文稿、配图都在本机，不上传任何内容。AI 语义理解尚未接入，当前搜索为本地关键词匹配。",
                color = Tokens.Muted, fontSize = 13.sp, lineHeight = 21.sp
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}
