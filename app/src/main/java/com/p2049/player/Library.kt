package com.p2049.player

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 一张配图 + 图注（图注来自源文件名）。 */
data class Pic(val file: File, val caption: String)

data class Episode(
    val id: String,
    val title: String,
    val date: String,
    val year: Int,
    val durationSec: Int,
    val oneLine: String,
    val summary: String,
    val bestFor: String,
    val series: String?,
    val gainDb: Float,
    val contentType: String,
    val level: Int,
    val load: Int,
    val humor: Int,
    val tone: String,
    val topics: List<String>,
    val keywords: List<String>,
    val audioFiles: List<File>,
    val partDurations: List<Int>,
    val images: List<Pic>,
    val cover: File?,
    val blocks: File?,
    val transcript: File?,
    val dir: File,
) {
    val minutes: Int get() = (durationSec / 60).coerceAtLeast(1)
    val audio: File get() = audioFiles.first()
    val haystack: String by lazy {
        "$title ${topics.joinToString(" ")} ${keywords.joinToString(" ")} $oneLine"
    }
}

/** 进程内缓存：Activity 被系统回收重建时，不用重扫 3,000 个 json。 */
object LibraryCache {
    @Volatile
    var mem: List<Episode>? = null
}

object Library {

    const val SCHEMA = 3

    /** 优先用公共目录 /sdcard/2049lib（adb 推的文件属主是 shell，App 私有目录读不了）。 */
    fun root(ctx: Context): File {
        val pub = File(android.os.Environment.getExternalStorageDirectory(), "2049lib")
        if (File(pub, "shows/2049").isDirectory) return pub
        return File(ctx.getExternalFilesDir(null), "library")
    }

    fun isReady(ctx: Context): Boolean =
        File(root(ctx), "shows/2049").isDirectory

    /** 缓存文件名带 schema 版本，升级后旧缓存自动作废。 */
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "library.v$SCHEMA.cache")

    fun loadCached(ctx: Context): List<Episode> {
        LibraryCache.mem?.let { return it }
        val root = root(ctx)
        val cf = cacheFile(ctx)
        if (cf.exists()) {
            val list = runCatching {
                cf.readLines().mapNotNull { line ->
                    if (line.isBlank()) null
                    else runCatching { decode(root, JSONObject(line)) }.getOrNull()
                }
            }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                LibraryCache.mem = list
                return list
            }
        }
        val fresh = load(ctx)
        LibraryCache.mem = fresh
        runCatching { saveCache(ctx, fresh) }
        return fresh
    }

    fun saveCache(ctx: Context, list: List<Episode>) {
        val root = root(ctx)
        val sb = StringBuilder(6 shl 20)
        list.forEach { sb.append(encode(root, it)).append("\n") }
        cacheFile(ctx).writeText(sb.toString())
    }

    // ---------------------------------------------------------------- 解析

    private fun str(o: JSONObject?, key: String): String {
        val v = o?.optString(key).orEmpty()
        return if (v == "null" || v == "[]") "" else v
    }

    private fun strList(o: JSONObject?, key: String): List<String> {
        val a = o?.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() && it != "null" }
    }

    fun load(ctx: Context): List<Episode> {
        val base = File(root(ctx), "shows/2049")
        if (!base.isDirectory) return emptyList()
        val out = ArrayList<Episode>(3200)
        base.listFiles()?.sortedBy { it.name }?.forEach { yearDir ->
            if (!yearDir.isDirectory) return@forEach
            yearDir.listFiles()?.sortedBy { it.name }?.forEach { dir ->
                val meta = File(dir, "meta.json")
                if (!meta.isFile) return@forEach
                runCatching { parse(dir, meta) }.getOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parse(dir: File, metaFile: File): Episode? {
        val m = JSONObject(metaFile.readText())
        if (m.optInt("schema", 0) != SCHEMA) return null      // 版本不对跳过，由上层提示
        val assets = m.optJSONObject("assets")
        val tax = m.optJSONObject("taxonomy")
        val tech = m.optJSONObject("tech")

        val audioArr = assets?.optJSONArray("audio")
        val audioFiles = ArrayList<File>()
        val partDurs = ArrayList<Int>()
        if (audioArr != null) {
            for (i in 0 until audioArr.length()) {
                val o = audioArr.optJSONObject(i) ?: continue
                val p = File(dir, str(o, "path"))
                if (p.isFile) {
                    audioFiles.add(p)
                    partDurs.add(o.optInt("duration_sec", 0))
                }
            }
        }
        if (audioFiles.isEmpty()) return null

        val text = assets?.optJSONObject("text")
        val blocksName = str(text, "blocks")
        val mdName = str(text, "markdown")

        val images = ArrayList<Pic>()
        val imgArr = assets?.optJSONArray("images")
        if (imgArr != null) {
            for (i in 0 until imgArr.length()) {
                val o = imgArr.optJSONObject(i) ?: continue
                val p = File(dir, str(o, "path"))
                images.add(Pic(p, o.optString("caption").takeIf { it != "null" } ?: ""))
            }
        }

        val coverName = str(assets, "cover")
        val gain = (tech?.optDouble("gain_db", 0.0) ?: 0.0).toFloat()

        return Episode(
            id = m.optString("id"),
            title = m.optString("title"),
            date = str(m, "date"),
            year = m.optInt("year"),
            durationSec = m.optInt("duration_sec"),
            oneLine = m.optString("summary_one_line"),
            summary = m.optString("summary"),
            bestFor = m.optString("best_for"),
            series = str(m, "series").takeIf { it.isNotBlank() },
            gainDb = gain,
            contentType = tax?.optString("content_type").orEmpty(),
            level = tax?.optInt("level", 2) ?: 2,
            load = tax?.optInt("cognitive_load", 3) ?: 3,
            humor = tax?.optInt("humor", 3) ?: 3,
            tone = tax?.optString("tone_primary").orEmpty(),
            topics = strList(tax, "topics"),
            keywords = strList(m, "keywords"),
            audioFiles = audioFiles,
            partDurations = partDurs,
            images = images,
            cover = if (coverName.isEmpty()) null else File(dir, coverName).takeIf { it.isFile },
            blocks = if (blocksName.isEmpty()) null else File(dir, blocksName).takeIf { it.isFile },
            transcript = if (mdName.isEmpty()) null else File(dir, mdName).takeIf { it.isFile },
            dir = dir,
        )
    }

    // ---------------------------------------------------------------- 缓存编解码

    private fun rel(root: File, f: File?): String =
        if (f == null) "" else runCatching { f.relativeTo(root).path }.getOrDefault("")

    private fun encode(root: File, e: Episode): String {
        val imgs = org.json.JSONArray()
        e.images.forEach { p -> imgs.put(JSONObject().put("p", rel(root, p.file)).put("c", p.caption)) }
        val audio = org.json.JSONArray()
        e.audioFiles.forEachIndexed { i, f ->
            audio.put(JSONObject().put("p", rel(root, f)).put("d", e.partDurations.getOrElse(i) { 0 }))
        }
        return JSONObject()
            .put("id", e.id).put("ti", e.title).put("dt", e.date).put("y", e.year)
            .put("du", e.durationSec).put("ol", e.oneLine).put("sm", e.summary)
            .put("bf", e.bestFor).put("se", e.series ?: "")
            .put("g", e.gainDb.toDouble()).put("ct", e.contentType)
            .put("lv", e.level).put("ld", e.load).put("hu", e.humor).put("to", e.tone)
            .put("tp", org.json.JSONArray(e.topics)).put("kw", org.json.JSONArray(e.keywords))
            .put("au", audio).put("im", imgs)
            .put("cv", rel(root, e.cover)).put("bl", rel(root, e.blocks))
            .put("tr", rel(root, e.transcript)).put("dir", rel(root, e.dir))
            .toString()
    }

    private fun decode(root: File, o: JSONObject): Episode {
        fun arr(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        }

        val audioFiles = ArrayList<File>()
        val partDurs = ArrayList<Int>()
        o.optJSONArray("au")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                audioFiles.add(File(root, x.optString("p")))
                partDurs.add(x.optInt("d"))
            }
        }

        val images = ArrayList<Pic>()
        o.optJSONArray("im")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                images.add(Pic(File(root, x.optString("p")), x.optString("c")))
            }
        }

        return Episode(
            id = o.optString("id"), title = o.optString("ti"), date = o.optString("dt"),
            year = o.optInt("y"), durationSec = o.optInt("du"),
            oneLine = o.optString("ol"), summary = o.optString("sm"),
            bestFor = o.optString("bf"), series = o.optString("se").takeIf { it.isNotBlank() },
            gainDb = o.optDouble("g", 0.0).toFloat(),
            contentType = o.optString("ct"), level = o.optInt("lv", 2),
            load = o.optInt("ld", 3), humor = o.optInt("hu", 3), tone = o.optString("to"),
            topics = arr("tp"), keywords = arr("kw"),
            audioFiles = audioFiles,
            partDurations = partDurs,
            images = images,
            cover = o.optString("cv").takeIf { it.isNotBlank() }?.let { File(root, it) },
            blocks = o.optString("bl").takeIf { it.isNotBlank() }?.let { File(root, it) },
            transcript = o.optString("tr").takeIf { it.isNotBlank() }?.let { File(root, it) },
            dir = File(root, o.optString("dir")),
        )
    }

    /** 本地关键词检索：bigram 重叠 + 标题加权。离线也能用。 */
    fun search(list: List<Episode>, q: String): List<Episode> {
        val key = q.trim()
        if (key.isEmpty()) return list
        val grams = key.windowed(2).filter { it.isNotBlank() }.toSet()
        return list.map { e ->
            var s = 0.0
            grams.forEach { g ->
                if (e.title.contains(g)) s += 3.0
                else if (e.haystack.contains(g)) s += 1.0
            }
            e to s
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }
}
