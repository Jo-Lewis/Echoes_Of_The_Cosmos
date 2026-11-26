package io.github.jolewis.echoes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 情景模式列表：内置 + 自定义，点进去改偏好。 */
@Composable
fun SceneListPage(
    scenes: List<ScenePrefs>,
    onBack: () -> Unit,
    onEdit: (ScenePrefs) -> Unit,
    onCreate: () -> Unit,
) {
    SubPage("情景模式", onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "点任意模式可以改它的偏好：单篇多长、听哪几年的、什么风格、要不要淡出。",
                    color = Tokens.Muted, fontSize = 12.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(14.dp))
            }
            items(scenes.size) { i ->
                val sc = scenes[i]
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(14.dp)).background(Tokens.Surface)
                        .clickable { onEdit(sc) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(sc.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            sc.name + if (sc.builtin) "" else "（自定义）",
                            color = Tokens.OnBg, fontSize = 14.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(Scenes.describe(sc), color = Tokens.Muted, fontSize = 10.sp)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = Tokens.Muted, modifier = Modifier.size(18.dp))
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Add, null, tint = Tokens.Primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建自定义模式", color = Tokens.Primary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** 单个模式的偏好编辑页。 */
@Composable
fun SceneEditPage(
    initial: ScenePrefs,
    isNew: Boolean,
    years: IntRange,
    onBack: () -> Unit,
    onSave: (ScenePrefs) -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
) {
    var p by remember(initial.id) { mutableStateOf(initial) }

    SubPage(if (isNew) "新建模式" else "编辑 · ${initial.name}", onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            if (!p.builtin) {
                Spacer(Modifier.height(6.dp))
                Text("名称", color = Tokens.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))
                OutlinedTextField(
                    value = p.name,
                    onValueChange = { p = p.copy(name = it.take(12)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))
            RangeRow(
                label = "单篇时长",
                lo = 5f, hi = 60f, step = 1,
                value = p.perMin.toFloat()..p.perMax.toFloat(),
                fmt = { "${it} 分钟" }
            ) { r ->
                p = p.copy(perMin = r.start.toInt(), perMax = r.endInclusive.toInt())
            }

            Spacer(Modifier.height(14.dp))
            SingleSlider(
                label = "一段大概多长",
                lo = 10f, hi = 120f, step = 5,
                value = p.totalMin.toFloat(),
                fmt = { "约 $it 分钟" }
            ) { v -> p = p.copy(totalMin = v.toInt()) }

            Spacer(Modifier.height(14.dp))
            // 年份：拉满 = 不限；yearMax=0 表示"到最新"
            val yLo = if (p.yearMin == 0) years.first else p.yearMin.coerceAtLeast(years.first)
            val yHi = if (p.yearMax == 0) years.last else p.yearMax.coerceAtMost(years.last)
            Spacer(Modifier.height(14.dp))
            RangeRow(
                label = "听哪几年的",
                lo = years.first.toFloat(),
                hi = years.last.toFloat(),
                step = 0,
                value = yLo.toFloat()..yHi.toFloat(),
                fmt = { "$it" }
            ) { r ->
                val a = r.start.toInt()
                val b = r.endInclusive.toInt()
                p = if (a <= years.first && b >= years.last) p.copy(yearMin = 0, yearMax = 0)
                else p.copy(yearMin = a, yearMax = b)
            }
            Text(
                when {
                    p.yearMin == 0 && p.yearMax == 0 -> "不限年份"
                    p.yearMin > 0 && p.yearMax == 0 -> "${p.yearMin} 年之后"
                    else -> "${p.yearMin} - ${p.yearMax} 年"
                },
                color = Tokens.Primary, fontSize = 10.sp,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp)
            )

            if (p.id == "sleep" || !p.builtin) {
            Spacer(Modifier.height(18.dp))
            Text("淡出", color = Tokens.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            SingleSlider(
                label = "从第几分钟开始淡出",
                lo = 0f, hi = 90f, step = 5,
                value = p.fadeStartMin.toFloat(),
                fmt = { if (it == 0) "不淡出" else "第 $it 分钟" }
            ) { v -> p = p.copy(fadeStartMin = v.toInt()) }

            Spacer(Modifier.height(10.dp))
            SingleSlider(
                label = "淡出持续多久",
                lo = 5f, hi = 30f, step = 5,
                value = p.fadeLenMin.coerceAtLeast(5).toFloat(),
                fmt = { "$it 分钟" }
            ) { v -> p = p.copy(fadeLenMin = v.toInt()) }

            Spacer(Modifier.height(10.dp))
            SingleSlider(
                label = "静音后多久自动停",
                lo = 0f, hi = 30f, step = 5,
                value = p.idleStopMin.toFloat(),
                fmt = { if (it == 0) "不停" else "$it 分钟无操作" }
            ) { v -> p = p.copy(idleStopMin = v.toInt()) }

            Text(
                "到 0 之后按音量键会重新出声，再从头计一轮。",
                color = Tokens.Muted, fontSize = 10.sp
            )
            }

            Spacer(Modifier.height(4.dp))
            MultiChips("风格（可多选，不选=不限）", Scenes.tones, p.tones) { t ->
                p = p.copy(tones = if (t in p.tones) p.tones - t else p.tones + t)
            }
            MultiChips("类型（可多选，不选=不限）", Scenes.types, p.contentTypes) { t ->
                p = p.copy(contentTypes = if (t in p.contentTypes) p.contentTypes - t else p.contentTypes + t)
            }

            ChipSection("趣味度下限", listOf("不限", "3", "4", "5"), listOf(0, 3, 4, 5).indexOf(p.humorMin)) { i ->
                p = p.copy(humorMin = listOf(0, 3, 4, 5)[i])
            }
            ChipSection("动脑上限", listOf("2", "3", "4", "5"), listOf(2, 3, 4, 5).indexOf(p.loadMax)) { i ->
                p = p.copy(loadMax = listOf(2, 3, 4, 5)[i])
            }
            ChipSection("门槛上限", listOf("入门", "中等", "进阶"), listOf(1, 2, 3).indexOf(p.levelMax)) { i ->
                p = p.copy(levelMax = listOf(1, 2, 3)[i])
            }

            ChipSection("只要睡前友好的", listOf("是", "否"), if (p.sleepOnly) 0 else 1) { i ->
                p = p.copy(sleepOnly = i == 0)
            }
            ChipSection("最后多久淡出", listOf("不淡出", "3 分", "5 分", "8 分", "15 分"),
                listOf(0, 3, 5, 8, 15).indexOf(p.fadeMin)) { i ->
                p = p.copy(fadeMin = listOf(0, 3, 5, 8, 15)[i])
            }
            ChipSection("出几期", listOf("自动", "只要一期", "多期"), listOf("auto", "one", "many").indexOf(p.countMode)) { i ->
                p = p.copy(countMode = listOf("auto", "one", "many")[i])
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onSave(p) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tokens.Primary)
                ) { Text("保存", color = Tokens.Bg, fontWeight = FontWeight.Bold) }
                if (p.builtin) {
                    OutlinedButton(onClick = onReset, shape = RoundedCornerShape(12.dp)) {
                        Text("恢复默认", color = Tokens.Primary, fontSize = 13.sp)
                    }
                } else {
                    OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Filled.Delete, null, tint = Tokens.Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除", color = Tokens.Accent, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun RangeRow(
    label: String,
    lo: Float,
    hi: Float,
    step: Int,
    value: ClosedFloatingPointRange<Float>,
    fmt: (Int) -> String,
    onChange: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    Text(label, color = Tokens.Muted, fontSize = 11.sp)
    Text(
        "${fmt(value.start.toInt())} - ${fmt(value.endInclusive.toInt())}",
        color = Tokens.OnBg, fontSize = 13.sp, fontWeight = FontWeight.Medium
    )
    val haptic = LocalHapticFeedback.current
    var tick by remember { mutableStateOf(-1) }
    RangeSlider(
        value = value,
        onValueChange = { v ->
            onChange(v)
            val t = v.start.toInt() / 3
            if (t != tick) { tick = t; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        },
        valueRange = lo..hi,
        steps = if (step > 1) ((hi - lo).toInt() / step) - 1 else 0,
        colors = SliderDefaults.colors(
            thumbColor = Tokens.Primary,
            activeTrackColor = Tokens.Primary,
            inactiveTrackColor = Tokens.Elevated,
        )
    )
}

@Composable
private fun SingleSlider(
    label: String,
    lo: Float,
    hi: Float,
    step: Int,
    value: Float,
    fmt: (Int) -> String,
    onChange: (Float) -> Unit,
) {
    Text(label, color = Tokens.Muted, fontSize = 11.sp)
    Text(fmt(value.toInt()), color = Tokens.OnBg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val haptic = LocalHapticFeedback.current
    var tick by remember { mutableStateOf(-1) }
    Slider(
        value = value,
        onValueChange = { v ->
            onChange(v)
            val t = v.toInt() / 3
            if (t != tick) { tick = t; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        },
        valueRange = lo..hi,
        steps = if (step > 1) ((hi - lo).toInt() / step) - 1 else 0,
        colors = SliderDefaults.colors(
            thumbColor = Tokens.Primary,
            activeTrackColor = Tokens.Primary,
            inactiveTrackColor = Tokens.Elevated,
        )
    )
}

@Composable
private fun ChipSection(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(label, color = Tokens.Muted, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i ->
            val sel = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Tokens.Primary else Tokens.Surface)
                    .clickable { onPick(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    options[i],
                    color = if (sel) Tokens.Bg else Tokens.OnBg,
                    fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun MultiChips(label: String, options: List<String>, selected: List<String>, onToggle: (String) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(label, color = Tokens.Muted, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i ->
            val t = options[i]
            val sel = t in selected
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Tokens.Primary else Tokens.Surface)
                    .clickable { onToggle(t) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    t,
                    color = if (sel) Tokens.Bg else Tokens.OnBg,
                    fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
