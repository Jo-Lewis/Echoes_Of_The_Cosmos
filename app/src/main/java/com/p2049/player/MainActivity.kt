package com.p2049.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        setContent { P2049Theme { App2049(this) } }
    }

    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(filesDir, "crash-" + System.currentTimeMillis() + ".txt")
                    .writeText(e.stackTraceToString())
            }
            prev?.uncaughtException(t, e)
        }
    }
}

@Composable
fun App2049(activity: ComponentActivity) {
    val box = remember { PlayerBox(activity) }
    val prefs = remember { activity.getSharedPreferences("p2049", 0) }
    val tracker = remember { PlayTracker(activity) }

    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var recs by remember { mutableStateOf<List<ListenRec>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) }
    var playerOpen by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<Episode?>(null) }
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0L) }
    var sleepUntil by remember { mutableStateOf(0L) }
    var sleepMin by remember { mutableStateOf(0) }
    var libQuery by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Episode?>(null) }
    var minePage by remember { mutableStateOf<String?>(null) }
    var favs by remember { mutableStateOf(prefs.getStringSet("favs", emptySet())!!.toSet()) }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { Library.loadCached(activity) }
        episodes = cached
        recs = withContext(Dispatchers.IO) { ListenLog.load(activity) }
        loading = false
        current = cached.firstOrNull { it.id == prefs.getString("last", null) }
        box.connect { }

        val fresh = withContext(Dispatchers.IO) { Library.load(activity) }
        if (fresh.size != cached.size) {
            episodes = fresh
            LibraryCache.mem = fresh
            withContext(Dispatchers.IO) { runCatching { Library.saveCache(activity, fresh) } }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            box.controller?.let {
                posMs = it.currentPosition
                playing = it.isPlaying
            }
            delay(500)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val c = box.controller
            val ep = current
            if (c != null && ep != null && c.isPlaying) {
                tracker.onTick(ep.id, ep.title, c.currentPosition, ep.durationSec.toLong())
                prefs.edit().putLong("pos_" + ep.id, c.currentPosition).apply()
            } else tracker.onIdle()
            delay(1000)
        }
    }

    LaunchedEffect(tab) {
        if (tab == 2) recs = withContext(Dispatchers.IO) { ListenLog.load(activity) }
    }

    LaunchedEffect(sleepUntil) {
        if (sleepUntil <= 0L) return@LaunchedEffect
        while (true) {
            if (sleepUntil - System.currentTimeMillis() <= 0L) {
                val c = box.controller
                val v0 = c?.volume ?: 1f
                repeat(30) { i -> c?.volume = v0 * (1f - (i + 1) / 30f); delay(1000) }
                c?.pause()
                c?.volume = v0
                tracker.onIdle()
                sleepUntil = 0L; sleepMin = 0
                break
            }
            delay(1000)
        }
    }

    BackHandler(enabled = playerOpen || detail != null || minePage != null || tab != 0) {
        when {
            playerOpen -> playerOpen = false
            detail != null -> detail = null
            minePage != null -> minePage = null
            else -> tab = 0
        }
    }

    val ensureLoaded: (Episode) -> Unit = { ep ->
        val playingId = box.controller?.currentMediaItem?.mediaId?.substringBefore('#')
        if (playingId != ep.id) {
            val saved = prefs.getLong("pos_" + ep.id, 0L)
            val durMs = ep.durationSec * 1000L
            val resume = if (saved > 5000L && saved < durMs - 10000L) saved else 0L
            box.playEpisode(ep, resume)
        }
    }

    val togglePlay: () -> Unit = {
        val c = box.controller
        val ep = current
        when {
            c == null -> Unit
            playing -> c.pause()
            box.hasQueue() -> c.play()
            ep != null -> ensureLoaded(ep)
        }
    }

    val startPlay: (Episode) -> Unit = { ep ->
        current = ep
        prefs.edit().putString("last", ep.id).apply()
        box.connect { ensureLoaded(ep); playerOpen = true }
    }

    if (loading) {
        Box(Modifier.fillMaxSize().background(Tokens.Bg), contentAlignment = Alignment.Center) {
            Text("正在加载 3,000 多期…", color = Tokens.Muted)
        }
        return
    }
    if (episodes.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Tokens.Bg), contentAlignment = Alignment.Center) {
            Text("没有找到媒体库\n把库推到 /sdcard/2049lib/ 后重启", color = Tokens.Muted)
        }
        return
    }

    if (playerOpen) {
        PlayerScreen(
            current, playing, posMs,
            onToggle = togglePlay,
            onSeek = { box.controller?.seekTo(it) },
            onBack = { playerOpen = false },
            onReader = { },
            onNext = { episodes.randomOrNull()?.let { startPlay(it) } },
        )
        return
    }

    minePage?.let { page ->
        when (page) {
            "stats" -> StatsPage(episodes, recs) { minePage = null }
            "favs" -> FavoritesPage(episodes, favs, { minePage = null }) { detail = it }
            "history" -> HistoryPage(episodes, recs, { minePage = null }) { detail = it }
            "settings" -> SettingsPage(sleepMin, { m ->
                sleepMin = m
                sleepUntil = if (m == 0) 0L else System.currentTimeMillis() + m * 60_000L
            }) { minePage = null }
            "library" -> LibraryPage(episodes) { minePage = null }
            "about" -> AboutPage { minePage = null }
        }
        return
    }

    detail?.let { d ->
        EpisodeDetailScreen(
            ep = d, all = episodes,
            progressSec = (prefs.getLong("pos_" + d.id, 0L)) / 1000,
            favorite = favs.contains(d.id),
            onBack = { detail = null },
            onPlay = { startPlay(d) },
            onToggleFavorite = {
                favs = if (favs.contains(d.id)) favs - d.id else favs + d.id
                prefs.edit().putStringSet("favs", favs).apply()
            },
            onOpen = { detail = it },
        )
        return
    }

    Scaffold(
        containerColor = Tokens.Bg,
        bottomBar = {
            Column {
                current?.let {
                    MiniPlayer(it, playing, posMs, onToggle = togglePlay, onOpen = { playerOpen = true })
                }
                NavigationBar(containerColor = Tokens.Surface, tonalElevation = 0.dp) {
                    NavItem("听什么", Icons.Filled.GraphicEq, tab == 0) { tab = 0 }
                    NavItem("节目库", Icons.Filled.LibraryMusic, tab == 1) { tab = 1 }
                    NavItem("我的", Icons.Filled.Person, tab == 2) { tab = 2 }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> HomeScreen(
                    episodes, recs, current,
                    onPlay = { startPlay(it) },
                    onDetail = { detail = it },
                    onOpenPlayer = { playerOpen = true },
                    onGoLibrary = { q -> libQuery = q; tab = 1 },
                )
                1 -> LibraryScreen(
                    episodes, recs, libQuery,
                    onPlay = { startPlay(it) },
                    onDetail = { detail = it },
                    onQueryChange = { libQuery = it },
                )
                else -> MineScreen(
                    episodes, recs, favs, sleepMin,
                    onOpenPage = { minePage = it },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    sel: Boolean,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = sel,
        onClick = onClick,
        icon = { Icon(icon, null) },
        label = { Text(label, fontSize = 11.sp) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Tokens.Primary,
            selectedTextColor = Tokens.Primary,
            indicatorColor = Tokens.Elevated,
            unselectedIconColor = Tokens.Muted,
            unselectedTextColor = Tokens.Muted,
        )
    )
}

@Composable
fun MiniPlayer(ep: Episode, playing: Boolean, posMs: Long, onToggle: () -> Unit, onOpen: () -> Unit) {
    val dur = (ep.durationSec * 1000L).coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().background(Tokens.Elevated).clickable { onOpen() }) {
        LinearProgressIndicator(
            progress = { (posMs.toFloat() / dur).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Tokens.Primary,
            trackColor = Tokens.Bg
        )
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(ep, 42, 10)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ep.title, color = Tokens.OnBg, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("${fmtClock(posMs)} / ${fmtClock(dur)}", color = Tokens.Muted, fontSize = 11.sp)
            }
            IconButton(onClick = onToggle) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Tokens.Primary)
            }
        }
    }
}
