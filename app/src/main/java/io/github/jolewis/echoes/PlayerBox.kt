package io.github.jolewis.echoes

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** 与 PlaybackService 的连接封装。 */
class PlayerBox(private val ctx: Context) {

    private var future: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set

    fun connect(onReady: () -> Unit) {
        if (controller != null) {
            onReady(); return
        }
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        future = f
        f.addListener({
            runCatching {
                controller = f.get()
                onReady()
            }.onFailure { Log.e("2049", "connect failed", it) }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    /**
     * 把「全集内的绝对位置」换算成「第几段 + 段内偏移」。
     * 多段节目的 durationSec 是各段之和，直接拿它当 startPosition 会超过第一段的时长，
     * ExoPlayer 会抛 IllegalSeekPositionException。这是之前「下一曲闪退」的根因。
     */
    private fun resolveStart(ep: Episode, startMs: Long): Pair<Int, Long> {
        val n = ep.audioFiles.size
        if (n <= 1) {
            val durMs = ep.durationSec * 1000L
            val off = if (durMs > 0) startMs.coerceIn(0L, (durMs - 3000L).coerceAtLeast(0L)) else 0L
            return 0 to off
        }
        if (ep.partDurations.size != n) return 0 to 0L
        var idx = 0
        var off = startMs.coerceAtLeast(0L)
        while (idx < n - 1 && off >= ep.partDurations[idx] * 1000L) {
            off -= ep.partDurations[idx] * 1000L
            idx++
        }
        val segMs = ep.partDurations[idx] * 1000L
        if (segMs > 0) off = off.coerceIn(0L, (segMs - 3000L).coerceAtLeast(0L))
        return idx to off
    }

    fun playEpisode(ep: Episode, startMs: Long = 0L) {
        val c = controller ?: return
        if (ep.audioFiles.isEmpty()) return

        val items = ep.audioFiles.mapIndexed { i, f ->
            MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(f))
                .setMediaId("${ep.id}#$i")
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(
                            if (ep.audioFiles.size > 1) "${ep.title} (${i + 1}/${ep.audioFiles.size})"
                            else ep.title
                        )
                        .setArtist(ep.series ?: "2049")
                        .build()
                )
                .build()
        }

        val (idx, off) = resolveStart(ep, startMs)
        try {
            c.setMediaItems(items, idx, off)
            c.prepare()
            c.play()
        } catch (e: Exception) {
            // 兜底：任何位置异常都退回到从头播，而不是崩掉
            Log.e("2049", "playEpisode failed, fallback to 0", e)
            runCatching {
                c.setMediaItems(items, 0, 0L)
                c.prepare()
                c.play()
            }
        }
        applyGain(ep)
    }

    fun applyGain(ep: Episode) {
        val c = controller ?: return
        // Media3 的 setVolume 只接受 0..1，超过就抛 IllegalArgumentException。
        // 增益为正（录音偏轻的期）时只能停在 1.0，做不到放大——要放大得走 AudioProcessor。
        val v = Math.pow(10.0, ep.gainDb.toDouble() / 20.0).toFloat()
        c.volume = v.coerceIn(0.0f, 1.0f)
    }

    fun hasQueue(): Boolean = (controller?.mediaItemCount ?: 0) > 0

    fun addListener(l: Player.Listener) {
        controller?.addListener(l)
    }

    fun removeListener(l: Player.Listener) {
        controller?.removeListener(l)
    }
}
