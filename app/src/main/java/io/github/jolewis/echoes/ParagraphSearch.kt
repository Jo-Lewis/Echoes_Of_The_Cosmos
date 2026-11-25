package io.github.jolewis.echoes

import org.json.JSONArray
import java.io.File

/** 一段命中的正文：第几段、起始时间、片段。 */
data class ParaHit(val startMs: Long, val text: String, val score: Int)

/**
 * 段落级检索：在已经召回的候选里，定位「哪一段真的提到了这个词」。
 *
 * 不做全局倒排索引（1268 万字，手机上不划算），而是在候选期的 blocks.json 里扫，
 * 一次只扫几期，速度够、也不占空间。
 */
object ParaSearch {

    /** 把查询拆成检索词：中文取二元组，英文数字取整词。 */
    fun terms(q: String): List<String> {
        val out = LinkedHashSet<String>()
        val norm = q.lowercase()
        Regex("[a-z0-9]{2,}").findAll(norm).forEach { out.add(it.value) }
        Regex("[\\u4e00-\\u9fa5]+").findAll(norm).forEach { seg ->
            val s = seg.value
            if (s.length == 1) out.add(s)
            else for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        }
        return out.toList()
    }

    /** 返回命中度最高的几段（按命中词数降序）。 */
    fun hits(ep: Episode, q: String, limit: Int = 2): List<ParaHit> {
        val f: File = ep.blocks ?: return emptyList()
        if (!f.isFile) return emptyList()
        val ts = terms(q)
        if (ts.isEmpty()) return emptyList()
        return try {
            val arr = org.json.JSONObject(f.readText()).optJSONArray("blocks") ?: return emptyList()
            // 门槛：整串出现直接算高分；否则要求命中足够多的二元组，避免「智能」命中「智能机」这类噪声
            val need = maxOf(2, (ts.size * 0.4).toInt() + 1)
            val whole = q.trim()
            val list = ArrayList<ParaHit>()
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                if (b.optString("type") != "p") continue
                val text = b.optString("text")
                if (text.length < 6) continue
                var sc = 0
                for (t in ts) if (text.contains(t, ignoreCase = true)) sc++
                if (whole.length >= 2 && text.contains(whole, ignoreCase = true)) sc += 10
                if (sc >= need) list.add(ParaHit(b.optLong("start_ms", 0L), text, sc))
            }
            list.sortedByDescending { it.score }.take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 截一段带上下文的摘录，命中词附近开窗。 */
    fun snippet(text: String, q: String, width: Int = 44): String {
        val ts = terms(q).filter { it.length >= 2 }
        var idx = -1
        for (t in ts) {
            val k = text.indexOf(t, ignoreCase = true)
            if (k >= 0 && (idx < 0 || k < idx)) idx = k
        }
        if (idx < 0) return text.take(width)
        val start = (idx - width / 3).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end) +
            (if (end < text.length) "…" else "")
    }

    fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
}
