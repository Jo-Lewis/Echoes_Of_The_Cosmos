package io.github.jolewis.echoes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    /** 音量键回调：睡眠模式淡到 0 后，按音量键要能重新出声。 */
    var volumeKeyHandler: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            runCatching { volumeKeyHandler?.invoke() }
        }
        return super.dispatchKeyEvent(event)
    }

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
    // 启动页至少停 2 秒；这 2 秒里顺带做全量校验
    var splashMin by remember { mutableStateOf(false) }
    var freshReady by remember { mutableStateOf(false) }
    var splashTimeout by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var playerOpen by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<Episode?>(null) }
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0L) }
    var sleepUntil by remember { mutableStateOf(0L) }
    var sleepMin by remember { mutableStateOf(0) }
    var libQuery by remember { mutableStateOf(activity.intent?.getStringExtra("libq") ?: "") }
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
    // 场景开始时间（elapsedRealtime），用于「第 N 分钟开始淡出」
    var sceneStartedAt by remember { mutableStateOf(0L) }
    // 淡到 0 的时刻，用于「N 分钟无操作就停」
    var sceneZeroAt by remember { mutableStateOf(0L) }
    var favs by remember { mutableStateOf(prefs.getStringSet("favs", emptySet())!!.toSet()) }

    // 唤醒 = 延长收听：音量回到原值，淡出重新计 10 分钟（由后台服务执行）
    fun wakeScene() {
        if (scene != null) SleepEngine.extend(10)
    }

    DisposableEffect(Unit) {
        val act = activity as? MainActivity
        act?.volumeKeyHandler = { wakeScene() }
        onDispose { act?.volumeKeyHandler = null }
    }

    // 屏幕关掉时 Activity 拿不到按键，用系统音量变化兜底
    LaunchedEffect(scene?.id) {
        val sc = scene ?: return@LaunchedEffect
        val am = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        var lastVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                if (v != lastVol) {
                    lastVol = v
                    wakeScene()
                }
            }
        }
        activity.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, obs
        )
        kotlinx.coroutines.awaitCancellation()
        activity.contentResolver.unregisterContentObserver(obs)
    }

    // 媒体库目录放一个 .nomedia：否则 2 万多张封面/配图会被系统相册收录
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val root = Library.root(activity)
                if (root.isDirectory) File(root, ".nomedia").takeIf { !it.exists() }?.createNewFile()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!activity.intent?.getStringExtra("libq").isNullOrBlank()) tab = 1
        val t0 = android.os.SystemClock.elapsedRealtime()
        val cached = withContext(Dispatchers.IO) { Library.loadCached(activity) }
        val t1 = android.os.SystemClock.elapsedRealtime()
        episodes = cached
        if (BuildConfig.DEBUG) {
            android.util.Log.d("echoes", "perf cacheLoad=" + (t1 - t0) + "ms n=" + cached.size)
        }
        recs = withContext(Dispatchers.IO) { ListenLog.load(activity) }
        loading = false
        current = cached.firstOrNull { it.id == prefs.getString("last", null) }
        box.connect { }

        val t2 = android.os.SystemClock.elapsedRealtime()
        val fresh = withContext(Dispatchers.IO) { Library.load(activity) }
        val t3 = android.os.SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) {
            android.util.Log.d("echoes", "perf fullScan=" + (t3 - t2) + "ms n=" + fresh.size)
        }
        if (fresh.size != cached.size) {
            episodes = fresh
            LibraryCache.mem = fresh
            withContext(Dispatchers.IO) { runCatching { Library.saveCache(activity, fresh) } }
        }
        freshReady = true
    }

    // 预热检索字段（标题/简介/关键词的归一化文本），首次搜索就不用现算了
    LaunchedEffect(episodes) {
        if (episodes.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            episodes.forEach { e ->
                e.titleN; e.metaN; e.keyN
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "echoes",
                    "perf warmup=" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms"
                )
            }
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

            // 淡出/定时/自动暂停全部由 PlaybackService 里的 SleepEngine 负责，
            // 这里只做界面同步（锁屏或退出界面也不影响播放侧的计时）。
            val sc = scene
            if (sc != null) {
                sceneRemainMin = if (SleepEngine.fadeEnabled) SleepEngine.remainMin else {
                    // 不淡出的模式：显示队列剩余时长
                    var remain = 0L
                    for (k in queue.index until queue.ids.size) {
                        val e = episodes.firstOrNull { it.id == queue.ids[k] } ?: continue
                        remain += e.durationSec * 1000L
                    }
                    ((remain - posMs).coerceAtLeast(0L) / 60_000L).toInt()
                }
                if (!SleepEngine.active) {
                    scene = null
                    sceneZeroAt = 0L
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
                val saved = prefs.longOf("pos_" + ep.id, 0L)
                val durMs = ep.durationSec * 1000L
                val resume = if (saved > 5000L && saved < durMs - 10000L) saved else 0L
                box.loadEpisode(ep, resume, autoPlay = false)
            }
        }
    }

    LaunchedEffect(sleepUntil) {
        SleepEngine.setStopAt(sleepUntil)
        if (sleepUntil <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < sleepUntil) delay(1000)
        sleepUntil = 0L
        sleepMin = 0
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
            val saved = prefs.longOf("pos_" + ep.id, 0L)
            val durMs = ep.durationSec * 1000L
            // 上次是听着睡着的：从停下的前 5 分钟接着放（书签用一次就清掉）
            val bm = prefs.longOf("bm_" + ep.id, -1L)
            val resume = when {
                bm >= 0L && bm < durMs - 5000L -> {
                    prefs.edit().remove("bm_" + ep.id).apply()
                    android.widget.Toast.makeText(
                        activity, "上次听到这里附近，从 ${bm / 60000} 分 ${(bm / 1000) % 60} 秒接着放",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    bm
                }
                saved > 5000L && saved < durMs - 10000L -> saved
                else -> 0L
            }
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

    // 一键情景：先挑好、给用户看一眼，确认后再播
    var scenePreview by remember { mutableStateOf<List<Episode>?>(null) }
    var scenePreviewPrefs by remember { mutableStateOf<ScenePrefs?>(null) }
    // 列表内容可能与上一批完全相同，用轮次强制刷新界面
    var previewRound by remember { mutableIntStateOf(0) }

    val startScene: (ScenePrefs, Set<String>) -> Unit = { sc, excl ->
        // 听过的（有记录，或进度超过 5 秒）不再推给情景模式
        val listened = recs.map { it.id }.toSet() + prefs.all.keys
            .filter { it.startsWith("pos_") && (prefs.all[it] as? Long ?: 0L) > 5000L }
            .map { it.removePrefix("pos_") }
        val list = Scenes.pick(activity, sc, episodes, excl, listened)
        if (list.isEmpty()) {
            android.widget.Toast.makeText(
                activity, "没找到符合「" + sc.name + "」条件的节目，去设置里放宽一点试试",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            scenePreviewPrefs = sc
            scenePreview = list
            previewRound += 1
        }
    }

    val confirmScene: (ScenePrefs, List<Episode>) -> Unit = { sc, list ->
        scene = sc
        SleepEngine.start(sc.name, sc.fadeStartMin, sc.fadeLenMin, sc.idleStopMin)
        Volume.setFadeScene(box.controller, 1f)
        playAll(list)
        val mins = list.sumOf { it.durationSec } / 60
        android.widget.Toast.makeText(
            activity, sc.name + " · " + list.size + " 期 · 约 " + mins + " 分钟",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        scenePreview = null
        scenePreviewPrefs = null
    }

    // 从「段落命中」直接播：起播后跳到那一段
    val scope0 = rememberCoroutineScope()
    val playSegment: (Episode, Long) -> Unit = { ep, ms ->
        startPlay(ep)
        scope0.launch {
            repeat(60) {
                delay(100)
                val c = box.controller
                if (c != null && c.currentMediaItem?.mediaId?.substringBefore('#') == ep.id) {
                    c.seekTo(ms)
                    c.play()
                    return@launch
                }
            }
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


    // 启动页：最少停 2 秒；这 2 秒内把缓存读取和全量校验都做完
    LaunchedEffect(Unit) {
        delay(2000)
        splashMin = true
    }
    // 兜底：库特别大时也不能一直卡在启动页
    LaunchedEffect(Unit) {
        delay(6000)
        splashTimeout = true
    }
    if (loading || !splashMin || (!freshReady && !splashTimeout)) {
        val status = when {
            loading -> "正在读取节目库"
            !freshReady -> "正在核对节目单"
            else -> ""
        }
        // 加载过程中期数还不准，等读完再显示
        SplashScreen(if (loading) 0 else episodes.size,
            (episodes.sumOf { it.durationSec } / 3600), status)
        return
    }
    if (episodes.isEmpty()) {
        LibraryMissingScreen()
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
            sceneFade = SleepEngine.fadeEnabled,
            onExitScene = {
                SleepEngine.cancel()
                scene = null
            },
            onExtendScene = { SleepEngine.extend(10) },
            onFadeNow = { SleepEngine.fadeNow(5) },
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
            progressSec = (prefs.longOf("pos_" + d.id, 0L)) / 1000,
            favorite = favs.contains(d.id),
            onBack = { detail = null },
            onPlay = { startPlay(d) },
            onToggleFavorite = {
                val on = !favs.contains(d.id)
                favs = if (on) favs + d.id else favs - d.id
                prefs.edit().putStringSet("favs", favs).apply()
                android.widget.Toast.makeText(
                    activity, if (on) "已收藏" else "已取消收藏", android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onOpen = { detail = it },
            onPlayCollection = { name ->
                val list = episodes.filter { it.collection == name }.sortedBy { it.collSeq }
                if (list.isNotEmpty()) {
                    playAll(list)
                    android.widget.Toast.makeText(
                        activity, "按顺序播放「" + name + "」共 " + list.size + " 期",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onEnqueue = {
                val before = queue.ids.size
                queue.enqueue(it.id)
                QueueState.save(activity, queue)
                android.widget.Toast.makeText(
                    activity,
                    if (queue.ids.size > before) "已加入队列" else "已经在队列里了",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
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
            onPlaySegment = playSegment,
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
            "collections" -> CollectionsPage(
                episodes,
                onBack = { minePage = null },
                onOpenEpisode = { minePage = null; detail = it },
                onPlayAll = { list -> minePage = null; playAll(list) },
            )
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

    // 情景模式预览：先看列表，再决定播不播
    scenePreview?.let { list ->
        val sc = scenePreviewPrefs
        AlertDialog(
            onDismissRequest = { scenePreview = null; scenePreviewPrefs = null },
            title = {
                Column {
                    Text(sc?.name?.let { "$it · 准备好了" } ?: "准备好了",
                        color = Tokens.OnBg)
                    Text(
                        "${list.size} 期 · 共 ${list.sumOf { it.durationSec } / 60} 分钟" +
                            (sc?.let {
                                if (it.fadeStartMin > 0) " · 第 ${it.fadeStartMin} 分钟起淡出" else ""
                            } ?: ""),
                        color = Tokens.Muted, fontSize = 11.sp
                    )
                }
            },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list) { ep ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CoverImage(ep, 40, 10)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ep.title, color = Tokens.OnBg, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${ep.minutes} 分钟" + if (ep.inCollection) " · ${ep.collection} 第 ${ep.collSeq} 期" else "",
                                    color = Tokens.Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val s2 = sc ?: return@TextButton
                    confirmScene(s2, list)
                }) { Text("开始播放", color = Tokens.Primary) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val cur = sc
                        if (cur != null) {
                            // 换一批：把当前这批排除掉，保证换出来的一定不一样
                            startScene(cur, list.map { it.id }.toSet())
                            android.widget.Toast.makeText(
                                activity, "已换一批", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) { Text("换一批", color = Tokens.Muted) }
                    TextButton(onClick = { scenePreview = null; scenePreviewPrefs = null }) {
                        Text("取消", color = Tokens.Muted)
                    }
                }
            },
            containerColor = Tokens.Surface
        )
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
                    onScene = { startScene(it, emptySet()) },
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
            onOpenCollections = { minePage = "collections" },
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
