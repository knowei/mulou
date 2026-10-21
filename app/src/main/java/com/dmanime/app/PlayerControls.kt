package com.dmanime.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.abs

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 播放器控制层 + 手势。
 *
 * 手势：单击显隐控制条、双击播放/暂停、左右拖调进度（按屏宽映射 2 分钟）、
 * 左侧上下拖亮度、右侧上下拖音量。调整时中间浮出 HUD 提示。
 */
@Composable
fun PlayerOverlay(
    player: Player,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    /** 上一集；null = 没有上一集（按钮灰掉） */
    onPrev: (() -> Unit)? = null,
    /** 下一集；null = 没有下一集 */
    onNext: (() -> Unit)? = null,
    /** 弹幕开关的当前状态；为 null 表示这个播放页没有弹幕功能 */
    onToggleDanmaku: (() -> Unit)? = null,
    danmakuOn: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val maxVolume = remember { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15 }

    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var hud by remember { mutableStateOf<String?>(null) }
    var speedMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            delay(300)
            if (!dragging) position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            isPlaying = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3_000)
            showControls = false
        }
    }

    LaunchedEffect(hud) {
        if (hud != null) {
            delay(900)
            hud = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = {
                        if (player.isPlaying) player.pause() else player.play()
                        showControls = true
                    },
                )
            }
            .pointerInput(Unit) {
                var mode = 0 // 0 未定 1 进度 2 音量 3 亮度
                var startX = 0f
                var startY = 0f
                var seekBase = 0L
                var brightnessBase = 1f

                detectDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x
                        startY = offset.y
                        seekBase = player.currentPosition
                        brightnessBase = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                        mode = 0
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val dx = change.position.x - startX
                        val dy = change.position.y - startY
                        if (mode == 0) {
                            mode = when {
                                abs(dx) > abs(dy) -> 1
                                startX < size.width / 2f -> 3
                                else -> 2
                            }
                        }
                        when (mode) {
                            1 -> {
                                val deltaMs = (dx / size.width) * 120_000f
                                dragValue = (seekBase + deltaMs).coerceIn(0f, duration.coerceAtLeast(1L).toFloat())
                                dragging = true
                                hud = "${formatClock(dragValue.toLong())} / ${formatClock(duration)}"
                            }

                            2 -> {
                                val delta = ((startY - change.position.y) / size.height) * maxVolume
                                val target = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) + delta.toInt()
                                val clamped = target.coerceIn(0, maxVolume)
                                audio?.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
                                hud = "音量 ${clamped * 100 / maxVolume}%"
                            }

                            else -> {
                                val delta = (startY - change.position.y) / size.height
                                val target = (brightnessBase + delta).coerceIn(0.05f, 1f)
                                activity?.window?.let { window ->
                                    window.attributes = window.attributes.apply { screenBrightness = target }
                                }
                                hud = "亮度 ${(target * 100).toInt()}%"
                            }
                        }
                    },
                    onDragEnd = {
                        if (mode == 1 && dragging) {
                            player.seekTo(dragValue.toLong())
                            position = dragValue.toLong()
                            dragging = false
                        }
                        mode = 0
                        hud = null
                    },
                    onDragCancel = {
                        dragging = false
                        mode = 0
                        hud = null
                    },
                )
            }
    ) {
        if (buffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(32.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }

        hud?.let {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(it, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.45f),
                            0.35f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.6f),
                        )
                    )
            ) {

                // 弹幕开关放在右上角：底部那行已经塞了进度条、集数控制、倍速和全屏，
                // 再加一个按钮会把「全屏」挤出屏幕
                if (onToggleDanmaku != null) {
                    Text(
                        if (danmakuOn) "弹幕 开" else "弹幕 关",
                        color = if (danmakuOn) Color.White else Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (danmakuOn) Color(0xFF6E8B7A).copy(alpha = 0.92f)
                                else Color.Black.copy(alpha = 0.45f)
                            )
                            .clickable {
                                onToggleDanmaku()
                                showControls = true
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                ) {
                    Slider(
                        value = if (dragging) dragValue else position.toFloat(),
                        onValueChange = { value ->
                            dragging = true
                            dragValue = value
                            showControls = true
                        },
                        onValueChangeFinished = {
                            player.seekTo(dragValue.toLong())
                            position = dragValue.toLong()
                            dragging = false
                        },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ControlButton(
                            enabled = onPrev != null,
                            size = 30.dp,
                            onClick = { onPrev?.invoke(); showControls = true },
                        ) {
                            SkipGlyph(forward = false, tint = Color.White, modifier = Modifier.size(16.dp))
                        }

                        Spacer(Modifier.size(6.dp))

                        ControlButton(
                            enabled = true,
                            size = 40.dp,
                            onClick = {
                                if (player.isPlaying) player.pause() else player.play()
                                showControls = true
                            },
                        ) {
                            PlayPauseGlyph(isPlaying, Color.White, Modifier.size(18.dp))
                        }

                        Spacer(Modifier.size(6.dp))

                        ControlButton(
                            enabled = onNext != null,
                            size = 30.dp,
                            onClick = { onNext?.invoke(); showControls = true },
                        ) {
                            SkipGlyph(forward = true, tint = Color.White, modifier = Modifier.size(16.dp))
                        }

                        Spacer(Modifier.size(10.dp))

                        Text(
                            formatClock(if (dragging) dragValue.toLong() else position),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            " / ${formatClock(duration)}",
                            color = Color.White.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        Box {
                            Text(
                                if (speed == 1f) "倍速" else "${trimSpeed(speed)}x",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.16f))
                                    .clickable {
                                        speedMenuOpen = true
                                        showControls = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                            DropdownMenu(
                                expanded = speedMenuOpen,
                                onDismissRequest = { speedMenuOpen = false },
                                containerColor = Color(0xFF23232A),
                            ) {
                                SPEEDS.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (option == 1f) "1.0x（正常）" else "${trimSpeed(option)}x",
                                                color = if (option == speed) Color(0xFFB9A6FF) else Color.White,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        },
                                        onClick = {
                                            speed = option
                                            player.setPlaybackSpeed(option)
                                            speedMenuOpen = false
                                            showControls = true
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (fullscreen) "退出全屏" else "全屏",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.16f))
                                .clickable {
                                    onToggleFullscreen()
                                    showControls = true
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (enabled) 0.42f else 0.2f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.alpha(if (enabled) 1f else 0.35f)) { content() }
    }
}

@Composable
private fun SkipGlyph(forward: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val triW = w * 0.34f
        val gap = w * 0.08f
        fun triangle(left: Float) {
            val path = Path().apply {
                if (forward) {
                    moveTo(left, 0f)
                    lineTo(left + triW, h / 2f)
                    lineTo(left, h)
                } else {
                    moveTo(left + triW, 0f)
                    lineTo(left, h / 2f)
                    lineTo(left + triW, h)
                }
                close()
            }
            drawPath(path, tint)
        }
        if (forward) {
            triangle(w * 0.06f)
            triangle(w * 0.06f + triW + gap)
        } else {
            triangle(w - triW - w * 0.06f - triW - gap)
            triangle(w - triW - w * 0.06f)
        }
    }
}

@Composable
private fun PlayPauseGlyph(isPlaying: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (isPlaying) {
            val barWidth = size.width * 0.28f
            val gap = size.width * 0.16f
            val left = (size.width - barWidth * 2 - gap) / 2f
            drawRect(tint, Offset(left, 0f), Size(barWidth, size.height))
            drawRect(tint, Offset(left + barWidth + gap, 0f), Size(barWidth, size.height))
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.12f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width * 0.12f, size.height)
                close()
            }
            drawPath(path, tint)
        }
    }
}

private fun trimSpeed(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
