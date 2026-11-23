package io.github.jolewis.echoes

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
private val CLOCK = SimpleDateFormat("HH:mm", Locale.CHINA)

data class ListenRec(
    val id: String,
    val title: String,
    val sec: Long,
    val at: Long,
    /** 这一段里听到的最远位置（毫秒），用于判断有没有听完 */
    val maxPos: Long = 0L,
    /** 该期总时长（毫秒），0 表示老记录没有这个字段 */
    val durMs: Long = 0L,
) {
    val dateKey: String get() = DAY.format(Date(at))
    val minuteOfDay: Int
        get() = Calendar.getInstance().apply { timeInMillis = at }
            .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    val hour: Int
        get() = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
    val finished: Boolean
        get() = durMs > 0 && maxPos >= durMs * 0.9
}

/**
 * 收听流水：一行一条，追加写。只记"真的在播"的时间，暂停/切后台不计。
 */
object ListenLog {
    private const val MIN_SEC = 10L

    private fun file(ctx: Context) = File(ctx.filesDir, "listens.jsonl")

    @Synchronized
    fun append(ctx: Context, id: String, title: String, sec: Long, at: Long,
               maxPos: Long = 0L, durSec: Long = 0L) {
        if (sec < MIN_SEC || id.isBlank()) return
        val o = JSONObject()
            .put("id", id).put("t", title).put("s", sec).put("at", at)
            .put("p", maxPos).put("d", durSec * 1000L)
        runCatching { file(ctx).appendText(o.toString() + "\n") }
    }

    fun load(ctx: Context): List<ListenRec> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val o = JSONObject(line)
                ListenRec(
                    id = o.optString("id"),
                    title = o.optString("t"),
                    sec = o.optLong("s"),
                    at = o.optLong("at"),
                    maxPos = o.optLong("p"),
                    durMs = o.optLong("d"),
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---------------- 统计 ----------------

    fun todaySec(recs: List<ListenRec>): Long {
        val today = DAY.format(Date())
        return recs.filter { it.dateKey == today }.sumOf { it.sec }
    }

    fun todayCount(recs: List<ListenRec>): Int {
        val today = DAY.format(Date())
        return recs.count { it.dateKey == today }
    }

    fun totalSec(recs: List<ListenRec>): Long = recs.sumOf { it.sec }

    fun distinctEpisodes(recs: List<ListenRec>): Int = recs.map { it.id }.distinct().size

    /** 连续收听天数（今天没听就从昨天往前数）。 */
    fun streak(recs: List<ListenRec>): Int {
        if (recs.isEmpty()) return 0
        val days = recs.map { it.dateKey }.toHashSet()
        val cal = Calendar.getInstance()
        var n = 0
        if (!days.contains(DAY.format(cal.time))) cal.add(Calendar.DAY_OF_MONTH, -1)
        while (days.contains(DAY.format(cal.time))) {
            n++
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return n
    }

    /** 时段分布：深夜 (23-6) / 早上 (6-12) / 下午 (12-18) / 晚上 (18-23)，单位秒。 */
    fun hourBuckets(recs: List<ListenRec>): List<Pair<String, Long>> {
        val b = longArrayOf(0, 0, 0, 0)
        recs.forEach { r ->
            val i = when (r.hour) {
                in 6..11 -> 1
                in 12..17 -> 2
                in 18..22 -> 3
                else -> 0
            }
            b[i] += r.sec
        }
        return listOf("深夜" to b[0], "早上" to b[1], "下午" to b[2], "晚上" to b[3])
    }

    /** 按内容类型聚合，需要 episodes 做 join。 */
    fun typeTop(recs: List<ListenRec>, episodes: List<Episode>, n: Int = 3): List<Pair<String, Long>> {
        val typeOf = episodes.associate { it.id to it.contentType.ifBlank { "未标注" } }
        return recs.groupBy { typeOf[it.id] ?: "未标注" }
            .mapValues { e -> e.value.sumOf { it.sec } }
            .entries.sortedByDescending { it.value }
            .take(n).map { it.key to it.value }
    }

    /** 完播率：有总时长信息的记录里，听到 90% 以上的比例。 */
    fun finishRate(recs: List<ListenRec>): Float? {
        val valid = recs.filter { it.durMs > 0 }
        if (valid.isEmpty()) return null
        return valid.count { it.finished }.toFloat() / valid.size
    }

    fun topEpisodes(recs: List<ListenRec>, n: Int = 5): List<Triple<String, String, Long>> =
        recs.groupBy { it.id }
            .map { (id, list) -> Triple(id, list.first().title, list.sumOf { it.sec }) }
            .sortedByDescending { it.third }
            .take(n)

    fun recentEpisodes(recs: List<ListenRec>, n: Int = 3): List<String> =
        recs.sortedByDescending { it.at }.map { it.id }.distinct().take(n)

    /** 最晚一次：凌晨 0–5 点算"更晚"，+24 小时偏移再比。 */
    fun latestNight(recs: List<ListenRec>): Triple<String, String, Int>? {
        var best: ListenRec? = null
        var bestScore = -1
        for (r in recs) {
            val m = r.minuteOfDay
            val score = if (m < 6 * 60) m + 24 * 60 else m
            if (score > bestScore) { bestScore = score; best = r }
        }
        val b = best ?: return null
        return Triple(b.dateKey, CLOCK.format(Date(b.at)), bestScore)
    }

    fun lastDays(recs: List<ListenRec>, days: Int = 14): List<Pair<String, Long>> {
        val cal = Calendar.getInstance()
        val keys = ArrayList<String>(days)
        repeat(days) {
            keys.add(DAY.format(cal.time))
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        val byDay = recs.groupBy { it.dateKey }.mapValues { it.value.sumOf { r -> r.sec } }
        return keys.reversed().map { it to (byDay[it] ?: 0L) }
    }
}

/**
 * 播放计时器：每秒 tick，只在真正播放时累加；攒够 60 秒或停止时落盘。
 */
class PlayTracker(private val ctx: Context) {
    private val MIN_FLUSH_MS = 10_000L
    private var id: String? = null
    private var title: String = ""
    private var acc = 0L
    private var maxPos = 0L
    private var durSec = 0L
    private var startedAt = System.currentTimeMillis()

    fun onTick(newId: String?, newTitle: String, posMs: Long, durationSec: Long) {
        if (newId.isNullOrBlank()) return
        if (newId != id) {
            flush()
            id = newId
            title = newTitle
            durSec = durationSec
            maxPos = posMs
            startedAt = System.currentTimeMillis()
        }
        if (posMs > maxPos) maxPos = posMs
        acc += 1000
        if (acc >= 60_000) flush()
    }

    fun onIdle() {
        if (acc > 0) flush()
    }

    fun flush() {
        val i = id ?: return
        if (acc >= MIN_FLUSH_MS) {
            ListenLog.append(ctx, i, title, acc / 1000, startedAt, maxPos, durSec)
        }
        acc = 0
        startedAt = System.currentTimeMillis()
    }
}
