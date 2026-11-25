package io.github.jolewis.echoes

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户数据（收听记录、进度、收藏）存哪。
 *
 * 存在应用私有目录的坏处：卸载即删。所以优先放到媒体库里的 `state/` 子目录——
 * 库本来就在手机磁盘上、能跟着库一起迁移，也不受卸载影响。
 * 库不可写时回退到私有目录，行为跟以前一致。
 */
object State {

    private const val DIR = "state"

    private fun writable(dir: File): Boolean = runCatching {
        if (!dir.isDirectory) dir.mkdirs()
        val probe = File(dir, ".probe")
        probe.writeText("1")
        probe.delete()
        true
    }.getOrDefault(false)

    fun dir(ctx: Context): File {
        val inLib = File(Library.root(ctx), DIR)
        if (writable(inLib)) return inLib
        val priv = File(ctx.filesDir, DIR)
        priv.mkdirs()
        return priv
    }

    /** 收听流水。首次启动会把旧的私有目录记录迁移过来。 */
    fun listensFile(ctx: Context): File {
        val dst = File(dir(ctx), "listens.jsonl")
        val legacy = File(ctx.filesDir, "listens.jsonl")
        if (!dst.exists() && legacy.exists()) {
            runCatching { legacy.copyTo(dst, overwrite = false) }
        }
        return dst
    }

    /** 进度/收藏/上次播放的镜像。 */
    fun profileFile(ctx: Context): File = File(dir(ctx), "profile.json")
}

/**
 * 把 SharedPreferences 镜像到磁盘，重装后自动恢复。
 *
 * **不写入 API Key**：媒体库目录是明文可见的，密钥不能落到那里。
 */
object Profile {

    private const val PREFS = "echoes"
    private const val SECRET_PREFIX = "ai_"

    fun restore(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, 0)
        if (prefs.all.isNotEmpty()) return          // 已有数据，不覆盖
        val f = State.profileFile(ctx)
        if (!f.exists()) return
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        val e = prefs.edit()
        o.keys().forEach { k ->
            if (k.startsWith(SECRET_PREFIX)) return@forEach
            when (val v = o.opt(k)) {
                is Int -> e.putInt(k, v)
                is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) e.putInt(k, v.toInt()) else e.putLong(k, v)
                is Double -> e.putInt(k, v.toInt())
                is String -> e.putString(k, v)
                is JSONArray -> e.putStringSet(
                    k, (0 until v.length()).map { v.optString(it) }.filter { it.isNotBlank() }.toSet()
                )
            }
        }
        e.apply()
    }

    fun dump(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, 0)
        val o = JSONObject()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith(SECRET_PREFIX)) return@forEach
            when (v) {
                is Long -> o.put(k, v)
                is Int -> o.put(k, v)
                is String -> o.put(k, v)
                is Set<*> -> o.put(k, JSONArray(v.mapNotNull { it?.toString() }))
            }
        }
        runCatching { State.profileFile(ctx).writeText(o.toString()) }
    }

    /** 给设置页显示用。 */
    fun location(ctx: Context): String = State.dir(ctx).absolutePath
}


/** 安全读 int：历史版本可能把整数存成了 Long，直接 getInt 会抛 ClassCastException。 */
fun android.content.SharedPreferences.intOf(key: String, def: Int = 0): Int =
    when (val v = all[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is String -> v.toIntOrNull() ?: def
        else -> def
    }

/** 安全读 long。 */
fun android.content.SharedPreferences.longOf(key: String, def: Long = 0L): Long =
    when (val v = all[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull() ?: def
        else -> def
    }
