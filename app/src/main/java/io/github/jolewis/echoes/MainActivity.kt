package io.github.jolewis.echoes

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
        setContent { EchoesTheme { EchoesApp(this) } }
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
fun EchoesApp(activity: ComponentActivity) {
    val box = remember { PlayerBox(activity) }
    val prefs = remember {
        Profile.restore(activity)                 // 重装后从媒体库恢复进度/收藏
        activity.getSharedPreferences("echoes", 0)
    }
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
    // 支持 am start --es q ... 直接进入点播（也方便以后做分享/快捷方式）
    var searchQuery by remember { mutableStateOf<String?>(activity.intent?.getStringExtra("q")) }
    var llmCfg by remember { mutableStateOf(LlmConfig.load(activity)) }
    val searchState = remember { SearchState() }
    var lastDump by remember { mutableStateOf(0L) }
    val queue = remember { QueueState.load(activity) }
    var queueSheet by remember { mutableStateOf(false) }
    var scene by remember { mutableStateOf<ScenePrefs?>(null) }
    var scenes by remember { mutableStateOf(Scenes.load(activity)) }
    var sceneEdit by remember { mutableStateOf<ScenePrefs?>(null) }
    var sceneIsNew by remember { mutableStateOf(false) }
    var sceneRemainMin by remember { mutableStateOf(0) }
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

            // 情景模式：按队列剩余时长做线性淡出，播完自动停
            val sc = scene
            if (sc != null) {
                var remain = 0L
                for (k in queue.index until queue.ids.size) {
                    val e = episodes.firstOrNull { it.id == queue.ids[k] } ?: continue
                    remain += e.durationSec * 1000L
                }
                remain = (remain - posMs).coerceAtLeast(0L)
                sceneRemainMin = (remain / 60_000L).toInt()
                if (sc.fadeMin > 0) {
                    val fadeMs = sc.fadeMin * 60_000L
                    Volume.setFadeScene(box.controller, (remain.toFloat() / fadeMs).coerceIn(0f, 1f))
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "echoes",
                            "scene=" + sc.name + " remain=" + (remain / 1000) + "s vol=" + Volume.effective
                        )
                    }
                }
                if (remain <= 0L) {
                    box.controller?.pause()
                    Volume.setFadeScene(box.controller, 1f)
                    scene = null
                    android.widget.Toast.makeText(activity, "${sc.name}模式结束，晚安", android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            if (System.currentTimeMillis() - lastDump > 60_000L) {
                Profile.dump(activity)
                lastDump = System.currentTimeMillis()
            }
            delay(1000)
        }
    }

    // 退出/切后台时落盘，并释放播放器连接（否则 Media3 会在退出时重复 unbind 崩溃）
    DisposableEffect(Unit) {
        onDispose {
            Profile.dump(activity)
            runCatching { box.release() }
        }
    }

    LaunchedEffect(tab) {
        if (tab == 2) recs = withContext(Dispatchers.IO) { ListenLog.load(activity) }
    }

    // 打开播放页时先把这一期 prepare 到上次的位置（不自动播），进度条直接显示正确位置
    LaunchedEffect(playerOpen, current?.id) {
        if (!playerOpen) return@LaunchedEffect
        val ep = current ?: return@LaunchedEffect
        box.connect {
            val loadedId = box.controller?.currentMediaItem?.mediaId?.substringBefore('#')
            if (loadedId != ep.id) {
                val saved = prefs.getLong("pos_" + ep.id, 0L)
                val durMs = ep.durationSec * 1000L
                val resume = if (saved > 5000L && saved < durMs - 10000L) saved else 0L
                box.loadEpisode(ep, resume, autoPlay = false)
            }
        }
    }

    LaunchedEffect(sleepUntil) {
        if (sleepUntil <= 0L) return@LaunchedEffect
        while (true) {
            if (sleepUntil - System.currentTimeMillis() <= 0L) {
                val c = box.controller
                repeat(30) { i -> Volume.setFadeSleep(c, 1f - (i + 1) / 30f); delay(1000) }
                c?.pause()
                Volume.setFadeSleep(c, 1f)
                tracker.onIdle()
                sleepUntil = 0L; sleepMin = 0
                break
            }
            delay(1000)
        }
    }

    BackHandler(
        enabled = playerOpen || detail != null || sceneEdit != null ||
            minePage != null || searchQuery != null || tab != 0
    ) {
        when {
            playerOpen -> playerOpen = false
            detail != null -> detail = null
            sceneEdit != null -> sceneEdit = null        // 编辑页 → 回到模式列表
            minePage != null -> minePage = null
            searchQuery != null -> searchQuery = null
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

    // 播放队列里第 i 项
    val playAt: (Int) -> Unit = { i ->
        val ep = queue.ids.getOrNull(i)?.let { id -> episodes.firstOrNull { it.id == id } }
        if (ep != null) {
            queue.index = i
            QueueState.save(activity, queue)
            current = ep
            prefs.edit().putString("last", ep.id).apply()
            box.connect { ensureLoaded(ep); playerOpen = true }
        }
    }

    // 用一批节目替换队列并从第 0 项开始播
    val playAll: (List<Episode>) -> Unit = { list ->
        if (list.isNotEmpty()) {
            queue.replace(list.map { it.id }, 0)
            QueueState.save(activity, queue)
            playAt(0)
        }
    }

    // 单集播放：已在队列里就跳过去，否则用它替换队列
    val startPlay: (Episode) -> Unit = { ep ->
        val i = queue.ids.indexOf(ep.id)
        if (i >= 0) playAt(i)
        else { queue.replace(listOf(ep.id), 0); QueueState.save(activity, queue); playAt(0) }
    }

    // 一键情景：本地按偏好挑一批 → 填队列 → 从头播
    val startScene: (ScenePrefs) -> Unit = { sc ->
        val list = Scenes.pick(activity, sc, episodes)
        if (list.isEmpty()) {
            android.widget.Toast.makeText(
                activity, "没找到符合「" + sc.name + "」条件的节目，去设置里放宽一点试试",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            scene = sc
            Volume.setFadeScene(box.controller, 1f)
            playAll(list)
            val mins = list.sumOf { it.durationSec } / 60
            android.widget.Toast.makeText(
                activity, sc.name + " · " + list.size + " 期 · 约 " + mins + " 分钟",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 下一期 / 上一期（到边界给提示，而不是毫无反应）
    val playNext: () -> Unit = {
        if (queue.hasNext()) playAt(queue.index + 1)
        else android.widget.Toast.makeText(activity, "已经是最后一期了", android.widget.Toast.LENGTH_SHORT).show()
    }
    val playPrev: () -> Unit = {
        if (queue.index > 0) playAt(queue.index - 1)
        else android.widget.Toast.makeText(activity, "已经是第一期了", android.widget.Toast.LENGTH_SHORT).show()
    }

    // 一期播完 → 自动下一期
    val onEnded by rememberUpdatedState(playNext)
    DisposableEffect(box.controller) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) onEnded()
            }
        }
        box.addListener(l)
        onDispose { box.removeListener(l) }
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
            sceneName = scene?.name,
            sceneRemainMin = sceneRemainMin,
            onExitScene = {
                scene = null
                Volume.setFadeScene(box.controller, 1f)
            },
            onQueue = { queueSheet = true },
            onPrev = playPrev,
            onNext = playNext,
        )
        if (queueSheet) {
            QueueSheet(
                episodes = episodes,
                qs = queue,
                onDismiss = { queueSheet = false },
                onPlayAt = { i -> playAt(i); queueSheet = false },
                onRemove = { i -> queue.removeAt(i); QueueState.save(activity, queue) },
                onClear = { queue.clear(); QueueState.save(activity, queue) },
            )
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
            onEnqueue = { queue.enqueue(it.id); QueueState.save(activity, queue) },
        )
        return
    }

    searchQuery?.let { q ->
        SearchScreen(
            initialQuery = q,
            state = searchState,
            episodes = episodes,
            cfg = llmCfg,
            onBack = { searchQuery = null },
            onPlay = { startPlay(it) },
            onPlayQueue = { list -> playAll(list) },
            onDetail = { detail = it },
            onOpenSettings = { searchQuery = null; tab = 2; minePage = "ai" },
        )
        return
    }

    sceneEdit?.let { sc ->
        SceneEditPage(
            initial = sc,
            isNew = sceneIsNew,
            years = run {
                val ys = episodes.map { it.year }.filter { it in 1990..2100 }
                if (ys.isEmpty()) 2016..2026 else ys.min()..ys.max()
            },
            onBack = { sceneEdit = null },
            onSave = { updated ->
                Scenes.upsert(activity, updated)
                scenes = Scenes.load(activity)
                if (scene?.id == updated.id) scene = updated
                sceneEdit = null
                android.widget.Toast.makeText(activity, "已保存", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                Scenes.remove(activity, sc.id)
                scenes = Scenes.load(activity)
                sceneEdit = null
            },
            onReset = {
                Scenes.resetOne(activity, sc.id)
                scenes = Scenes.load(activity)
                sceneEdit = null
            },
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
            "ai" -> AiSettingsPage(llmCfg, { c -> c.save(activity); llmCfg = c }) { minePage = null }
            "scenes" -> SceneListPage(
                scenes,
                onBack = { minePage = null },
                onEdit = { sceneIsNew = false; sceneEdit = it },
                onCreate = {
                    sceneIsNew = true
                    sceneEdit = ScenePrefs(
                        id = "custom_" + System.currentTimeMillis(),
                        name = "我的模式",
                        emoji = "⭐",
                        builtin = false,
                    )
                },
            )
        }
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
                    episodes, recs, current, llmCfg.ready,
                    scenes = scenes,
                    activeScene = scene,
                    sceneRemainMin = sceneRemainMin,
                    onPlay = { startPlay(it) },
                    onDetail = { detail = it },
                    onSearch = { q -> searchState.finishedFor = null; searchQuery = q },
                    onScene = { startScene(it) },
                    onExitScene = {
                        scene = null
                        Volume.setFadeScene(box.controller, 1f)
                        android.widget.Toast.makeText(activity, "已退出情景模式", android.widget.Toast.LENGTH_SHORT).show()
                    },
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
                    episodes, recs, favs, sleepMin, llmCfg.ready, scenes.size,
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
