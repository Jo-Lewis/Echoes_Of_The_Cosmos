package io.github.jolewis.echoes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 情景模式：一键"按此刻的状态听"。
 *
 * 全部走本地规则，不调 LLM——点一下就要出声，不能等 5 秒。
 * 每期时长区间 + 情绪/趣味/负荷筛选 + 目标总时长 + 淡出时长。
 */
enum class Scene(
    val label: String,
    val emoji: String,
    val blurb: String,
    /** 每期时长区间（分钟） */
    val minPer: Int,
    val maxPer: Int,
    /** 整段大概多久（分钟） */
    val total: Int,
    /** 最后多少分钟缓慢淡出，0 = 不淡出 */
    val fade: Int,
    val tones: List<String>,
    val humorMin: Int,
    val humorMax: Int,
    val loadMax: Int,
    val levelMax: Int,
    val sleepOnly: Boolean = false,
    val contentTypes: List<String> = emptyList(),
) {
    SLEEP("睡前", "🛌", "${20} 分钟 · 渐弱到静音", 6, 20, 20, 8,
        listOf("平静", "怀旧", "好奇"), 0, 4, 2, 3, sleepOnly = true),

    COMMUTE("通勤", "🚇", "25 分钟 · 有故事线", 8, 25, 25, 0,
        listOf("好奇", "震撼", "紧张"), 0, 5, 4, 2,
        contentTypes = listOf("历史叙事", "人物传记", "时事评论")),

    CHORES("家务", "🧹", "20 分钟 · 轻松不费脑", 6, 20, 20, 0,
        listOf("轻松", "好奇"), 4, 5, 3, 3),

    FOCUS("专注", "🎧", "40 分钟 · 低刺激背景", 10, 40, 40, 0,
        listOf("平静", "好奇"), 0, 4, 3, 3,
        contentTypes = listOf("杂谈漫聊", "科普讲解")),

    WORKOUT("运动", "🏃", "20 分钟 · 热血提速", 6, 25, 20, 0,
        listOf("热血", "紧张", "震撼"), 0, 5, 5, 5),

    DRINK("小酌", "🍷", "30 分钟 · 怀旧慢聊", 12, 45, 30, 0,
        listOf("怀旧", "平静", "沉痛"), 0, 4, 4, 4,
        contentTypes = listOf("杂谈漫聊", "历史叙事", "人物传记")),
    ;

    /** 按模式挑一批节目，总时长接近 total 分钟。 */
    fun pick(eps: List<Episode>): List<Episode> {
        val pool = eps.asSequence()
            .filter { it.durationSec in (minPer * 60)..(maxPer * 60) }
            .filter { it.level <= levelMax }
            .filter { it.load <= loadMax }
            .filter { it.humor in humorMin..humorMax }
            .filter { e -> tones.isEmpty() || e.tone in tones }
            .filter { e -> contentTypes.isEmpty() || e.contentType in contentTypes }
            .filter { e -> !sleepOnly || e.sleepFriendly }
            .map { e -> e to score(e) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()

        if (pool.isEmpty()) return emptyList()

        // 贪心凑时长，同时尽量不重复主题
        val picked = ArrayList<Episode>()
        val usedTopics = HashSet<String>()
        var sum = 0
        for (e in pool) {
            if (sum >= total * 60) break
            if (picked.size >= 4) break
            val overlap = e.topics.count { it in usedTopics }
            if (picked.isNotEmpty() && overlap >= 2) continue   // 撞主题就跳过
            picked.add(e)
            usedTopics.addAll(e.topics)
            sum += e.durationSec
        }
        return picked
    }

    private fun score(e: Episode): Int {
        var s = 3
        if (tones.isNotEmpty() && e.tone in tones) s += 4
        if (e.contentType in contentTypes) s += 3
        if (sleepOnly && e.sleepFriendly) s += 6
        // 睡前偏好短一点、浅一点
        if (this == SLEEP) {
            if (e.durationSec <= 15 * 60) s += 2
            if (e.load <= 2) s += 2
        }
        if (this == WORKOUT && e.intensity() >= 4) s += 3
        if (e.humor >= 4) s += 1
        return s
    }
}

/** 强度：优先用元数据字段，没有再按情绪猜。 */
private fun Episode.intensity(): Int = when (tone) {
    "热血", "紧张", "震撼" -> 5
    "沉痛" -> 4
    "好奇" -> 3
    "轻松" -> 2
    "平静", "怀旧" -> 1
    else -> 3
}

@Composable
fun SceneRow(onPick: (Scene) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(Scene.values().toList()) { sc ->
            Column(
                Modifier.width(104.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Tokens.Surface)
                    .clickable { onPick(sc) }
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Text(sc.emoji, fontSize = 20.sp)
                Spacer(Modifier.height(6.dp))
                Text(sc.label, color = Tokens.OnBg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(sc.blurb, color = Tokens.Muted, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2)
            }
        }
    }
}
