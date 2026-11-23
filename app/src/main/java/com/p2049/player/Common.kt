package com.p2049.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File

/** 90 分钟 → "1 小时 30 分"。 */
fun fmtShort(sec: Long): String {
    if (sec < 60) return "$sec 秒"
    val m = sec / 60
    return if (m < 60) "$m 分钟" else "${m / 60} 小时 ${m % 60} 分"
}

fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun CoverImage(ep: Episode, size: Int, radius: Int = 12) {
    val f: File? = ep.cover
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)).background(Tokens.Elevated),
        contentAlignment = Alignment.Center
    ) {
        if (f != null && f.exists()) {
            AsyncImage(model = f, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Text("${ep.year}".takeLast(2), color = Tokens.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun EpisodeRow(ep: Episode, progressSec: Long = 0, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(ep, 56)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, color = Tokens.OnBg, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("${ep.minutes} 分钟")
                    if (ep.contentType.isNotBlank()) append(" · ${ep.contentType}")
                    if (ep.tone.isNotBlank()) append(" · ${ep.tone}")
                    if (progressSec > 0) append(" · 已听 ${fmtShort(progressSec)}")
                },
                color = Tokens.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ChipRow(options: List<String>, selected: String, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onPick(o) },
                label = { Text(o, fontSize = 12.sp) },
                modifier = Modifier.height(32.dp)
            )
        }
    }
}

@Composable
fun SectionTitle(
    text: String,
    mod: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        mod.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Tokens.OnBg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun CardBox(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        mod.clip(RoundedCornerShape(18.dp)).background(Tokens.Surface).padding(16.dp),
        content = content
    )
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Tokens.Muted, fontSize = 13.sp)
    }
}

@Composable
fun ColorDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(50)).background(color))
}
