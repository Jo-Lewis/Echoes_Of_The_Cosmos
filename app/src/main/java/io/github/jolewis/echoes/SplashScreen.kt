package io.github.jolewis.echoes

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 启动页：等媒体库加载时显示。
 *
 * 停留时间由 MainActivity 控制（最少 900ms，避免一闪而过）。
 */
@Composable
fun SplashScreen(count: Int = 0, hours: Int = 0, status: String = "") {
    val trans = rememberInfiniteTransition(label = "splash")
    val glow by trans.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    val dot by trans.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot"
    )

    Box(
        Modifier.fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Tokens.Surface, Tokens.Bg, Tokens.Bg),
                    center = Offset(540f, 700f),
                    radius = 1500f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // 中央：星环 + 品牌词
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(132.dp)
                        .alpha(glow * 0.55f)
                        .border(1.dp, Tokens.Primary.copy(alpha = 0.55f), CircleShape)
                )
                Box(
                    Modifier.size(96.dp)
                        .alpha(glow * 0.9f)
                        .border(1.dp, Tokens.Accent.copy(alpha = 0.45f), CircleShape)
                )
                Box(
                    Modifier.size(10.dp)
                        .alpha(glow)
                        .background(Tokens.Primary, CircleShape)
                )
            }

            Spacer(Modifier.height(34.dp))

            Text(
                "星河回响",
                color = Tokens.OnBg,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 10.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "在声音里，遇见宇宙",
                color = Tokens.Primary,
                fontSize = 13.sp,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "ECHOES OF THE COSMOS",
                color = Tokens.Muted.copy(alpha = 0.55f),
                fontSize = 9.sp,
                letterSpacing = 3.sp
            )
        }

        // 底部：状态
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i ->
                    Box(
                        Modifier.size(5.dp)
                            .alpha(if (i == 1) dot else dot * 0.45f)
                            .background(Tokens.Primary, CircleShape)
                    )
                    if (i < 2) Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                status.ifBlank {
                    if (count > 0) "正在整理 $count 期节目" else "正在整理节目单"
                },
                color = Tokens.Muted, fontSize = 11.sp, letterSpacing = 1.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "把三千期科学，装进口袋",
                color = Tokens.Muted.copy(alpha = 0.6f), fontSize = 10.sp
            )
        }
    }
}

/** 媒体库缺失时的提示页，跟启动页同一套视觉。 */
@Composable
fun LibraryMissingScreen() {
    Box(
        Modifier.fillMaxSize().background(Tokens.Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("找不到媒体库", color = Tokens.OnBg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "把库放到 /sdcard/2049lib/ 之后重启应用",
                color = Tokens.Muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "目录结构：shows/2049/<年>/<日期 标题>/",
                color = Tokens.Muted.copy(alpha = 0.7f), fontSize = 11.sp
            )
        }
    }
}
