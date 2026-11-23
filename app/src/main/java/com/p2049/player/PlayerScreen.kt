package com.p2049.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Dim2 = Tokens.Muted
private val Accent2 = Tokens.Primary
private val Bg2 = Tokens.Bg

private enum class Mode(val label: String) { COVER("封面"), IMAGE("画面"), LYRIC("字幕") }

/** blocks.json 里带时间的一段。 */
private data class Line(val text: String, val startMs: Long)

private fun loadLines(ep: Episode): List<Line> = runCatching {
    val root = JSONObject(ep.blocks!!.readText())
    val arr = root.getJSONArray("blocks")
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val t = o.optString("type")
        if (t == "p" || t == "h2") {
            val txt = o.optString("text")
            if (txt.isBlank()) null else Line(txt, o.optLong("start_ms", 0L))
        } else null
    }
}.getOrDefault(emptyList())

@Composable
fun PlayerScreen(
    ep: Episode?,
    playing: Boolean,
    posMs: Long,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onReader: () -> Unit,
    onNext: () -> Unit,
) {
    if (ep == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有选节目", color = Dim2)
        }
        return
    }

    // 三种模式本身也是一个横向 Pager：左右滑直接翻页
    val modePager = rememberPagerState(pageCount = { Mode.values().size })
    val scope = rememberCoroutineScope()
    var fullscreen by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().background(Bg2).safeDrawingPadding()) {
        // 顶部：返回 + 三种模式 + 阅读
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            ModeTabs(Mode.values()[modePager.currentPage]) { m ->
                scope.launch { modePager.animateScrollToPage(m.ordinal) }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onReader) {
                Icon(Icons.Filled.Subject, null, tint = Dim2)
            }
        }

        HorizontalPager(
            state = modePager,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 1
        ) { page ->
            when (Mode.values()[page]) {
                Mode.COVER -> CoverPane(ep)
                Mode.IMAGE -> ImagePane(ep, playing) { i -> fullscreen = i }
                Mode.LYRIC -> LyricPane(ep, posMs, onSeek)
            }
        }

        // 底部：进度 + 控制
        Column(Modifier.padding(horizontal = 20.dp)) {
            val dur = (ep.durationSec * 1000L).coerceAtLeast(1L)
            var dragging by remember(ep.id) { mutableStateOf(false) }
            var dragValue by remember(ep.id) { mutableStateOf(0f) }
            var seekTarget by remember(ep.id) { mutableStateOf<Long?>(null) }

            LaunchedEffect(posMs, seekTarget) {
                val t = seekTarget
                if (t != null && kotlin.math.abs(posMs - t) < 1500L) seekTarget = null
            }
            val shown = seekTarget ?: posMs

            Slider(
                value = if (dragging) dragValue else (shown.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    val target = (dragValue * dur).toLong()
                    seekTarget = target
                    onSeek(target)
                    dragging = false
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(shown), color = Dim2, fontSize = 11.sp)
                Text(fmt(dur), color = Dim2, fontSize = 11.sp)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onSeek((posMs - 15000).coerceAtLeast(0)) }) {
                    Icon(Icons.Filled.Replay, null, tint = Color.White)
                }
                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(60.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent2)
                ) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                        tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, null, tint = Color.White)
                }
            }
        }
    }

    fullscreen?.let { idx ->
        val imgs = ep.images
        if (idx in imgs.indices) ZoomDialog(imgs[idx].file) { fullscreen = null }
    }
}

@Composable
private fun ModeTabs(cur: Mode, onPick: (Mode) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF232732))
    ) {
        Mode.values().forEach { m ->
            val sel = m == cur
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Accent2 else Color.Transparent)
                    .clickable { onPick(m) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    m.label,
                    color = if (sel) Color.White else Dim2,
                    fontSize = 12.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 封面模式

@Composable
private fun CoverPane(ep: Episode) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cover = minOf(maxWidth * 0.86f, maxHeight * 0.62f)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(6.dp))
            AsyncImage(
                model = ep.cover, contentDescription = null,
                modifier = Modifier.size(cover).clip(RoundedCornerShape(18.dp))
            )
            Spacer(Modifier.height(14.dp))
            Text(ep.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(ep.oneLine.ifBlank { ep.summary }, color = Dim2, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- 画面模式

@Composable
private fun ImagePane(ep: Episode, playing: Boolean, onOpen: (Int) -> Unit) {
    if (ep.images.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("这一期没有配图", color = Dim2)
        }
        return
    }
    val pager = rememberPagerState(pageCount = { ep.images.size })
    var auto by remember(ep.id) { mutableStateOf(true) }

    // 自动轮播：key 里不能带 currentPage，否则动画途中索引一变就把协程取消，停在半页
    LaunchedEffect(auto, playing, ep.id) {
        if (!auto || !playing) return@LaunchedEffect
        while (true) {
            delay(4000)
            val next = (pager.currentPage + 1) % ep.images.size
            pager.animateScrollToPage(next)
        }
    }

    Column(Modifier.fillMaxSize()) {
        VerticalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            val pic = ep.images[page]
            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { onOpen(page) }
                ) {
                    AsyncImage(
                        model = pic.file, contentDescription = pic.caption,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pic.caption.isNotBlank()) {
                        Text(
                            pic.caption,
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(Color(0xB3000000))
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${pager.currentPage + 1} / ${ep.images.size}", color = Dim2, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text("上下滑换图", color = Dim2, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { auto = !auto }) {
                Icon(
                    if (auto) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                    null, tint = if (auto) Accent2 else Dim2, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (auto) "自动轮播" else "手动翻页", color = Dim2, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 全屏看大图：铺满屏幕、双指缩放、点一下退出。 */
@Composable
private fun ZoomDialog(img: java.io.File, onClose: () -> Unit) {
    var scale by remember(img) { mutableStateOf(1f) }
    var offset by remember(img) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,   // 关键：默认会把窗口限制成窄条
            decorFitsSystemWindows = false
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val vw = constraints.maxWidth.toFloat()
            val vh = constraints.maxHeight.toFloat()

            Box(
                Modifier.fillMaxSize()
                    // 单击退出（双指缩放不会触发 tap）
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            if (scale > 1f) {
                                val maxX = vw * (scale - 1f) / 2f
                                val maxY = vh * (scale - 1f) / 2f
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY)
                                )
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = img,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,   // 铺满屏幕，不变形
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
                )
            }

            if (scale > 1.02f) {
                Text(
                    "双指缩放 · 轻点退出",
                    color = Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 字幕模式

@Composable
private fun LyricPane(ep: Episode, posMs: Long, onSeek: (Long) -> Unit) {
    val lines = remember(ep.id) { loadLines(ep) }
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("这一期暂无文字版", color = Dim2)
        }
        return
    }
    val state = rememberLazyListState()
    var following by remember(ep.id) { mutableStateOf(true) }
    // 记下每段实际高度，居中时用来估算滚动距离（越用越准）
    val heights = remember(ep.id) { mutableStateMapOf<Int, Int>() }

    // 当前句：最后一条 startMs <= 播放位置
    val cur = remember(posMs, lines) {
        var idx = 0
        for (i in lines.indices) if (lines[i].startMs <= posMs) idx = i
        idx
    }

    // 程序滚动期间不算「用户手动滑动」
    var autoScrolling by remember(ep.id) { mutableStateOf(false) }

    LaunchedEffect(cur, following) {
        if (!following || lines.isEmpty()) return@LaunchedEffect
        autoScrolling = true
        runCatching {
            state.layoutInfo.visibleItemsInfo.forEach { heights[it.index] = it.size }
            val li = state.layoutInfo
            val vCenter = (li.viewportStartOffset + li.viewportEndOffset) / 2
            val target = li.visibleItemsInfo.firstOrNull { it.index == cur }

            if (target != null) {
                // 常见情况（顺播放往下走一段）：目标就在屏幕内，直接按距离补间，时长可控
                val delta = (target.offset + target.size / 2) - vCenter
                if (kotlin.math.abs(delta) > 4) {
                    state.animateScrollBy(
                        delta.toFloat(),
                        animationSpec = tween(480, easing = FastOutSlowInEasing)
                    )
                }
            } else {
                // 跳转很远：先定位到该段，再补一个小动画把它挪到正中
                val vh = (li.viewportEndOffset - li.viewportStartOffset).coerceAtLeast(1)
                val h = heights[cur] ?: 220
                state.animateScrollToItem(cur, scrollOffset = -(vh / 2 - h / 2))
                val li2 = state.layoutInfo
                val item = li2.visibleItemsInfo.firstOrNull { it.index == cur }
                if (item != null) {
                    val v2 = (li2.viewportStartOffset + li2.viewportEndOffset) / 2
                    val d = item.offset + item.size / 2 - v2
                    if (kotlin.math.abs(d) > 6) {
                        state.animateScrollBy(d.toFloat(), tween(320, easing = FastOutSlowInEasing))
                    }
                }
            }
        }
        autoScrolling = false
    }

    // 用户自己滑动 → 暂停跟随 5 秒
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress && !autoScrolling) {
            following = false
            delay(5000)
            following = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp)
        ) {
            items(lines.size) { i ->
                val active = i == cur
                val color by animateColorAsState(
                    if (active) Color.White else Color(0xFF6B7280),
                    animationSpec = tween(320), label = "lc$i"
                )
                val fs by animateFloatAsState(
                    if (active) 17f else 15f,
                    animationSpec = tween(320), label = "ls$i"
                )
                Text(
                    lines[i].text,
                    color = color,
                    fontSize = fs.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    lineHeight = 26.sp,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onSeek(lines[i].startMs) }
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }
        if (!following) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color(0xFF2A2F3A))
                    .clickable { following = true }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) { Text("回到当前", color = Accent2, fontSize = 12.sp) }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
