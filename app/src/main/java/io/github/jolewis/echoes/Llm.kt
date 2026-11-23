package io.github.jolewis.echoes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLM 配置。密钥只存在本机 SharedPreferences，不进代码、不进仓库。
 * 任何 OpenAI 兼容接口都能用（DeepSeek / 本地 Ollama / 自建网关）。
 */
data class LlmConfig(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
) {
    val ready: Boolean get() = apiKey.isNotBlank()

    fun save(ctx: Context) {
        ctx.getSharedPreferences("echoes", 0).edit()
            .putString("ai_base", baseUrl.trim())
            .putString("ai_key", apiKey.trim())
            .putString("ai_model", model.trim())
            .apply()
    }

    companion object {
        fun load(ctx: Context): LlmConfig {
            val p = ctx.getSharedPreferences("echoes", 0)
            return LlmConfig(
                baseUrl = p.getString("ai_base", "")!!.ifBlank { "https://api.deepseek.com" },
                apiKey = p.getString("ai_key", "")!!,
                model = p.getString("ai_model", "")!!.ifBlank { "deepseek-chat" },
            )
        }
    }
}

object Llm {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** 调一次 chat/completions，返回 message.content。异常直接抛给上层。 */
    suspend fun chat(
        cfg: LlmConfig,
        system: String,
        user: String,
        maxTokens: Int = 400,
    ): String = withContext(Dispatchers.IO) {
        if (!cfg.ready) throw IOException("未配置 API Key")

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))

        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("response_format", JSONObject().put("type", "json_object"))

        val req = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}：${text.take(160)}")
            }
            JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }
}
