package com.dmanime.app.danmaku

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.dmanime.danmaku.Danmaku
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 弹幕层。叠在 PlayerView 之上、控制层之下。
 *
 * 播放位置直接读 [androidx.media3.common.Player.currentPosition]，不经过 ViewModel ——
 * 弹幕要跟到帧，每秒回传状态会让整页重组几十次。
 * 每帧用 withFrameNanos 推一次 tick，Canvas 的 draw lambda 读它只让绘制阶段失效。
 */
@Composable
fun DanmakuLayer(
    player: androidx.media3.common.Player?,
    comments: List<Danmaku>,
    enabled: Boolean,
    alpha: Float,
    textScale: Float,
    offsetMs: Long,
    area: Float = 0.62f,
    speedFactor: Float = 1f,
    modifier: Modifier = Modifier,
) {
    if (!enabled || comments.isEmpty() || player == null || alpha <= 0.01f) return

    val density = LocalDensity.current.density
    var tick by remember { mutableLongStateOf(0L) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { tick = it }
        }
    }

    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isSubpixelText = true
        }
    }

    val plan = remember(comments, size.width, size.height, textScale, area, speedFactor) {
        if (size.width == 0 || size.height == 0) {
            LanePlan(emptyList(), emptyList(), 0f, 1f, 0f)
        } else {
            buildPlan(comments, size.width.toFloat(), size.height.toFloat(), textScale, area, speedFactor, paint)
        }
    }

    val clampedAlpha = alpha.coerceIn(0f, 1f)
    val alphaPx = (clampedAlpha * 255).roundToInt()

    Canvas(modifier = modifier.fillMaxSize().onSizeChanged { size = it }) {
        // 读一次 tick：让绘制跟随每一帧失效，但不触发重组
        val frame = tick
        if (frame < 0L) return@Canvas

        val textSize = plan.textSizePx
        if (textSize <= 0f) return@Canvas
        paint.textSize = textSize
        val position = player.currentPosition + offsetMs
        if (position < 0) return@Canvas
        val canvas = drawContext.canvas.nativeCanvas

        // 白字在亮画面上会糊成一团，所以先描一圈半透明黑边再填色（B 站就是这么干的）。
        // 两次 drawText 的开销可以忽略：同屏最多几十条。
        val strokeHalf = 1.6f * density
        fun paintText(text: String, x: Float, baseline: Float, color: Int) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeHalf * 2f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = OUTLINE_COLOR
            paint.alpha = alphaPx
            canvas.drawText(text, x, baseline, paint)
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = alphaPx
            canvas.drawText(text, x, baseline, paint)
        }

        for (line in plan.scroll) {
            val elapsed = position - line.enterMs
            if (elapsed < 0 || elapsed > line.durationMs) continue
            paintText(line.item.text, plan.width - elapsed * plan.speedPxPerMs, line.baseline, line.item.color)
        }

        for (line in plan.fixed) {
            val elapsed = position - line.enterMs
            if (elapsed < 0 || elapsed > FIXED_DURATION_MS) continue
            paintText(line.item.text, (plan.width - line.widthPx) / 2f, line.baseline, line.item.color)
        }
    }
}

// ---------------- 轨道排布 ----------------

private class Line(
    val item: Danmaku,
    val enterMs: Long,
    val durationMs: Long,
    val widthPx: Float,
    val baseline: Float,
)

private class LanePlan(
    val scroll: List<Line>,
    val fixed: List<Line>,
    val width: Float,
    val speedPxPerMs: Float,
    val textSizePx: Float,
)

private const val FIXED_DURATION_MS = 4_000L
private const val SCROLL_DURATION_MS = 9_000L
private const val LANE_GAP_PX = 8f
private const val OUTLINE_COLOR = -1073741824 // 0xC0000000：75% 黑，描边用

/**
 * 预排轨道。只依赖屏幕尺寸、字号和弹幕文本，播放过程中不重算。
 *
 * 滚动弹幕的速度统一：快慢不一的话同一条轨道上一定会追尾。
 * 同速时只要「上一条的尾巴已经完全进屏」，后面那条就永远追不上它，
 * 所以轨道是否可用只需要判断这一个条件。
 */
private fun buildPlan(
    comments: List<Danmaku>,
    width: Float,
    height: Float,
    textScale: Float,
    area: Float,
    speedFactor: Float,
    paint: Paint,
): LanePlan {
    val textSize = min(width / 26f, 60f) * textScale.coerceIn(0.6f, 2f)
    if (textSize <= 1f) return LanePlan(emptyList(), emptyList(), width, 1f, 0f)

    paint.textSize = textSize
    val lineHeight = textSize * 1.45f
    // 顶部留一行给播放器的「弹幕 开」按钮，别让第一条弹幕压在它下面
    val topInset = lineHeight
    // 弹幕只铺画面上部：铺满整屏会盖住字幕和人脸
    val usableHeight = height * area.coerceIn(0.2f, 1f)
    val laneCount = max(1, ((usableHeight - topInset) / lineHeight).toInt())
    // 轨道分池：顶部固定弹幕吃最上面一条，剩下给滚动（顶部固定弹幕在 B 站占比很低，
    // 留两条会让首屏空一行）；底部固定弹幕走 0.62h 以下那块空白，和滚动区不重叠。
    val topLanes = if (laneCount >= 6) 1 else 0
    // 底部固定弹幕落在弹幕区下方那块空白里；铺满整个画面时就没地方给它了，直接不出
    val bottomLanes = if (laneCount >= 6 && area < 0.9f) 2 else 0
    val scrollLanes = max(1, laneCount - topLanes)
    val speed = (width + 960f) / (SCROLL_DURATION_MS / speedFactor.coerceIn(0.5f, 2f))

    val scroll = ArrayList<Line>(comments.size)
    val fixed = ArrayList<Line>()
    val scrollEnter = LongArray(laneCount) { Long.MIN_VALUE }
    val scrollWidth = FloatArray(laneCount) { 0f }
    val topFree = LongArray(laneCount) { Long.MIN_VALUE }
    val bottomFree = LongArray(laneCount) { Long.MIN_VALUE }
    var nextTop = 0
    var nextBottom = 0

    val widthCache = HashMap<String, Float>()
    fun measure(text: String): Float = widthCache.getOrPut(text) { paint.measureText(text) }

    for (item in comments) {
        val textWidth = measure(item.text)
        when (item.mode) {
            Danmaku.Mode.Scroll -> {
                if (textWidth >= width) continue // 比屏幕还长的弹幕会糊满一整行，丢掉
                var lane = -1
                for (k in 0 until scrollLanes) {
                    val i = topLanes + k
                    val enter = scrollEnter[i]
                    if (enter == Long.MIN_VALUE) {
                        lane = i
                        break
                    }
                    val gap = item.timeMs - enter
                    if (gap >= 0 && gap * speed >= scrollWidth[i] + LANE_GAP_PX) {
                        lane = i
                        break
                    }
                }
                if (lane < 0) continue // 这一瞬所有轨道都挤，丢掉比叠字好看
                scrollEnter[lane] = item.timeMs
                scrollWidth[lane] = textWidth
                scroll += Line(
                    item = item,
                    enterMs = item.timeMs,
                    durationMs = ((width + textWidth) / speed).toLong(),
                    widthPx = textWidth,
                    baseline = lineHeight * lane + textSize + topInset,
                )
            }

            Danmaku.Mode.Top, Danmaku.Mode.Bottom -> {
                val isTop = item.mode == Danmaku.Mode.Top
                val laneMax = if (isTop) topLanes else bottomLanes
                if (laneMax == 0) continue // 屏幕太矮，固定弹幕直接不出
                val book = if (isTop) topFree else bottomFree
                var lane = if (isTop) nextTop else nextBottom
                var attempts = 0
                while (attempts < laneMax) {
                    val freeAt = book[lane]
                    if (freeAt == Long.MIN_VALUE || item.timeMs - freeAt >= FIXED_DURATION_MS) break
                    lane = (lane + 1) % laneMax
                    attempts++
                }
                if (attempts >= laneMax) continue
                book[lane] = item.timeMs
                if (isTop) nextTop = lane else nextBottom = lane
                fixed += Line(
                    item = item,
                    enterMs = item.timeMs,
                    durationMs = FIXED_DURATION_MS,
                    widthPx = textWidth,
                    baseline = if (isTop) {
                        lineHeight * lane + textSize + topInset
                    } else {
                        // 底部弹幕抬到字幕上方，压在字幕上就没法看了
                        height - lineHeight * lane - textSize * 1.8f
                    },
                )
            }
        }
    }

    return LanePlan(scroll, fixed, width, speed, textSize)
}
