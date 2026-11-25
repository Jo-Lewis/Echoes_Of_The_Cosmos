package io.github.jolewis.echoes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局段落检索：在整库正文里找「哪一段提到了这句话」。
 *
 * 索引是离线生成的 paragraphs.tsv（约 141,000 段 / 37 MB），
 * 不整体读进内存，而是逐行流式扫描——内存只占一行，速度几百毫秒。
 */
object ParaIndex {

    data class Hit(val epId: String, val startMs: Long, val text: String, val score: Int)

    fun file(ctx: Context): File = File(Library.root(ctx), "paragraphs.tsv")

    fun available(ctx: Context): Boolean = file(ctx).isFile

    /**
     * 扫描全库段落，返回命中度最高的若干条。
     * @param exclude 已经在结果里出现过的期，避免重复展示
     */
    suspend fun search(
        ctx: Context,
        query: String,
        limit: Int = 8,
        exclude: Set<String> = emptySet(),
    ): List<Hit> = withContext(Dispatchers.IO) {
        val f = file(ctx)
        if (!f.isFile || query.isBlank()) return@withContext emptyList()
        val tokens = ParaSearch.terms(query)
        if (tokens.isEmpty()) return@withContext emptyList()
        val need = maxOf(2, (tokens.size * 0.4).toInt() + 1)
        val whole = query.trim()

        val hits = ArrayList<Hit>(64)
        try {
            f.bufferedReader(bufferSize = 1 shl 16).useLines { lines ->
                for (line in lines) {
                    val t1 = line.indexOf('\t')
                    if (t1 <= 0) continue
                    val t2 = line.indexOf('\t', t1 + 1)
                    if (t2 <= t1) continue
                    val eid = line.substring(0, t1)
                    if (eid in exclude) continue
                    val body = line.substring(t2 + 1)
                    var sc = 0
                    for (t in tokens) if (body.contains(t, ignoreCase = true)) sc++
                    if (whole.length >= 2 && body.contains(whole, ignoreCase = true)) sc += 10
                    if (sc >= need) {
                        val ms = line.substring(t1 + 1, t2).toLongOrNull() ?: 0L
                        hits.add(Hit(eid, ms, body, sc))
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        // 同一期只留最好的一段，避免霸屏
        val seen = HashSet<String>()
        hits.sortedByDescending { it.score }
            .filter { seen.add(it.epId) }
            .take(limit)
    }
}
