package io.github.jolewis.echoes

import androidx.media3.session.MediaController

/**
 * 音量只有一个出口。
 *
 * 之前有两处直接写 controller.volume：每期的响度增益、睡眠定时渐弱。
 * 它们互相覆盖（换期时渐弱被重置），所以合并成"基准增益 × 淡出系数"。
 */
object Volume {
    private var gain = 1f        // 每期响度均衡（离线 ebur128 测的 gain_db）
    private var fadeSleep = 1f   // 定时关闭的渐弱
    private var fadeScene = 1f   // 情景模式（睡前）的渐弱

    val effective: Float get() = (gain * minOf(fadeSleep, fadeScene)).coerceIn(0f, 1f)

    fun apply(c: MediaController?) {
        c?.volume = effective
    }

    fun setGain(c: MediaController?, v: Float) {
        gain = v.coerceIn(0f, 1f)
        apply(c)
    }

    fun setFadeSleep(c: MediaController?, v: Float) {
        fadeSleep = v.coerceIn(0f, 1f)
        apply(c)
    }

    fun setFadeScene(c: MediaController?, v: Float) {
        fadeScene = v.coerceIn(0f, 1f)
        apply(c)
    }

    fun reset(c: MediaController?) {
        gain = 1f
        fadeSleep = 1f
        fadeScene = 1f
        apply(c)
    }
}
