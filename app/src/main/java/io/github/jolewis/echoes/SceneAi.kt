package io.github.jolewis.echoes

import org.json.JSONObject

/**
 * 让 LLM 帮忙从候选里挑一组节目。
 *
 * 约束和本地逻辑一致：**要么整条系列，要么全是单集**，不能混着来。
 * LLM 只负责「在这些候选里选哪几期、按什么顺序」，不参与打分和过滤。
 */
object SceneAi {

    private val SYS = """
        你在帮一个中文科普音频 App 的用户挑「一段时间听什么」。
        给你一个场景（比如睡前、通勤）和候选节目列表，从中挑出适合这个场景的一组，按播放顺序返回。

        规则：
        - 只能从给定的候选里选，不能编造
        - 如果选了某个系列里的某一期，就必须把这条系列按顺序连着选（本组只放这一条系列）
        - 否则就选彼此不重复的单集，彼此话题不要重复
        - 总时长尽量贴近目标时长（±40% 以内）
        - 只输出 JSON：{"ids":["id1","id2"]}
    """.trimIndent()

    /**
     * @return 选中的节目（已按顺序、且满足系列纯净规则）；失败返回 null
     */
    suspend fun suggest(
        cfg: LlmConfig,
        scene: ScenePrefs,
        cands: List<Episode>,
        targetMin: Int,
        all: List<Episode>,
    ): List<Episode>? {
        if (!cfg.ready || cands.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("场景：").append(scene.name).append('\n')
        sb.append("目标时长：约 ").append(targetMin).append(" 分钟\n")
        val tone = scene.tones.joinToString("、")
        if (tone.isNotBlank()) sb.append("偏好风格：").append(tone).append('\n')
        if (scene.sleepOnly) sb.append("要求：适合睡前，平静、不刺激\n")
        sb.append("\n候选：\n")
        cands.forEach { e ->
            sb.append("- ").append(e.id).append(" | ").append(e.title)
                .append(" | ").append(e.minutes).append(" 分钟")
            if (e.contentType.isNotBlank()) sb.append(" | ").append(e.contentType)
            if (e.inCollection) sb.append(" | 系列《").append(e.collection).append("》第 ").append(e.collSeq).append(" 期")
            sb.append('\n')
        }

        val raw = runCatching {
            Llm.chat(cfg, SYS, sb.toString(), maxTokens = 3000)
        }.onFailure {
            android.util.Log.w("echoes", "sceneAi chat failed: " + it.message)
        }.getOrNull() ?: return null
        android.util.Log.d("echoes", "sceneAi raw=" + raw.take(200))

        val ids = runCatching {
            val o = JSONObject(SmartSearch.extractJson(raw))
            val arr = o.optJSONArray("ids") ?: return null
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        }.onFailure {
            android.util.Log.w("echoes", "sceneAi parse failed: " + it.message)
        }.getOrNull() ?: return null
        android.util.Log.d("echoes", "sceneAi ids=" + ids.size + " " + ids.take(3))

        val byId = cands.associateBy { it.id }
        val picked = ids.mapNotNull { byId[it] }.distinctBy { it.id }
        if (picked.isEmpty()) return null

        // 系列纯净：只要命中系列，就整条按顺序给
        val coll = picked.firstOrNull { it.inCollection }?.collection
        if (coll != null) {
            val parts = all.filter { it.collection == coll }.sortedBy { it.collSeq }
            val out = ArrayList<Episode>()
            var sum = 0
            for (e in parts) {
                if (out.isNotEmpty() && sum >= targetMin * 60) break
                out.add(e); sum += e.durationSec
            }
            return out.ifEmpty { null }
        }
        return picked
    }
}
