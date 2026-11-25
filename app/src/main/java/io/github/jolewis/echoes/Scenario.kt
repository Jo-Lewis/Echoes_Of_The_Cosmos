package io.github.jolewis.echoes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.random.Random

/**
 * 情景模式的配置。内置六种是预设值，用户改过的和自定义的都存这里。
 *
 * 存 prefs（`scene_prefs`），跟着 Profile 一起镜像到媒体库 state/，重装不丢。
 */
// build-tag: normalfade
data class ScenePrefs(
    val id: String,
    val name: String,
    val emoji: String,
    val perMin: Int = 8,          // 单篇时长下限（分钟）
    val perMax: Int = 25,         // 单篇时长上限
    val totalMin: Int = 20,       // 一段大概多长
    val yearMin: Int = 0,         // 0 = 不限
    val yearMax: Int = 0,         // 0 = 不限
    val tones: List<String> = emptyList(),
    val humorMin: Int = 0,
    val humorMax: Int = 5,
    val loadMax: Int = 5,
    val levelMax: Int = 3,
    val contentTypes: List<String> = emptyList(),
    val sleepOnly: Boolean = false,
    val fadeMin: Int = 0,             // 旧语义：在最后 N 分钟淡出
    val fadeStartMin: Int = 0,        // 新语义：从第 N 分钟开始淡出（0=不用）
    val fadeLenMin: Int = 0,          // 淡出持续多少分钟
    val idleStopMin: Int = 0,         // 淡到 0 后，无操作多少分钟就暂停（0=不停）
    val countMode: String = "auto",   // auto / one / many
    val builtin: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("emoji", emoji)
        .put("perMin", perMin).put("perMax", perMax).put("totalMin", totalMin)
        .put("yearMin", yearMin).put("yearMax", yearMax)
        .put("tones", JSONArray(tones))
        .put("humorMin", humorMin).put("humorMax", humorMax)
        .put("loadMax", loadMax).put("levelMax", levelMax)
        .put("contentTypes", JSONArray(contentTypes))
        .put("sleepOnly", sleepOnly).put("fadeMin", fadeMin)
        .put("fadeStartMin", fadeStartMin).put("fadeLenMin", fadeLenMin)
        .put("idleStopMin", idleStopMin)
        .put("countMode", countMode).put("builtin", builtin)

    companion object {
        fun fromJson(o: JSONObject): ScenePrefs {
            fun list(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }
            return ScenePrefs(
                id = o.optString("id"),
                name = o.optString("name"),
                emoji = o.optString("emoji").ifBlank { "🎧" },
                perMin = o.optInt("perMin", 8),
                perMax = o.optInt("perMax", 25),
                totalMin = o.optInt("totalMin", 20),
                yearMin = o.optInt("yearMin", 0),
                yearMax = o.optInt("yearMax", 0),
                tones = list("tones"),
                humorMin = o.optInt("humorMin", 0),
                humorMax = o.optInt("humorMax", 5),
                loadMax = o.optInt("loadMax", 5),
                levelMax = o.optInt("levelMax", 3),
                contentTypes = list("contentTypes"),
                sleepOnly = o.optBoolean("sleepOnly", false),
                fadeMin = o.optInt("fadeMin", 0),
                fadeStartMin = o.optInt("fadeStartMin", 0),
                fadeLenMin = o.optInt("fadeLenMin", 0),
                idleStopMin = o.optInt("idleStopMin", 0),
                countMode = o.optString("countMode").ifBlank { "auto" },
                builtin = o.optBoolean("builtin", true),
            )
        }
    }
}

object Scenes {

    const val ALL_TONES = "好奇,平静,震撼,沉痛,热血,怀旧,轻松,紧张"
    const val ALL_TYPES = "科普讲解,人物传记,历史叙事,时事评论,答疑互动,实验演示,系列专题,杂谈漫聊"

    val tones: List<String> get() = ALL_TONES.split(",")
    val types: List<String> get() = ALL_TYPES.split(",")

    /** 内置预设。用户改过就以存储的为准。 */
    fun defaults(): List<ScenePrefs> = listOf(
        ScenePrefs(
            id = "sleep", name = "睡前", emoji = "🛌",
            perMin = 5, perMax = 60, totalMin = 50,
            tones = listOf("平静", "怀旧", "好奇"),
            humorMin = 0, humorMax = 4, loadMax = 2, levelMax = 3,
            sleepOnly = true, fadeMin = 0,
            fadeStartMin = 15, fadeLenMin = 10, idleStopMin = 5,
            countMode = "auto"
        ),
        ScenePrefs(
            id = "commute", name = "通勤", emoji = "🚇",
            perMin = 8, perMax = 25, totalMin = 25,
            tones = listOf("好奇", "震撼", "紧张"),
            humorMin = 0, humorMax = 5, loadMax = 4, levelMax = 2,
            contentTypes = listOf("历史叙事", "人物传记", "时事评论"),
            fadeMin = 0, countMode = "auto"
        ),
        ScenePrefs(
            id = "chores", name = "家务", emoji = "🧹",
            perMin = 6, perMax = 20, totalMin = 20,
            tones = listOf("轻松", "好奇"),
            humorMin = 4, humorMax = 5, loadMax = 3, levelMax = 3,
            fadeMin = 0, countMode = "auto"
        ),
        ScenePrefs(
            id = "focus", name = "专注", emoji = "🎧",
            perMin = 10, perMax = 40, totalMin = 40,
            tones = listOf("平静", "好奇"),
            humorMin = 0, humorMax = 4, loadMax = 3, levelMax = 3,
            contentTypes = listOf("杂谈漫聊", "科普讲解"),
            fadeMin = 0, countMode = "many"
        ),
        ScenePrefs(
            id = "workout", name = "运动", emoji = "🏃",
            perMin = 6, perMax = 25, totalMin = 20,
            tones = listOf("热血", "紧张", "震撼"),
            humorMin = 0, humorMax = 5, loadMax = 5, levelMax = 5,
            fadeMin = 0, countMode = "auto"
        ),
        ScenePrefs(
            id = "drink", name = "小酌", emoji = "🍷",
            perMin = 12, perMax = 45, totalMin = 30,
            tones = listOf("怀旧", "平静", "沉痛"),
            humorMin = 0, humorMax = 4, loadMax = 4, levelMax = 4,
            contentTypes = listOf("杂谈漫聊", "历史叙事", "人物传记"),
            fadeMin = 0, countMode = "auto"
        ),
    )

    // ---------------- 读写 ----------------

    fun load(ctx: Context): List<ScenePrefs> {
        // 旧版睡眠模式是「结尾 N 分钟淡出」，新版改成「第 N 分钟起淡出 + 静音后自动停」。
        // 只迁移一次，记在 scene_ver 里。
        val prefs0 = ctx.getSharedPreferences("echoes", 0)
        if (prefs0.intOf("scene_ver", 1) < 5) {
            val raw0 = prefs0.getString("scene_prefs", null)
            if (raw0 != null) {
                runCatching {
                    val arr = JSONArray(raw0)
                    val out = JSONArray()
                    for (k in 0 until arr.length()) {
                        val o = arr.getJSONObject(k)
                        if (o.optString("id") == "sleep") {
                            o.put("fadeMin", 0)
                            o.put("fadeStartMin", 15)
                            o.put("fadeLenMin", 10)
                            o.put("idleStopMin", 5)
                            o.put("totalMin", 50)
                            o.put("perMax", 60)
                        }
                        out.put(o)
                    }
                    prefs0.edit().putString("scene_prefs", out.toString())
                        .putInt("scene_ver", 5).apply()
                }
            } else {
                prefs0.edit().putInt("scene_ver", 5).apply()
            }
        }

        val raw = ctx.getSharedPreferences("echoes", 0).getString("scene_prefs", "").orEmpty()
        val stored = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { ScenePrefs.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        val byId = stored.associateBy { it.id }
        val merged = ArrayList<ScenePrefs>()
        // 内置的按默认顺序，用存储值覆盖
        defaults().forEach { d -> merged.add(byId[d.id] ?: d) }
        // 自定义的排在后面；已删除的内置模式不再出现
        stored.filter { !it.builtin }.forEach { merged.add(it) }
        return merged
    }

    fun save(ctx: Context, list: List<ScenePrefs>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences("echoes", 0).edit().putString("scene_prefs", arr.toString()).apply()
    }

    fun upsert(ctx: Context, p: ScenePrefs) {
        val list = load(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        save(ctx, list)
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, load(ctx).filter { it.id != id || it.builtin })
    }

    fun resetOne(ctx: Context, id: String) {
        val d = defaults().firstOrNull { it.id == id } ?: return
        upsert(ctx, d)
    }

    // ---------------- 选片 ----------------

    /**
     * 按配置挑节目。每次结果都不同：
     * 先打分取前 40，再随机洗牌，并排除上一次用过的那批。
     */
    /**
     * 生成一批节目。
     *
     * **系列纯净规则**：只要队列里出现系列节目，整条队列就只放这一个系列
     * （从第 1 期开始按顺序取）；否则只放单集，绝不掺系列碎片。
     * 之前是「先挑一期、再随便补齐」，会变成泽连斯基1 → 某单集 → 另一个系列某期，听着断。
     */
    fun pick(
        ctx: Context,
        p: ScenePrefs,
        eps: List<Episode>,
        exclude: Set<String> = emptySet(),
    ): List<Episode> {
        val yearFloor = p.yearMin
        val yearCeil = if (p.yearMax > 0) p.yearMax else 9999
        val recent = lastPicked(ctx, p.id) + exclude

        val scored = eps.asSequence()
            .filter { it.durationSec in (p.perMin * 60)..(p.perMax * 60) }
            .filter { it.year >= yearFloor && it.year <= yearCeil }
            .filter { it.level <= p.levelMax }
            .filter { it.load <= p.loadMax }
            .filter { it.humor in p.humorMin..p.humorMax }
            .filter { p.tones.isEmpty() || it.tone in p.tones }
            .filter { p.contentTypes.isEmpty() || it.contentType in p.contentTypes }
            .filter { !p.sleepOnly || it.sleepFriendly || quiet(it) }
            .map { it to score(p, it) }
            .sortedByDescending { it.second }
            .toList()

        if (scored.isEmpty()) return emptyList()

        val targetSec = p.totalMin * 60
        val head = scored.take(80)

        // 系列维度：整条听过一半以上就不再出现
        val collTotal = eps.filter { it.inCollection }.groupBy { it.collection }
            .mapValues { (_, v) -> v.size }
        val collRecent = eps.filter { it.inCollection && it.id in recent }
            .groupingBy { it.collection }.eachCount()
        val tired = collRecent.filter { (n, c) -> c * 2 >= (collTotal[n] ?: 1) }.keys

        val fresh = head.filter { it.first.id !in recent && it.first.collection !in tired }
            .ifEmpty { head.filter { it.first.id !in exclude } }
            .ifEmpty { head }
        val seed = weightedPick(fresh)?.first ?: return emptyList()

        val out = ArrayList<Episode>()

        if (seed.inCollection) {
            // 只放这一个系列，按顺序从第 1 期开始
            val parts = eps.filter { it.collection == seed.collection }.sortedBy { it.collSeq }
            var sum = 0
            for (e in parts) {
                if (out.isNotEmpty() && (sum >= targetSec || out.size >= 8)) break
                out.add(e)
                sum += e.durationSec
                if (p.countMode == "one") break
            }
        } else {
            // 只放单集：绝不再掺系列碎片
            val singles = (listOf(seed) + head.map { it.first } + scored.drop(80).map { it.first })
                .filter { !it.inCollection }
                .distinctBy { it.id }
            val usedTopics = HashSet<String>()
            var sum = 0
            val limit = if (p.countMode == "one") 1 else if (p.countMode == "many") 8 else 6
            for (e in singles) {
                if (out.size >= limit) break
                if (out.isNotEmpty() && sum >= targetSec) break
                if (e.id in exclude) continue
                if (out.isNotEmpty() && e.topics.count { it in usedTopics } >= 2) continue
                out.add(e)
                usedTopics.addAll(e.topics)
                sum += e.durationSec
            }
        }

        if (out.isEmpty()) return emptyList()
        remember(ctx, p.id, out)
        return out
    }

    /** 给 LLM 用的候选池：按场景偏好排序后的前 N 期（不做历史过滤）。 */
    fun candidates(p: ScenePrefs, eps: List<Episode>, limit: Int = 24): List<Episode> {
        val yearFloor = p.yearMin
        val yearCeil = if (p.yearMax > 0) p.yearMax else 9999
        return eps.asSequence()
            .filter { it.durationSec in (p.perMin * 60)..(p.perMax * 60) }
            .filter { it.year >= yearFloor && it.year <= yearCeil }
            .filter { it.level <= p.levelMax }
            .filter { it.load <= p.loadMax }
            .filter { it.humor in p.humorMin..p.humorMax }
            .filter { p.tones.isEmpty() || it.tone in p.tones }
            .filter { p.contentTypes.isEmpty() || it.contentType in p.contentTypes }
            .filter { !p.sleepOnly || it.sleepFriendly || quiet(it) }
            .map { it to score(p, it) }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
            .toList()
    }

    /** 按分数加权随机取一个，分数越高越可能被选中，但不是必然。 */
    private fun weightedPick(list: List<Pair<Episode, Int>>): Pair<Episode, Int>? {
        if (list.isEmpty()) return null
        val weights = list.map { (it.second.coerceAtLeast(1)).toDouble() }
        val total = weights.sum()
        var r = Random.Default.nextDouble() * total
        for (i in list.indices) {
            r -= weights[i]
            if (r <= 0) return list[i]
        }
        return list.last()
    }

    private fun quiet(e: Episode) = e.load <= 2 && e.humor <= 3 &&
        e.tone in listOf("平静", "怀旧", "好奇")

    private fun score(p: ScenePrefs, e: Episode): Int {
        var s = 3
        if (p.tones.isNotEmpty() && e.tone in p.tones) s += 4
        if (e.contentType in p.contentTypes) s += 3
        if (p.sleepOnly && e.sleepFriendly) s += 6
        if (p.yearMin > 0 && e.year in p.yearMin..(if (p.yearMax > 0) p.yearMax else 9999)) s += 2
        if (e.humor >= 4) s += 1
        return s
    }

    // ---------------- 避免重复 ----------------

    private fun recentKey(id: String) = "scene_recent_$id"

    private fun lastPicked(ctx: Context, id: String): Set<String> =
        ctx.getSharedPreferences("echoes", 0)
            .getStringSet(recentKey(id), emptySet())!!.toSet()

    /** 记住最近选过的期；累积到 120 期，避免翻来覆去就那几期。 */
    private fun remember(ctx: Context, id: String, picked: List<Episode>) {
        val prefs = ctx.getSharedPreferences("echoes", 0)
        val old = prefs.getStringSet(recentKey(id), emptySet()) ?: emptySet()
        val merged = LinkedHashSet(old)
        picked.forEach { merged.add(it.id) }
        while (merged.size > 120) merged.remove(merged.first())
        prefs.edit().putStringSet(recentKey(id), merged).apply()
    }

    fun describe(p: ScenePrefs): String {
        val parts = ArrayList<String>()
        parts.add("${p.perMin}-${p.perMax} 分钟/期")
        parts.add("约 ${p.totalMin} 分钟")
        if (p.yearMin > 0 || p.yearMax > 0) {
            parts.add(
                if (p.yearMax > 0) "${p.yearMin}-${p.yearMax} 年" else "${p.yearMin} 年后"
            )
        }
        if (p.fadeStartMin > 0 && p.fadeLenMin > 0) {
            parts.add("第 ${p.fadeStartMin} 分钟起淡出 ${p.fadeLenMin} 分钟")
        } else if (p.fadeMin > 0) {
            parts.add("末 ${p.fadeMin} 分钟淡出")
        }
        return parts.joinToString(" · ")
    }
}

/** 等大卡片：固定宽高，文案最多两行，保证一排对齐。 */
@Composable
fun SceneRow(list: List<ScenePrefs>, onPick: (ScenePrefs) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list) { sc ->
            Column(
                Modifier.width(108.dp).height(112.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Tokens.Surface)
                    .clickable { onPick(sc) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(sc.emoji, fontSize = 20.sp)
                Spacer(Modifier.height(4.dp))
                Text(sc.name, color = Tokens.OnBg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (sc.fadeMin > 0) "${sc.totalMin} 分钟 · 淡出" else "${sc.totalMin} 分钟",
                    color = Tokens.Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 正在运行的情景：显示剩余时间并可退出。 */
@Composable
fun SceneBanner(p: ScenePrefs, remainMin: Int, onExit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Tokens.Elevated).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(p.emoji, fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("${p.name}模式", color = Tokens.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                buildString {
                    append("剩余约 $remainMin 分钟")
                    if (p.fadeMin > 0) append(" · 末 ${p.fadeMin} 分钟淡出")
                    append(" · 播完自动停")
                },
                color = Tokens.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            Modifier.clip(RoundedCornerShape(50)).clickable { onExit() }.padding(6.dp)
        ) {
            Icon(Icons.Filled.Close, null, tint = Tokens.Muted, modifier = Modifier.size(16.dp))
        }
    }
}
