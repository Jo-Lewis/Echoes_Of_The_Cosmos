package io.github.jolewis.echoes

import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 查询意图。字段和流水线里的 Python 版本保持一致，方便对照调参。 */
data class Intent(
    val topics: List<String> = emptyList(),
    val domain: List<String> = emptyList(),
    val contentType: String = "",
    val tone: List<String> = emptyList(),
    val humorMin: Int = 0,
    val loadMax: Int = 0,
    val levelMax: Int = 0,
    val yearMin: Int = 0,
    val durationMin: Int = 0,
    val durationMax: Int = 0,
    val sleep: Boolean = false,
    val exclude: List<String> = emptyList(),
    val want: String = "",
)

data class SearchResult(
    val pick: Episode,
    val reason: String,
    val queue: List<Episode>,
    val confidence: Double,
    val candidates: List<Pair<Episode, Double>>,
    val intent: Intent,
    val usedLlm: Boolean,
    /** AI 失败时的说明，空串表示一切正常 */
    val note: String = "",
)

/** 检索进度，UI 按这三个阶段显示。 */
enum class SearchPhase(val label: String) {
    UNDERSTAND("理解需求"),
    RECALL("检索节目"),
    RERANK("排列候选"),
    LOCAL_ONLY("本地匹配"),
    DONE("完成"),
}

object SmartSearch {

    // ---------------------------------------------------------- 提示词

    private const val INTENT_SYS = """你是音频点播助手的查询理解模块。把用户的一句话翻译成检索条件。

**重要**：如果用户只说了情绪、场景、时长（"轻松有趣的""睡前听""短一点"），
topics 和 domain 就必须留空——不要为了填满字段而编造主题词。
编出来的"轻松科普""趣味知识"会污染关键词召回。

字段：
- topics: 只填用户明确提到的具体主题（火星、鲁迅、脱欧、咖啡），否则 []。2-5 个
- domain: 只在用户说了学科时才填（"讲点物理""历史类的"），否则 []
  选项：[天文,物理,化学,生物,医学,地球科学,数学,计算机,工程,军事,历史,人文社科,哲学,杂项]
- content_type: 只在用户明确要类型时填（"想听个故事"→历史叙事/人物传记），否则 ""
  选项：[科普讲解,人物传记,历史叙事,时事评论,答疑互动,实验演示,系列专题,杂谈漫聊]
- tone: 情绪，0-2 个，从 [好奇,平静,震撼,沉痛,热血,怀旧,轻松,紧张]
- humor_min: 1-5，想要多有趣（"轻松有趣"=4，"严肃"=2），没说就 0
- load_max: 1-5，能接受的动脑程度（"不想动脑/不费脑"=2，"随便"=3），没说就 0
- level_max: 1-3，知识门槛（"入门/小白"=1），没说就 0
- year_min: 4 位年份，用户说了"2024 年之后"这类时间限制时才填，否则 0
- duration_min / duration_max: 秒（"十几分钟"=600/1200，"一小时"=3000/4200），没说就 0
- sleep: true/false，是否明确要睡前的
- exclude: 不想要的内容
- want: 一句话复述用户意图

只输出 JSON。"""

    private const val RERANK_SYS = """你是音频点播的选片员。给用户的一句话和一批候选节目，选出最该播的那一期。

判断顺序：

1. **主题匹配**（最重要）：只有**标题或 topics 字段**里包含这个主题，才算"讲了"。摘要里顺带提一句、举例时带上，都**不算**。宁可说"没有"，也不要选一期只是提到过的。
2. **如果没有任何候选真正讲了这个主题**：选最接近的一期，并在 reason 里如实说明"没有专门讲 X 的，这期最接近"。
3. **风格匹配**：情绪、趣味度、深浅、节奏是否符合用户此刻的状态。
4. **硬约束**：用户给了时长范围就必须满足；满足不了要在 reason 里说明。

输出 JSON：
{"pick": "<序号>", "reason": "≤25字，说清为什么选它", "queue": ["序号","序号"], "confidence": 0.0-1.0}

pick 和 queue 都填序号数字。queue 给 3-6 个相关的，要多样，不要全是一个系列。只输出 JSON。"""

    // ---------------------------------------------------------- 意图解析

    fun parseIntent(raw: String): Intent = runCatching {
        val o = JSONObject(raw)
        fun strList(k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
        }
        Intent(
            topics = strList("topics").take(5),
            domain = strList("domain").take(3),
            contentType = o.optString("content_type").trim().takeIf { it != "null" }.orEmpty(),
            tone = strList("tone").take(2),
            humorMin = o.optInt("humor_min", 0).coerceIn(0, 5),
            loadMax = o.optInt("load_max", 0).coerceIn(0, 5),
            levelMax = o.optInt("level_max", 0).coerceIn(0, 3),
            yearMin = o.optInt("year_min", 0),
            durationMin = o.optInt("duration_min", 0),
            durationMax = o.optInt("duration_max", 0),
            sleep = o.optBoolean("sleep", false),
            exclude = strList("exclude").take(3),
            want = o.optString("want").trim(),
        )
    }.getOrElse { Intent(want = raw.take(40)) }

    suspend fun understand(cfg: LlmConfig, query: String): Intent =
        parseIntent(Llm.chat(cfg, INTENT_SYS, query, maxTokens = 400))

    // ---------------------------------------------------------- 召回

    private val CJK = Regex("[\\u4e00-\\u9fa5]+")

    private fun bigrams(s: String): Set<String> {
        val out = HashSet<String>()
        CJK.findAll(s).forEach { m ->
            val seg = m.value
            for (i in 0 until seg.length - 1) out.add(seg.substring(i, i + 2))
            if (seg.length == 1) out.add(seg)
        }
        return out
    }

    /** BM25-lite 索引：只在第一次检索时建，之后复用。 */
    class Index(val eps: List<Episode>) {
        private val n = eps.size
        private val post = HashMap<String, MutableList<Int>>()
        private val df = HashMap<String, Int>()
        private val dlen = IntArray(n)
        private val idfCache = HashMap<String, Double>()

        init {
            eps.forEachIndexed { i, e ->
                val terms = bigrams(text(e))
                dlen[i] = max(1, terms.size)
                terms.forEach { t ->
                    post.getOrPut(t) { ArrayList() }.add(i)
                    df[t] = (df[t] ?: 0) + 1
                }
            }
        }

        private fun idf(t: String): Double = idfCache.getOrPut(t) {
            ln(1.0 + n / ((df[t] ?: 0) + 1.0))
        }

        fun lexical(query: String): Map<Int, Double> {
            val qs = bigrams(query)
            if (qs.isEmpty()) return emptyMap()
            val scores = HashMap<Int, Double>()
            qs.forEach { t ->
                val ids = post[t] ?: return@forEach
                val w = idf(t)
                ids.forEach { i ->
                    scores[i] = (scores[i] ?: 0.0) + w / sqrt(dlen[i].toDouble())
                }
            }
            val mx = scores.values.maxOrNull() ?: return emptyMap()
            if (mx <= 0) return emptyMap()
            return scores.mapValues { it.value / mx }
        }
    }

    private fun text(e: Episode): String = buildString {
        append(e.title)
        append(' ').append(e.topics.joinToString(" "))
        append(' ').append(e.keywords.joinToString(" "))
        append(' ').append(e.summary)
        append(' ').append(e.oneLine)
        append(' ').append(e.domain.joinToString(" "))
    }

    private fun tagScore(e: Episode, it: Intent): Double {
        var s = 0.0
        var w = 0.0
        if (it.domain.isNotEmpty()) {
            w += 0.18
            val hit = it.domain.toSet().intersect(e.domain.toSet()).size
            s += 0.18 * (hit.toDouble() / max(1, it.domain.size))
        }
        if (it.contentType.isNotEmpty()) {
            w += 0.18
            if (e.contentType == it.contentType) s += 0.18
        }
        if (it.tone.isNotEmpty()) {
            w += 0.20
            val hit = it.tone.count { t -> t == e.tone }
            s += 0.20 * min(1.0, hit.toDouble() / max(1, it.tone.size))
        }
        if (it.humorMin > 0) {
            w += 0.16
            s += 0.16 * max(0.0, min(1.0, (e.humor - it.humorMin + 1) / 2.0))
        }
        if (it.loadMax > 0) {
            w += 0.14
            if (e.load <= it.loadMax) s += 0.14
        }
        if (it.levelMax > 0) {
            w += 0.14
            if (e.level <= it.levelMax) s += 0.14
        }
        return if (w > 0) s / w else 0.0
    }

    private fun topicHit(e: Episode, topics: List<String>): Double {
        if (topics.isEmpty()) return 0.0
        val hay = e.topics + e.keywords
        var hit = 0.0
        topics.forEach { q ->
            when {
                e.title.contains(q) || hay.any { it.contains(q) || q.contains(it) } -> hit += 1.0
                hay.any { it.length >= 2 && (q.contains(it) || it.contains(q)) } -> hit += 0.6
                q.length >= 2 && e.topics.joinToString("").contains(q) -> hit += 0.4
            }
        }
        return min(1.0, hit / topics.size)
    }

    private fun passFilter(e: Episode, it: Intent): Boolean {
        if (it.durationMin > 0 && e.durationSec < it.durationMin) return false
        if (it.durationMax > 0 && e.durationSec > it.durationMax) return false
        if (it.levelMax > 0 && e.level > it.levelMax) return false
        if (it.yearMin > 0 && e.year < it.yearMin) return false
        if (it.sleep && !e.sleepFriendly) return false
        val hay = e.title + " " + e.topics.joinToString(" ")
        it.exclude.forEach { bad -> if (bad.isNotBlank() && hay.contains(bad)) return false }
        return true
    }

    fun recall(index: Index, query: String, it: Intent, k: Int = 40): List<Pair<Episode, Double>> {
        val lex = index.lexical(query)
        val wLex: Double
        val wTag: Double
        val wTopic: Double
        if (it.topics.isEmpty()) {
            wLex = 0.55; wTag = 0.45; wTopic = 0.0
        } else {
            wLex = 0.45; wTag = 0.25; wTopic = 0.30
        }

        val scored = ArrayList<Pair<Episode, Double>>()
        index.eps.forEachIndexed { i, e ->
            if (!passFilter(e, it)) return@forEachIndexed
            val sc = wLex * (lex[i] ?: 0.0) + wTag * tagScore(e, it) + wTopic * topicHit(e, it.topics)
            if (sc > 0.02) scored.add(e to sc)
        }
        scored.sortByDescending { it.second }
        if (scored.isNotEmpty()) return scored.take(k)

        // 只有硬约束、本地分全 0 时兜底：按时长降序给一批
        return index.eps.filter { e -> passFilter(e, it) }
            .sortedByDescending { it.durationSec }
            .take(k)
            .map { it to 0.001 }
    }

    // ---------------------------------------------------------- 精排

    private fun card(e: Episode, i: Int): String =
        "$i. ${e.title} | ${e.minutes}分钟 | ${e.contentType} | ${e.domain.joinToString("")} " +
            "| L${e.level} 动脑${e.load} 趣味${e.humor} | ${e.tone} | " +
            "${e.topics.take(4).joinToString("/")} | ${e.oneLine}"

    data class Ranked(val pickIndex: Int, val reason: String, val queue: List<Int>, val confidence: Double)

    private fun parseRank(raw: String, size: Int): Ranked = runCatching {
        val o = JSONObject(raw)
        fun idx(v: Any?): Int? {
            val k = (v?.toString() ?: "").trim().trim('.', '[', ']', ' ').toIntOrNull() ?: return null
            return if (k in 1..size) k - 1 else null
        }
        val pick = idx(o.opt("pick")) ?: 0
        val q = ArrayList<Int>()
        val arr = o.optJSONArray("queue")
        if (arr != null) {
            for (i in 0 until arr.length()) idx(arr.opt(i))?.let { if (it !in q) q.add(it) }
        }
        Ranked(pick, o.optString("reason").trim(), q.take(6),
            o.optDouble("confidence", 0.0).coerceIn(0.0, 1.0))
    }.getOrDefault(Ranked(0, "", emptyList(), 0.0))

    suspend fun rerank(
        cfg: LlmConfig,
        query: String,
        it: Intent,
        cands: List<Pair<Episode, Double>>,
    ): Ranked {
        val listing = cands.mapIndexed { i, (e, _) -> card(e, i + 1) }.joinToString("\n")
        val user = "用户说：$query\n意图：${it.want}\n\n候选（序号 = 下标+1）：\n$listing"
        return parseRank(Llm.chat(cfg, RERANK_SYS, user, maxTokens = 500), cands.size)
    }

    // ---------------------------------------------------------- 编排

    /**
     * 完整流程：理解 → 召回 → 精排。
     * 没有配置 Key 时直接走本地召回，结果标注「本地匹配」。
     */
    suspend fun run(
        cfg: LlmConfig,
        index: Index,
        query: String,
        onPhase: (SearchPhase) -> Unit,
        onIntent: (Intent) -> Unit = {},
    ): SearchResult? {
        var it: Intent
        var usedLlm = false
        var note = ""

        if (cfg.ready) {
            onPhase(SearchPhase.UNDERSTAND)
            it = runCatching { understand(cfg, query) }
                .getOrElse {
                    note = "意图解析失败：${it.message?.take(60)}"
                    Intent()
                }
        } else {
            onPhase(SearchPhase.LOCAL_ONLY)
            it = Intent()
        }
        onIntent(it)

        onPhase(SearchPhase.RECALL)
        val cands = recall(index, query, it, k = 40)
        if (cands.isEmpty()) return null

        if (cfg.ready) {
            onPhase(SearchPhase.RERANK)
            val ranked = runCatching { rerank(cfg, query, it, cands) }
                .onFailure { note = "AI 选片失败：${it.message?.take(60)}" }
                .getOrNull()
            if (ranked != null) {
                usedLlm = true
                val pick = cands[ranked.pickIndex].first
                val queue = ranked.queue.mapNotNull { cands.getOrNull(it)?.first }.filter { it.id != pick.id }
                onPhase(SearchPhase.DONE)
                return SearchResult(pick, ranked.reason, queue, ranked.confidence, cands, it, true, note)
            }
        }

        onPhase(SearchPhase.DONE)
        return SearchResult(
            pick = cands.first().first,
            reason = "",
            queue = cands.drop(1).take(5).map { it.first },
            confidence = 0.0,
            candidates = cands,
            intent = it,
            usedLlm = false,
            note = note,
        )
    }
}
