package com.vibetype.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetype.AckState
import com.vibetype.MainViewModel
import com.vibetype.ModelState
import com.vibetype.Transcript
import com.vibetype.VibeBlue
import com.vibetype.VibeCoral
import com.vibetype.VibeFaint
import com.vibetype.VibeGreen
import com.vibetype.VibeInk
import com.vibetype.VibeMuted
import com.vibetype.net.ConnState
import kotlinx.coroutines.delay
import kotlin.math.abs

private val PadTop = Color(0xFF1D1E24)
private val PadBottom = Color(0xFF111216)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    vm: MainViewModel,
    conn: ConnState,
    model: ModelState,
    recording: Boolean,
    busy: Boolean,
    level: Float,
    partial: String,
    last: Transcript?,
    notice: String?,
) {
    val connected = conn is ConnState.Connected
    val ready = model is ModelState.Ready
    var showSettings by remember { mutableStateOf(false) }
    var showCapsules by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(recording) {
        seconds = 0
        while (recording) {
            delay(1_000)
            seconds += 1
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionPill(conn)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showSettings = true }) {
                Text("设置", fontSize = 12.sp, color = VibeMuted)
            }
        }

        Trackpad(
            connected = connected,
            recording = recording,
            busy = busy,
            level = level,
            partial = partial,
            last = last,
            seconds = seconds,
            notice = notice,
            onMove = vm::sendMove,
            onScroll = vm::sendScroll,
            onClick = vm::sendClick,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp),
        )

        Dock(
            enabled = connected && ready && !busy,
            recording = recording,
            onVoiceStart = vm::beginTalk,
            onVoiceEnd = vm::endTalk,
            onVoiceCancel = vm::cancelTalk,
            onKey = vm::sendKey,
            onCapsule = { showCapsules = true },
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )
    }

    if (showSettings) {
        SettingsSheet(
            conn = conn,
            onDismiss = { showSettings = false },
            onUnpair = {
                showSettings = false
                vm.unpair()
            },
        )
    }
    if (showCapsules) {
        CapsuleSheet(onDismiss = { showCapsules = false })
    }
}

@Composable
private fun ConnectionPill(conn: ConnState) {
    val (color, label) = when (conn) {
        ConnState.Idle -> VibeFaint to "未连接"
        ConnState.Connecting -> Color(0xFFFF9F0A) to "连接中"
        is ConnState.Connected -> VibeGreen to "已连接"
        is ConnState.Failed -> VibeCoral to "未连接"
        is ConnState.NeedsPairing -> VibeCoral to "未配对"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = VibeMuted)
    }
}

@Composable
private fun Trackpad(
    connected: Boolean,
    recording: Boolean,
    busy: Boolean,
    level: Float,
    partial: String,
    last: Transcript?,
    seconds: Int,
    notice: String?,
    onMove: (Float, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val edgePx = with(LocalDensity.current) { 26.dp.toPx() }
    val slop = with(LocalDensity.current) { 10.dp.toPx() }

    Box(
        modifier
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(PadTop, PadBottom)))
            .then(
                if (connected) {
                    Modifier.pointerInput(Unit) {
                        trackpadGestures(density, edgePx, slop, onMove, onScroll, onClick)
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // 右缘滚动条：常驻淡显，让用户摸得到
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 7.dp)
                .width(3.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.10f))
        )

        when {
            !connected -> DisconnectedOverlay()
            recording -> RecordingOverlay(level, seconds, partial)
            busy -> BusyOverlay()
            last != null -> ResultToast(last)
        }

        if (connected && !recording && !busy) {
            Text(
                text = "轻点左键 · 双指滚动 · 右缘滚动",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.28f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
        }

        if (notice != null) {
            Text(
                text = notice,
                fontSize = 12.sp,
                color = Color(0xFFFFB4A2),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 42.dp),
            )
        }
    }
}

private suspend fun PointerInputScope.trackpadGestures(
    density: Float,
    edgePx: Float,
    slop: Float,
    onMove: (Float, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val started = System.currentTimeMillis()
        val edge = down.position.x > size.width - edgePx
        var last = down.position
        var total = Offset.Zero
        var maxPointers = 1
        var lastTwo: Offset? = null
        var twoFingerMoved = false

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            maxPointers = maxOf(maxPointers, pressed.size)
            val centroid = pressed.fold(Offset.Zero) { acc, change -> acc + change.position } /
                pressed.size.toFloat()

            if (pressed.size >= 2) {
                val previous = lastTwo
                if (previous != null) {
                    val dy = centroid.y - previous.y
                    if (abs(dy) > 0.8f) {
                        onScroll(0f, -dy / density * 0.85f)
                        twoFingerMoved = true
                    }
                }
                lastTwo = centroid
            } else {
                val delta = centroid - last
                total += delta
                last = centroid
                if (total.getDistance() > slop) {
                    if (edge) {
                        onScroll(0f, -delta.y / density * 1.2f)
                    } else {
                        onMove(delta.x / density * 1.35f, delta.y / density * 1.35f)
                    }
                }
            }
            event.changes.forEach { it.consume() }
        }

        val duration = System.currentTimeMillis() - started
        if (maxPointers >= 2) {
            if (!twoFingerMoved && total.getDistance() < slop && duration < 400) {
                onClick("right")
            }
        } else if (total.getDistance() < slop && duration < 400) {
            onClick("left")
        }
    }
}

@Composable
private fun BoxScopeDisconnected() = Unit

@Composable
private fun DisconnectedOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99090A0E)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("先连上电脑", fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(5.dp))
            Text(
                "配对之后，触控板和按键才有用",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.42f),
            )
        }
    }
}

@Composable
private fun RecordingOverlay(level: Float, seconds: Int, partial: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99090A0E)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(210.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val base = size.minDimension / 2f
                for (i in 0..2) {
                    drawCircle(
                        color = VibeCoral.copy(alpha = (0.10f - i * 0.025f) * (0.5f + level)),
                        radius = base * (0.55f + 0.11f * i + level * 0.06f),
                        center = center,
                    )
                }
                drawCircle(color = VibeCoral, radius = base * 0.42f, center = center)
                val barWidth = size.width * 0.024f
                val gap = size.width * 0.022f
                val heights = floatArrayOf(0.16f, 0.28f, 0.38f, 0.25f, 0.14f)
                var x = center.x - (barWidth * 5 + gap * 4) / 2f
                heights.forEach { height ->
                    val half = size.height * height * (0.7f + level * 0.45f)
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(x, center.y - half),
                        size = androidx.compose.ui.geometry.Size(barWidth, half * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2),
                    )
                    x += barWidth + gap
                }
            }
            Text(
                text = "%02d:%02d".format(seconds / 60, seconds % 60),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text("松开结束", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            if (partial.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = partial,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 26.dp),
                )
            }
        }
    }
}

@Composable
private fun BusyOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99090A0E)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = VibeBlue,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text("转写中…", fontSize = 13.sp, color = VibeInk)
        }
    }
}

@Composable
private fun ResultToast(transcript: Transcript) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    when (transcript.ack) {
                        AckState.Delivered -> VibeGreen.copy(alpha = 0.15f)
                        AckState.Failed -> VibeCoral.copy(alpha = 0.14f)
                        AckState.Pending -> VibeBlue.copy(alpha = 0.12f)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when (transcript.ack) {
                    AckState.Delivered -> "✓"
                    AckState.Failed -> "!"
                    AckState.Pending -> "…"
                },
                fontSize = 12.sp,
                color = when (transcript.ack) {
                    AckState.Delivered -> VibeGreen
                    AckState.Failed -> VibeCoral
                    AckState.Pending -> VibeBlue
                },
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = transcript.text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = VibeInk,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Dock(
    enabled: Boolean,
    recording: Boolean,
    onVoiceStart: () -> Unit,
    onVoiceEnd: () -> Unit,
    onVoiceCancel: () -> Unit,
    onKey: (String) -> Unit,
    onCapsule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceKey(enabled, recording, onVoiceStart, onVoiceEnd, onVoiceCancel)
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton("胶囊", enabled, Modifier.weight(1f)) { if (enabled) onCapsule() }
                KeyButton("回车", enabled, Modifier.weight(1f)) { if (enabled) onKey("enter") }
                KeyButton("删除", enabled, Modifier.weight(1f)) { if (enabled) onKey("delete") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton("esc", enabled, Modifier.weight(1f)) { if (enabled) onKey("esc") }
                KeyButton("切换", enabled, Modifier.weight(1f)) { if (enabled) onKey("switch") }
                KeyButton("自定义", enabled, Modifier.weight(1f)) { if (enabled) onKey("custom") }
            }
        }
    }
}

@Composable
private fun VoiceKey(
    enabled: Boolean,
    recording: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val background = when {
        !enabled -> Brush.linearGradient(listOf(Color(0xFFB9C4D0), Color(0xFF98A3B0)))
        pressed || recording -> Brush.linearGradient(listOf(Color(0xFFFF9A76), VibeCoral))
        else -> Brush.linearGradient(listOf(Color(0xFF4DA3FF), VibeBlue))
    }

    Box(
        Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(background)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(onPress = {
                        pressed = true
                        onStart()
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) onEnd() else onCancel()
                    })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(40.dp, 32.dp)) {
                val heights = floatArrayOf(0.34f, 0.58f, 0.82f, 0.5f, 0.3f)
                val barWidth = size.width * 0.08f
                val gap = size.width * 0.1f
                var x = (size.width - (barWidth * 5 + gap * 4)) / 2f
                heights.forEach { weight ->
                    val half = size.height * weight / 2f
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(x, size.height / 2f - half),
                        size = androidx.compose.ui.geometry.Size(barWidth, half * 2f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2),
                    )
                    x += barWidth + gap
                }
            }
            Spacer(Modifier.height(5.dp))
            Text("按住说话", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = if (label.length > 2) 11.sp else 12.sp,
            color = if (enabled) VibeInk else VibeFaint,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(conn: ConnState, onDismiss: () -> Unit, onUnpair: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        ) {
            Text("设置", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = VibeInk)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("当前电脑", fontSize = 13.sp, color = VibeMuted)
                Spacer(Modifier.weight(1f))
                Text(
                    when (conn) {
                        is ConnState.Connected ->
                            if (conn.platform == "macos") "Mac" else "Windows"
                        else -> "未连接"
                    },
                    fontSize = 13.sp,
                    color = VibeFaint,
                )
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onUnpair, modifier = Modifier.fillMaxWidth()) {
                Text("换电脑 / 解除配对", fontSize = 13.sp, color = VibeCoral)
            }
            Spacer(Modifier.height(4.dp))
            Text("VibeType 0.1.0 · X-ASR", fontSize = 11.sp, color = VibeFaint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapsuleSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("闪念胶囊", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = VibeInk)
            Spacer(Modifier.height(18.dp))
            Text(
                "还没有胶囊。胶囊录制正在接入，先用语音键把话打进电脑。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = VibeMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
