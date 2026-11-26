package io.github.jolewis.echoes

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player

/**
 * 睡眠引擎：淡出、延长、提前淡出、自动暂停、睡眠书签。
 *
 * **必须跑在后台服务里**：之前这段逻辑挂在 Compose 的 tick 上，
 * 锁屏或退出界面就停摆——定时到了不会淡、也不会暂停。
 * 现在由 [PlaybackService] 每 500ms 调用 [tick]，与界面是否存活无关。
 */
object SleepEngine {

    private const val PREFS = "echoes"

    var active = false
        private set
    /** 这个情景是否启用淡出。没有配置淡出参数的模式（通勤/家务/专注…）为 false。 */
    var fadeEnabled = false
        private set
    var sceneName = ""
        private set

    private var fadeStartMin = 15
    private var fadeLenMin = 10
    private var idleStopMin = 5
    private var startedAt = 0L          // elapsedRealtime，淡出计时的起点
    private var zeroAt = 0L             // 音量到 0 的时刻
    private var extended = 0            // 延长了几次，仅用于界面展示

    /** 定时关闭（墙钟），与情景模式互不影响。 */
    private var stopAtWall = 0L
    private var lastLog = 0L

    val remainMin: Int
        get() {
            if (!active) return 0
            val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 60_000f
            return (fadeStartMin + fadeLenMin - elapsed).toInt().coerceAtLeast(0)
        }

    fun start(scene: String, fadeStart: Int, fadeLen: Int, idleStop: Int) {
        sceneName = scene
        // 不再兜底：没配就是没配，否则通勤/家务这些模式也会被淡出
        fadeStartMin = fadeStart.coerceAtLeast(0)
        fadeLenMin = fadeLen.coerceAtLeast(0)
        idleStopMin = idleStop
        fadeEnabled = fadeStartMin > 0 && fadeLenMin > 0
        startedAt = SystemClock.elapsedRealtime()
        zeroAt = 0L
        extended = 0
        active = true
        Volume.setFadeScene(null, 1f)
    }

    /** 延长收听：音量立刻回到原值，并从头再计一轮淡出计时。 */
    fun extend(minutes: Int = 10) {
        if (!active || !fadeEnabled) return
        startedAt = SystemClock.elapsedRealtime()
        zeroAt = 0L
        extended += 1
        // 延长后从头再来：先保持满音量一段时间，再按原节奏淡出
        fadeStartMin = minutes
        fadeLenMin = fadeLenMin.coerceAtLeast(5)
        Volume.setFadeScene(null, 1f)
    }

    /** 提前淡出：立刻开始，用 lenMin 分钟降到 0。 */
    fun fadeNow(lenMin: Int = 5) {
        if (!active || !fadeEnabled) return
        startedAt = SystemClock.elapsedRealtime()
        fadeStartMin = 0
        fadeLenMin = lenMin
        zeroAt = 0L
    }

    fun cancel() {
        active = false
        zeroAt = 0L
        Volume.setFadeScene(null, 1f)
    }

    fun setStopAt(wallMs: Long) {
        stopAtWall = wallMs
    }

    /**
     * 每 500ms 调一次。返回 true 表示这一拍应当暂停播放。
     * 音量写入 [Volume]，由调用方同步到播放器。
     */
    fun tick(ctx: Context, player: Player): Boolean {
        // ---- 定时关闭（与情景模式独立） ----
        if (stopAtWall > 0) {
            val left = stopAtWall - System.currentTimeMillis()
            if (left <= 0) {
                Volume.setFadeSleep(null, 1f)
                stopAtWall = 0L
                return true
            }
            // 最后 30 秒线性渐弱
            Volume.setFadeSleep(null, (left / 30_000f).coerceIn(0f, 1f))
        } else {
            Volume.setFadeSleep(null, 1f)
        }

        if (!active || !fadeEnabled) {
            Volume.setFadeScene(null, 1f)
            player.volume = Volume.effective
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val elapsedMin = (now - startedAt) / 60_000f
        val vol = when {
            elapsedMin <= fadeStartMin -> 1f
            elapsedMin >= fadeStartMin + fadeLenMin -> 0f
            else -> 1f - (elapsedMin - fadeStartMin) / fadeLenMin
        }
        Volume.setFadeScene(null, vol)
        player.volume = Volume.effective
        if (BuildConfig.DEBUG && now - lastLog > 10_000L) {
            lastLog = now
            android.util.Log.d(
                "echoes",
                "sleep scene=" + sceneName + " elapsed=" + elapsedMin.toInt() +
                    "min vol=" + vol + " playing=" + player.isPlaying
            )
        }

        if (vol <= 0f) {
            if (zeroAt == 0L) zeroAt = now
            if (idleStopMin > 0 && now - zeroAt >= idleStopMin * 60_000L) {
                // 睡着了：记一个「睡眠书签」，下次从停在这里的前 5 分钟接着听
                val id = player.currentMediaItem?.mediaId?.substringBefore('#')
                if (!id.isNullOrBlank()) {
                    val bm = (player.currentPosition - 5 * 60_000L).coerceAtLeast(0L)
                    ctx.getSharedPreferences(PREFS, 0).edit()
                        .putLong("bm_$id", bm)
                        .putLong("bm_at", System.currentTimeMillis())
                        .apply()
                }
                cancel()
                Volume.reset(null)
                player.volume = Volume.effective
                return true
            }
        } else {
            zeroAt = 0L
        }
        return false
    }
}
