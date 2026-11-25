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
) {
    /** 全空 = 模型什么都没给出来（多半解析失败），不是"只想按风格听"。 */
    val isEmpty: Boolean
        get() = topics.isEmpty() && domain.isEmpty() && contentType.isBlank() &&
            tone.isEmpty() && humorMin == 0 && loadMax == 0 && levelMax == 0 &&
            yearMin == 0 && durationMin == 0 && durationMax == 0 && !sleep &&
            exclude.isEmpty() && want.isBlank()
}

data class SearchResult(
    val pick: Episode,
    val reason: String,
    val queue: List<Episode>,
    val confidence: Double,
    val candidates: List<Pair<Episode, Double>>,
    val intent: Intent,
    val usedLlm: Boolean,
    /** AI 失败/放宽条件时的说明，空串表示一切正常 */
    val note: String = "",
)

enum class SearchPhase(val label: String) {
    UNDERSTAND("理解需求"),
    RECALL("检索节目"),
    RERANK("排列候选"),
    LOCAL_ONLY("本地匹配"),
    DONE("完成"),
}

object SmartSearch {

    private val DOMAINS = listOf(
        "天文", "物理", "化学", "生物", "医学", "地球科学", "数学",
        "计算机", "工程", "军事", "历史", "人文社科", "哲学", "杂项"
    )
    private val CONTENT_TYPES = listOf(
        "科普讲解", "人物传记", "历史叙事", "时事评论", "答疑互动", "实验演示", "系列专题", "杂谈漫聊"
    )
    private val TONES = listOf("好奇", "平静", "震撼", "沉痛", "热血", "怀旧", "轻松", "紧张")

    // ---------------------------------------------------------- 提示词

    /** 字段说明和 validateIntent 的规则必须同步维护。 */
    private val INTENT_SYS = """你是音频点播助手的查询理解模块。把用户的一句话翻译成检索条件。

**重要**：如果用户只说了情绪、场景、时长（"轻松有趣的""睡前听""短一点"），
topics 和 domain 就必须留空——不要为了填满字段而编造主题词。

**输出格式**：只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字。
所有字段都必须出现，没有就填空值。

{
  "topics": ["用户明确提到的具体主题，2-5 个，否则空数组"],
  "domain": ["只在用户说了学科时填，从 ${DOMAINS.joinToString("/")} 里选"],
  "content_type": "只在用户明确要类型时填，从 ${CONTENT_TYPES.joinToString("/")} 里选，否则空串",
  "tone": ["情绪，0-2 个，从 ${TONES.joinToString("/")} 里选"],
  "humor_min": 0,
  "load_max": 0,
  "level_max": 0,
  "year_min": 0,
  "duration_min": 0,
  "duration_max": 0,
  "sleep": false,
  "exclude": [],
  "want": "一句话复述用户意图"
}

字段含义：
- humor_min: 1-5，想要多有趣（"轻松有趣"=4，"严肃"=2），没说就 0
- load_max: 1-5，能接受的动脑程度（"不想动脑"=2），没说就 0
- level_max: 1-3，知识门槛（"入门/小白"=1），没说就 0
- duration_min/max: 秒（"十几分钟"=600/1200，"一小时"=3000/4200），没说就 0
- sleep: 是否明确要睡前的"""

    private val RERANK_SYS = """你是音频点播的选片员。给用户的一句话和一批候选节目，选出最该播的那一期。

判断顺序：

1. **主题匹配**（最重要）：只有**标题或 topics 字段**里包含这个主题，才算"讲了"。摘要里顺带提一句、举例时带上，都**不算**。宁可说"没有"，也不要选一期只是提到过的。
2. **如果没有任何候选真正讲了这个主题**：选最接近的一期，并在 reason 里如实说明"没有专门讲 X 的，这期最接近"。
3. **风格匹配**：情绪、趣味度、深浅、节奏是否符合用户此刻的状态。
4. **硬约束**：用户给了时长范围就必须满足；满足不了要在 reason 里说明。

**输出格式**：只输出一个 JSON 对象，不要代码块：
{"pick": 1, "reason": "≤25字", "queue": [2,3,4], "confidence": 0.8}

pick 和 queue 都填**序号数字**（不是节目名）。queue 给 3-6 个，要多样，不要全是一个系列。"""

    // ---------------------------------------------------------- 解析与校验

    internal fun extractJson(raw: String): String {
        val s = raw.trim()
        if (s.startsWith("{") && s.endsWith("}")) return s
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else s
    }

    /** 把模型输出收敛到合法值；返回 null 表示完全不可用。 */
    private fun validateIntent(raw: String): Intent? {
        val o = runCatching { JSONObject(extractJson(raw)) }.getOrNull() ?: return null

        fun strList(key: String, vocab: List<String>?, maxN: Int): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until a.length()) {
                val v = a.optString(i).trim()
                if (v.isEmpty() || v == "null" || v in out) continue
                if (vocab != null && v !in vocab) continue       // 丢弃编造的枚举
                out.add(v)
                if (out.size >= maxN) break
            }
            return out
        }

        fun num(key: String, lo: Int, hi: Int): Int =
            runCatching { o.optInt(key, 0) }.getOrDefault(0).coerceIn(lo, hi)

        val ct = o.optString("content_type").trim().let { if (it in CONTENT_TYPES) it else "" }

        return Intent(
            topics = strList("topics", null, 5),
            domain = strList("domain", DOMAINS, 3),
            contentType = ct,
            tone = strList("tone", TONES, 2),
            humorMin = num("humor_min", 0, 5),
            loadMax = num("load_max", 0, 5),
            levelMax = num("level_max", 0, 3),
            yearMin = num("year_min", 0, 2100),
            durationMin = num("duration_min", 0, 86400),
            durationMax = num("duration_max", 0, 86400),
            sleep = o.optBoolean("sleep", false),
            exclude = strList("exclude", null, 3),
            want = o.optString("want").trim().take(60).takeIf { it != "null" }.orEmpty(),
        )
    }

    /** 解析意图；失败带错误原因重试一次（官方口径单次 85%，重试把失败率压到 ~2%）。 */
    suspend fun understand(cfg: LlmConfig, query: String): Intent {
        var lastRaw = ""
        for (attempt in 0..1) {
            val user = if (attempt == 0) query else
                "$query\n\n上一次的返回无法解析成约定格式：\n${lastRaw.take(200)}\n" +
                    "请只输出一个合法 JSON 对象，字段与约定一致，不要解释。"
            val raw = Llm.chat(cfg, INTENT_SYS, user, maxTokens = 1200)
            lastRaw = raw
            val parsed = validateIntent(raw)
            if (parsed != null && !parsed.isEmpty) return parsed
        }
        return Intent()
    }

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

    private fun tagScore(e: Episode, intent: Intent): Double {
        var s = 0.0
        var w = 0.0
        if (intent.domain.isNotEmpty()) {
            w += 0.18
            s += 0.18 * (intent.domain.toSet().intersect(e.domain.toSet()).size.toDouble() / max(1, intent.domain.size))
        }
        if (intent.contentType.isNotEmpty()) {
            w += 0.18
            if (e.contentType == intent.contentType) s += 0.18
        }
        if (intent.tone.isNotEmpty()) {
            w += 0.20
            val hit = intent.tone.count { t -> t == e.tone }
            s += 0.20 * min(1.0, hit.toDouble() / max(1, intent.tone.size))
        }
        if (intent.humorMin > 0) {
            w += 0.16
            s += 0.16 * max(0.0, min(1.0, (e.humor - intent.humorMin + 1) / 2.0))
        }
        if (intent.loadMax > 0) {
            w += 0.14
            if (e.load <= intent.loadMax) s += 0.14
        }
        if (intent.levelMax > 0) {
            w += 0.14
            if (e.level <= intent.levelMax) s += 0.14
        }
        return if (w > 0) s / w else 0.0
    }

    private fun topicHit(e: Episode, topics: List<String>): Double {
        if (topics.isEmpty()) return 0.0
        val hay = e.topics + e.keywords
        var hit = 0.0
        topics.forEach { q ->
            when {
                e.title.contains(q) || hay.any { it.contains(q) } -> hit += 1.0
                hay.any { it.length >= 2 && (q.contains(it) || it.contains(q)) } -> hit += 0.6
                q.length >= 2 && e.topics.joinToString("").contains(q) -> hit += 0.4
            }
        }
        return min(1.0, hit / topics.size)
    }

    /** 睡前友好：优先元数据标注，缺了就用「低负荷 + 不激烈」兜底。 */
    private fun quiet(e: Episode): Boolean =
        e.sleepFriendly || (e.load <= 2 && e.humor <= 3 && e.tone in listOf("平静", "怀旧", "好奇"))

    private fun passFilter(e: Episode, intent: Intent): Boolean {
        if (intent.durationMin > 0 && e.durationSec < intent.durationMin) return false
        if (intent.durationMax > 0 && e.durationSec > intent.durationMax) return false
        if (intent.levelMax > 0 && e.level > intent.levelMax) return false
        if (intent.yearMin > 0 && e.year < intent.yearMin) return false
        if (intent.sleep && !quiet(e)) return false
        val hay = e.title + " " + e.topics.joinToString(" ")
        intent.exclude.forEach { bad -> if (bad.isNotBlank() && hay.contains(bad)) return false }
        return true
    }

    private fun scoreWith(
        index: Index,
        lex: Map<Int, Double>,
        intent: Intent,
        k: Int,
    ): List<Pair<Episode, Double>> {
        val wLex: Double
        val wTag: Double
        val wTopic: Double
        if (intent.topics.isEmpty()) {
            wLex = 0.55; wTag = 0.45; wTopic = 0.0
        } else {
            wLex = 0.45; wTag = 0.25; wTopic = 0.30
        }
        val scored = ArrayList<Pair<Episode, Double>>()
        index.eps.forEachIndexed { i, e ->
            if (!passFilter(e, intent)) return@forEachIndexed
            val sc = wLex * (lex[i] ?: 0.0) + wTag * tagScore(e, intent) + wTopic * topicHit(e, intent.topics)
            if (sc > 0.02) scored.add(e to sc)
        }
        scored.sortByDescending { it.second }
        return scored.take(k)
    }

    data class Recall(val hits: List<Pair<Episode, Double>>, val relaxed: Boolean)

    /**
     * 召回。约束太严导致筛空时逐级放宽，而不是直接说"没有"。
     * 顺序：原条件 → 去掉睡前 → 去掉时长 → 去掉门槛/年份。
     */
    fun recall(index: Index, query: String, intent: Intent, k: Int = 40): Recall {
        val lex = index.lexical(query)
        val variants = listOf(
            intent,
            intent.copy(sleep = false),
            intent.copy(sleep = false, durationMin = 0, durationMax = 0),
            intent.copy(sleep = false, durationMin = 0, durationMax = 0, levelMax = 0, yearMin = 0),
        )
        variants.forEachIndexed { i, v ->
            val hits = scoreWith(index, lex, v, k)
            if (hits.isNotEmpty()) return Recall(hits, i > 0)
        }
        val fallback = index.eps.sortedByDescending { it.durationSec }.take(k).map { it to 0.001 }
        return Recall(fallback, true)
    }

    // ---------------------------------------------------------- 精排

    private fun card(e: Episode, i: Int): String =
        "$i. ${e.title} | ${e.minutes}分钟 | ${e.contentType} | ${e.domain.joinToString("")} " +
            "| L${e.level} 动脑${e.load} 趣味${e.humor} | ${e.tone} | " +
            "${e.topics.take(4).joinToString("/")} | ${e.oneLine}"

    data class Ranked(val pickIndex: Int, val reason: String, val queue: List<Int>, val confidence: Double)

    private fun parseRank(raw: String, size: Int): Ranked? {
        val o = runCatching { JSONObject(extractJson(raw)) }.getOrNull() ?: return null

        fun idx(v: Any?): Int? {
            val k = (v?.toString() ?: "").trim().trim('.', '[', ']', ' ').toIntOrNull() ?: return null
            return if (k in 1..size) k - 1 else null
        }

        val pick = idx(o.opt("pick")) ?: return null
        val queue = ArrayList<Int>()
        o.optJSONArray("queue")?.let { arr ->
            for (i in 0 until arr.length()) {
                idx(arr.opt(i))?.let { if (it != pick && it !in queue) queue.add(it) }
            }
        }
        return Ranked(
            pick,
            o.optString("reason").trim().take(40).takeIf { it != "null" }.orEmpty(),
            queue.take(6),
            o.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
        )
    }

    suspend fun rerank(
        cfg: LlmConfig,
        query: String,
        intent: Intent,
        cands: List<Pair<Episode, Double>>,
    ): Ranked? {
        val listing = cands.mapIndexed { i, (e, _) -> card(e, i + 1) }.joinToString("\n")
        val base = "用户说：$query\n意图：${intent.want}\n\n候选（序号 = 下标+1）：\n$listing"
        var lastRaw = ""
        for (attempt in 0..1) {
            val user = if (attempt == 0) base else
                "$base\n\n上一次返回无法解析（pick 必须是 1-${cands.size} 的数字）：\n${lastRaw.take(160)}\n" +
                    "请只输出 JSON。"
            val raw = Llm.chat(cfg, RERANK_SYS, user, maxTokens = 1600)
            lastRaw = raw
            parseRank(raw, cands.size)?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------- 编排

    suspend fun run(
        cfg: LlmConfig,
        index: Index,
        query: String,
        onPhase: (SearchPhase) -> Unit,
        onIntent: (Intent) -> Unit = {},
    ): SearchResult? {
        var note = ""
        var intent = Intent()

        if (cfg.ready) {
            onPhase(SearchPhase.UNDERSTAND)
            runCatching { understand(cfg, query) }
                .onSuccess { intent = it }
                .onFailure { note = "理解失败：${it.message?.take(60)}" }
            if (intent.isEmpty && note.isBlank()) {
                note = "AI 没能理解这句话，已退回关键词匹配"
            }
        } else {
            onPhase(SearchPhase.LOCAL_ONLY)
        }
        onIntent(intent)

        onPhase(SearchPhase.RECALL)
        val rc = recall(index, query, intent, k = 40)
        if (rc.hits.isEmpty()) return null
        if (rc.relaxed && note.isBlank()) note = "按条件没筛出结果，已放宽限制"

        if (cfg.ready) {
            onPhase(SearchPhase.RERANK)
            val ranked = runCatching { rerank(cfg, query, intent, rc.hits) }.getOrNull()
            if (ranked != null) {
                val pick = rc.hits[ranked.pickIndex].first
                val queue = ranked.queue.mapNotNull { rc.hits.getOrNull(it)?.first }
                    .filter { it.id != pick.id }
                onPhase(SearchPhase.DONE)
                return SearchResult(pick, ranked.reason, queue, ranked.confidence, rc.hits, intent, true, note)
            }
            if (note.isBlank()) note = "AI 选片失败，已按关键词排序"
        }

        onPhase(SearchPhase.DONE)
        return SearchResult(
            pick = rc.hits.first().first,
            reason = "",
            queue = rc.hits.drop(1).take(5).map { it.first },
            confidence = 0.0,
            candidates = rc.hits,
            intent = intent,
            usedLlm = false,
            note = note,
        )
    }
}
