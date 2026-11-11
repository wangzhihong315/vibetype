package com.vibetype.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.vibetype.capsule.Capsule
import com.vibetype.net.ConnState
import com.vibetype.net.KeySlot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    var showGestures by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }

    val capsules by vm.capsules.collectAsState()
    val capsuleRecording by vm.capsuleRecording.collectAsState()
    val capsulePartial by vm.capsulePartial.collectAsState()
    val capsuleSaving by vm.capsuleSaving.collectAsState()
    val leftHanded by vm.leftHanded.collectAsState()
    val pointerSpeed by vm.pointerSpeed.collectAsState()
    val naturalScroll by vm.naturalScroll.collectAsState()
    val keymap by vm.keymap.collectAsState()

    // 第一次连上之后自动弹一次手势说明
    LaunchedEffect(Unit) {
        if (!vm.gesturesSeen()) {
            delay(900)
            showGestures = true
        }
    }

    var flying by remember { mutableStateOf<Capsule?>(null) }
    LaunchedEffect(Unit) {
        vm.capsuleSaved.collect { flying = it }
    }

    // 结果卡只是确认，5 秒后收起；失败时留着让用户重试
    LaunchedEffect(last) {
        if (last != null && last.ack != AckState.Failed) {
            delay(5_000)
            vm.clearLast()
        }
    }

    val recordingNow = recording || capsuleRecording
    LaunchedEffect(recordingNow) {
        seconds = 0
        while (recordingNow) {
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
            capsuleRecording = capsuleRecording,
            capsuleSaving = capsuleSaving,
            busy = busy,
            level = level,
            partial = if (capsuleRecording) capsulePartial else partial,
            last = last,
            seconds = seconds,
            notice = notice,
            flying = flying,
            leftHanded = leftHanded,
            pointerSpeed = pointerSpeed,
            naturalScroll = naturalScroll,
            onFlightDone = { flying = null },
            onMove = vm::sendMove,
            onScroll = vm::sendScroll,
            onClick = vm::sendClick,
            onDown = vm::sendPointerDown,
            onUp = vm::sendPointerUp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp),
        )

        Dock(
            enabled = connected && ready && !busy,
            capsuleEnabled = ready && !busy && !recording,
            recording = recording,
            capsuleRecording = capsuleRecording,
            leftHanded = leftHanded,
            keymap = keymap,
            onVoiceStart = vm::beginTalk,
            onVoiceEnd = vm::endTalk,
            onVoiceCancel = vm::cancelTalk,
            onKey = vm::sendKey,
            onCapsuleTap = { showCapsules = true },
            onCapsuleStart = vm::beginCapsule,
            onCapsuleEnd = vm::endCapsule,
            onCapsuleCancel = vm::cancelCapsule,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )
    }

    if (showSettings) {
        SettingsSheet(
            conn = conn,
            leftHanded = leftHanded,
            pointerSpeed = pointerSpeed,
            naturalScroll = naturalScroll,
            debuggable = vm.debuggable,
            onDismiss = { showSettings = false },
            onSelfTest = {
                showSettings = false
                vm.selfTest()
            },
            onToggleLeftHanded = vm::setLeftHanded,
            onSpeedChange = vm::setPointerSpeed,
            onToggleNatural = vm::setNaturalScroll,
            onShowGestures = {
                showSettings = false
                showGestures = true
            },
            onUnpair = {
                showSettings = false
                vm.unpair()
            },
        )
    }
    if (showGestures) {
        GestureSheet(onDismiss = {
            showGestures = false
            vm.markGesturesSeen()
        })
    }
    if (showCapsules) {
        CapsuleSheet(
            items = capsules,
            onDismiss = { showCapsules = false },
            onCopy = vm::copyCapsule,
            onSend = vm::sendCapsuleToDesktop,
            onDelete = vm::deleteCapsule,
            onRename = vm::renameCapsule,
        )
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
    capsuleRecording: Boolean,
    capsuleSaving: Boolean,
    busy: Boolean,
    level: Float,
    partial: String,
    last: Transcript?,
    seconds: Int,
    notice: String?,
    flying: Capsule?,
    leftHanded: Boolean,
    pointerSpeed: Float,
    naturalScroll: Boolean,
    onFlightDone: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    onDown: () -> Unit,
    onUp: () -> Unit,
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
                    Modifier.pointerInput(leftHanded, pointerSpeed, naturalScroll) {
                        trackpadGestures(
                            density, edgePx, slop, leftHanded,
                            pointerSpeed, naturalScroll,
                            onMove, onScroll, onClick, onDown, onUp,
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // 滚动条贴着惯用手那一侧，常驻淡显，让用户摸得到
        Box(
            Modifier
                .align(if (leftHanded) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(start = if (leftHanded) 7.dp else 0.dp, end = if (leftHanded) 0.dp else 7.dp)
                .width(3.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.10f))
        )

        when {
            !connected && !capsuleRecording -> DisconnectedOverlay()
            capsuleRecording -> RecordingOverlay(
                level = level,
                seconds = seconds,
                partial = partial,
                label = "松开保存，自动写进胶囊",
                capsule = true,
            )
            recording -> RecordingOverlay(
                level = level,
                seconds = seconds,
                partial = partial,
                label = "松开结束，文字会打进电脑光标",
                capsule = false,
            )
            capsuleSaving -> BusyOverlay(label = "存进胶囊…")
            busy -> BusyOverlay(label = "转写中…")
            last != null -> ResultToast(last)
        }

        if (connected && !recording && !capsuleRecording && !busy && last == null) {
            Text(
                text = if (leftHanded) "轻点左键 · 双指滚动 · 左缘滚动" else "轻点左键 · 双指滚动 · 右缘滚动",
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

        if (flying != null) {
            CapsuleFlight(capsule = flying, onDone = onFlightDone)
        }
    }
}

/**
 * 存下胶囊时的那一下：白色卡片从中间缩着飞向左下的胶囊键。
 * 只是视觉确认，飞完就消失，不留状态。
 */
@Composable
private fun BoxScope.CapsuleFlight(capsule: Capsule, onDone: () -> Unit) {
    val progress = remember(capsule.id) { Animatable(0f) }
    LaunchedEffect(capsule.id) {
        progress.animateTo(1f, tween(durationMillis = 680, easing = FastOutSlowInEasing))
        delay(60)
        onDone()
    }

    Box(
        Modifier
            .align(Alignment.Center)
            .widthIn(max = 280.dp)
            .graphicsLayer {
                val p = progress.value
                translationX = -size.width * 0.55f * p
                translationY = size.height * 2.4f * p
                val scale = 1f - 0.62f * p
                scaleX = scale
                scaleY = scale
                alpha = 1f - p * p
            }
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(VibeCoral.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(VibeCoral)
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text("已存进胶囊", fontSize = 11.sp, color = VibeCoral)
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = capsule.text,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = VibeInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private suspend fun PointerInputScope.trackpadGestures(
    density: Float,
    edgePx: Float,
    slop: Float,
    leftHanded: Boolean,
    speed: Float,
    natural: Boolean,
    onMove: (Float, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val scrollSign = if (natural) 1f else -1f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val started = System.currentTimeMillis()
        val edge = if (leftHanded) {
            down.position.x < edgePx
        } else {
            down.position.x > size.width - edgePx
        }
        var last = down.position
        var total = Offset.Zero
        var maxPointers = 1
        var lastTwo: Offset? = null
        var twoFingerMoved = false
        var dragging = false

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
                        onScroll(0f, -dy / density * 0.85f * speed * scrollSign)
                        twoFingerMoved = true
                    }
                }
                lastTwo = centroid
            } else {
                val delta = centroid - last
                total += delta
                last = centroid

                // 停住不动 350ms 就当作长按：按下左键，后面的移动变成拖拽
                val held = System.currentTimeMillis() - started
                if (!dragging && !edge && maxPointers == 1 && held > 350 &&
                    total.getDistance() < slop
                ) {
                    dragging = true
                    onDown()
                }

                if (dragging) {
                    onMove(
                        delta.x / density * 1.35f * speed,
                        delta.y / density * 1.35f * speed,
                    )
                } else if (total.getDistance() > slop) {
                    if (edge) {
                        onScroll(0f, -delta.y / density * 1.2f * speed * scrollSign)
                    } else {
                        onMove(
                        delta.x / density * 1.35f * speed,
                        delta.y / density * 1.35f * speed,
                    )
                    }
                }
            }
            event.changes.forEach { it.consume() }
        }

        if (dragging) {
            onUp()
            return@awaitEachGesture
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
private fun RecordingOverlay(
    level: Float,
    seconds: Int,
    partial: String,
    label: String,
    capsule: Boolean,
) {
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
                if (capsule) {
                    // 胶囊是一条竖着的药丸，和圆形语音键区分开
                    val pillWidth = size.width * 0.40f
                    val pillHeight = size.height * 0.72f
                    for (i in 0..2) {
                        val grow = 1f + 0.10f * i + level * 0.05f
                        val w = pillWidth * grow
                        val h = pillHeight * grow
                        val radius = CornerRadius(h / 2f, h / 2f)
                        drawRoundRect(
                            color = VibeCoral.copy(alpha = (0.12f - i * 0.03f) * (0.5f + level)),
                            topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = radius,
                        )
                    }
                    drawRoundRect(
                        color = VibeCoral,
                        topLeft = Offset(center.x - pillWidth / 2f, center.y - pillHeight / 2f),
                        size = androidx.compose.ui.geometry.Size(pillWidth, pillHeight),
                        cornerRadius = CornerRadius(pillHeight / 2f, pillHeight / 2f),
                    )
                    drawBars(center, level, barHeight = size.height * 0.34f)
                } else {
                    for (i in 0..2) {
                        drawCircle(
                            color = VibeCoral.copy(alpha = (0.10f - i * 0.025f) * (0.5f + level)),
                            radius = base * (0.55f + 0.11f * i + level * 0.06f),
                            center = center,
                        )
                    }
                    drawCircle(color = VibeCoral, radius = base * 0.42f, center = center)
                    drawBars(center, level, barHeight = size.height * 0.30f)
                }
            }
            Text(
                text = "%02d:%02d".format(seconds / 60, seconds % 60),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
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

/** 五根竖条，跟着实时音量抖。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(
    center: Offset,
    level: Float,
    barHeight: Float,
) {
    val barWidth = size.width * 0.024f
    val gap = size.width * 0.022f
    val heights = floatArrayOf(0.52f, 0.9f, 1.18f, 0.8f, 0.46f)
    var x = center.x - (barWidth * 5 + gap * 4) / 2f
    heights.forEach { height ->
        val half = barHeight * height * (0.7f + level * 0.45f) / 2f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(x, center.y - half),
            size = androidx.compose.ui.geometry.Size(barWidth, half * 2),
            cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
        )
        x += barWidth + gap
    }
}

@Composable
private fun BusyOverlay(label: String) {
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
            Text(label, fontSize = 13.sp, color = VibeInk)
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
    capsuleEnabled: Boolean,
    recording: Boolean,
    capsuleRecording: Boolean,
    leftHanded: Boolean,
    keymap: List<KeySlot>,
    onVoiceStart: () -> Unit,
    onVoiceEnd: () -> Unit,
    onVoiceCancel: () -> Unit,
    onKey: (String) -> Unit,
    onCapsuleTap: () -> Unit,
    onCapsuleStart: () -> Unit,
    onCapsuleEnd: () -> Unit,
    onCapsuleCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leftHanded) {
            DockKeys(
                enabled = enabled,
                capsuleEnabled = capsuleEnabled,
                recording = recording,
                capsuleRecording = capsuleRecording,
                keymap = keymap,
                onKey = onKey,
                onCapsuleTap = onCapsuleTap,
                onCapsuleStart = onCapsuleStart,
                onCapsuleEnd = onCapsuleEnd,
                onCapsuleCancel = onCapsuleCancel,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(14.dp))
            VoiceKey(
                enabled = enabled && !capsuleRecording,
                recording = recording,
                onStart = onVoiceStart,
                onEnd = onVoiceEnd,
                onCancel = onVoiceCancel,
            )
        } else {
            VoiceKey(
                enabled = enabled && !capsuleRecording,
                recording = recording,
                onStart = onVoiceStart,
                onEnd = onVoiceEnd,
                onCancel = onVoiceCancel,
            )
            Spacer(Modifier.width(14.dp))
            DockKeys(
                enabled = enabled,
                capsuleEnabled = capsuleEnabled,
                recording = recording,
                capsuleRecording = capsuleRecording,
                keymap = keymap,
                onKey = onKey,
                onCapsuleTap = onCapsuleTap,
                onCapsuleStart = onCapsuleStart,
                onCapsuleEnd = onCapsuleEnd,
                onCapsuleCancel = onCapsuleCancel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DockKeys(
    enabled: Boolean,
    capsuleEnabled: Boolean,
    recording: Boolean,
    capsuleRecording: Boolean,
    keymap: List<KeySlot>,
    onKey: (String) -> Unit,
    onCapsuleTap: () -> Unit,
    onCapsuleStart: () -> Unit,
    onCapsuleEnd: () -> Unit,
    onCapsuleCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val keys = keymap.ifEmpty {
            listOf(
                KeySlot("k1", "回车"),
                KeySlot("k2", "删除"),
                KeySlot("k3", "esc"),
                KeySlot("k4", "切换"),
                KeySlot("k5", "自定义"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapsuleKey(
                enabled = capsuleEnabled && !recording,
                recording = capsuleRecording,
                onTap = onCapsuleTap,
                onStart = onCapsuleStart,
                onEnd = onCapsuleEnd,
                onCancel = onCapsuleCancel,
                modifier = Modifier.weight(1f),
            )
            keys.take(2).forEach { slot ->
                KeyButton(slot.label, enabled, Modifier.weight(1f)) {
                    if (enabled) onKey(slot.id)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            keys.drop(2).take(3).forEach { slot ->
                KeyButton(slot.label, enabled, Modifier.weight(1f)) {
                    if (enabled) onKey(slot.id)
                }
            }
        }
    }
}

/**
 * 胶囊键：轻点开列表，按住录一条。
 * 按下先等 240ms 再开麦克风，避免轻点时白录一段。
 */
@Composable
private fun CapsuleKey(
    enabled: Boolean,
    recording: Boolean,
    onTap: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val active = recording || (pressed && enabled)

    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    recording -> VibeCoral
                    active -> Color.White
                    enabled -> Color.White
                    else -> Color.White.copy(alpha = 0.45f)
                }
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    pressed = true
                    var started = false
                    val hold = scope.launch {
                        delay(240)
                        started = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStart()
                    }
                    val released = tryAwaitRelease()
                    pressed = false
                    if (started) {
                        hold.cancel()
                        if (released) onEnd() else onCancel()
                    } else {
                        hold.cancel()
                        if (released) onTap() else onCancel()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (recording) Color.White else VibeCoral)
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = if (recording) "录音中" else "胶囊",
                fontSize = if (recording) 10.sp else 12.sp,
                color = when {
                    recording -> Color.White
                    enabled -> VibeInk
                    else -> VibeFaint
                },
            )
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
    val haptics = LocalHapticFeedback.current
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
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
private fun SettingsSheet(
    conn: ConnState,
    leftHanded: Boolean,
    pointerSpeed: Float,
    naturalScroll: Boolean,
    debuggable: Boolean,
    onDismiss: () -> Unit,
    onToggleLeftHanded: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleNatural: (Boolean) -> Unit,
    onShowGestures: () -> Unit,
    onSelfTest: () -> Unit,
    onUnpair: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        contentColor = VibeInk,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        ) {
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = VibeInk)
            Spacer(Modifier.height(18.dp))

            SettingsGroup("电脑") {
                SettingsRow(
                    title = "当前电脑",
                    value = when (conn) {
                        is ConnState.Connected ->
                            if (conn.platform == "macos") "Mac" else "Windows"
                        else -> "未连接"
                    },
                )
                SettingsRow(
                    title = "换电脑 / 解除配对",
                    value = "",
                    danger = true,
                    onClick = onUnpair,
                )
            }

            Spacer(Modifier.height(16.dp))
            SettingsGroup("遥控器") {
                SettingsRow(title = "手势说明", value = "", onClick = onShowGestures)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("左手模式", fontSize = 14.sp, color = VibeInk)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = leftHanded,
                        onCheckedChange = onToggleLeftHanded,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = VibeGreen,
                        ),
                    )
                }

                Column(Modifier.padding(bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("指针速度", fontSize = 14.sp, color = VibeInk)
                        Spacer(Modifier.weight(1f))
                        Text(speedLabel(pointerSpeed), fontSize = 12.sp, color = VibeMuted)
                    }
                    Slider(
                        value = pointerSpeed,
                        onValueChange = onSpeedChange,
                        valueRange = 0.6f..2f,
                        steps = 6,
                        colors = SliderDefaults.colors(
                            thumbColor = VibeBlue,
                            activeTrackColor = VibeBlue,
                        ),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text("慢", fontSize = 10.sp, color = VibeFaint)
                        Spacer(Modifier.weight(1f))
                        Text("快", fontSize = 10.sp, color = VibeFaint)
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("自然滚动", fontSize = 14.sp, color = VibeInk)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = naturalScroll,
                        onCheckedChange = onToggleNatural,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = VibeGreen,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsGroup("胶囊") {
                SettingsRow(title = "保存位置", value = "这台手机")
                SettingsRow(title = "转写", value = "手机本地")
            }

            if (debuggable) {
                Spacer(Modifier.height(16.dp))
                SettingsGroup("调试") {
                    SettingsRow(title = "跑一段自检音频", value = "", onClick = onSelfTest)
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("VibeType 0.1.0 · X-ASR", fontSize = 11.sp, color = VibeFaint)
        }
    }
}

@Composable
private fun SettingsGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Text(label, fontSize = 11.sp, color = VibeFaint)
    Spacer(Modifier.height(6.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF5F5F7))
            .padding(horizontal = 16.dp),
        content = content,
    )
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, color = if (danger) VibeCoral else VibeInk)
        Spacer(Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, fontSize = 13.sp, color = VibeFaint)
        }
        if (onClick != null && !danger) {
            Spacer(Modifier.width(6.dp))
            Text("›", fontSize = 16.sp, color = VibeFaint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureSheet(onDismiss: () -> Unit) {
    val rows = listOf(
        "单指移动" to "移动鼠标",
        "轻点" to "左键",
        "双指滑动" to "滚动页面",
        "双指轻点" to "右键",
        "停住再拖" to "按住左键拖拽",
        "边缘滑动" to "滚动，不碰鼠标位置",
        "按住胶囊键" to "存一条闪念",
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        contentColor = VibeInk,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        ) {
            Text("触控板手势", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = VibeInk)
            Spacer(Modifier.height(14.dp))
            rows.forEach { (gesture, meaning) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        gesture,
                        fontSize = 13.sp,
                        color = VibeInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF2F3F5))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(meaning, fontSize = 13.sp, color = VibeMuted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(VibeBlue)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("知道了", fontSize = 14.sp, color = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "之后可以从「设置 → 手势说明」再打开。",
                fontSize = 11.sp,
                color = VibeFaint,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapsuleSheet(
    items: List<Capsule>,
    onDismiss: () -> Unit,
    onCopy: (Capsule) -> Unit,
    onSend: (Capsule) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Capsule?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Capsule?>(null) }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(1_800)
            feedback = null
        }
    }

    val selected = items.firstOrNull { it.id == selectedId }
    // 弹层高度显式给：M3 的 sheet 用内容高度，fillMaxHeight 拿不到屏幕约束。
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.76f
    // 默认会停在「半展开」那一档，胶囊列表会被腰斩，这里直接全开。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        contentColor = VibeInk,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (items.isEmpty()) Modifier else Modifier.height(sheetHeight))
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        ) {
            when {
                selected != null -> CapsuleDetail(
                    capsule = selected,
                    feedback = feedback,
                    modifier = Modifier.weight(1f),
                    onBack = { selectedId = null },
                    onCopy = {
                        onCopy(selected)
                        feedback = "已复制文字"
                    },
                    onSend = {
                        onSend(selected)
                        feedback = "已发到电脑光标"
                    },
                    onEdit = { editing = selected },
                    onDelete = { confirmDelete = selected },
                )
                else -> CapsuleList(
                    items = items,
                    modifier = Modifier.weight(1f),
                    onOpen = { selectedId = it.id },
                    onDismiss = onDismiss,
                )
            }
        }
    }

    editing?.let { capsule ->
        var draft by remember(capsule.id) { mutableStateOf(capsule.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("改一下这段字", fontSize = 16.sp, color = VibeInk) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(capsule.id, draft)
                    editing = null
                }) { Text("保存", color = VibeBlue) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text("取消", color = VibeMuted)
                }
            },
        )
    }

    confirmDelete?.let { capsule ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删掉这条胶囊？", fontSize = 16.sp, color = VibeInk) },
            text = {
                Text(
                    capsule.text,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = VibeMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(capsule.id)
                    confirmDelete = null
                    selectedId = null
                }) { Text("删除", color = VibeCoral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("取消", color = VibeMuted)
                }
            },
        )
    }
}

@Composable
private fun CapsuleList(
    items: List<Capsule>,
    modifier: Modifier = Modifier,
    onOpen: (Capsule) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("闪念胶囊", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = VibeInk)
            if (items.isNotEmpty()) {
                Spacer(Modifier.width(7.dp))
                Text("${items.size}", fontSize = 13.sp, color = VibeFaint)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("完成", fontSize = 13.sp, color = VibeBlue)
            }
        }

        if (items.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp, bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF2F3F5)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋", fontSize = 24.sp, color = VibeFaint)
                }
                Spacer(Modifier.height(14.dp))
                Text("还没有胶囊", fontSize = 15.sp, color = VibeInk)
                Spacer(Modifier.height(7.dp))
                Text(
                    "按住遥控器上的胶囊键，把刚才想到的那句话留下来。",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = VibeMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            return@Column
        }

        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { capsule ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFF5F5F7))
                        .clickable { onOpen(capsule) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(capsuleTime(capsule.createdAt), fontSize = 11.sp, color = VibeFaint)
                        Spacer(Modifier.weight(1f))
                        if (capsule.synced) {
                            Text(
                                "已同步",
                                fontSize = 10.sp,
                                color = Color(0xFF1F9D43),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0x1F30D158))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = capsule.text,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = VibeInk,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = capsuleMeta(capsule),
                        fontSize = 11.sp,
                        color = VibeFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapsuleDetail(
    capsule: Capsule,
    feedback: String?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("‹ 返回", fontSize = 13.sp, color = VibeBlue)
            }
            Spacer(Modifier.weight(1f))
            Text(capsuleTime(capsule.createdAt), fontSize = 12.sp, color = VibeFaint)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = capsule.text,
            fontSize = 18.sp,
            lineHeight = 28.sp,
            color = VibeInk,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF5F5F7))
                .padding(16.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row {
            Text(capsuleMeta(capsule), fontSize = 11.sp, color = VibeFaint)
            if (feedback != null) {
                Spacer(Modifier.width(8.dp))
                Text("· $feedback", fontSize = 11.sp, color = VibeGreen)
            }
        }
        Spacer(Modifier.height(18.dp))
        DetailAction("复制文字", VibeInk, onCopy)
        Spacer(Modifier.height(8.dp))
        DetailAction("发到电脑光标", VibeBlue, onSend, primary = true)
        Spacer(Modifier.height(8.dp))
        DetailAction("编辑文字", VibeInk, onEdit)
        Spacer(Modifier.height(8.dp))
        DetailAction("删除这条胶囊", VibeCoral, onDelete)
        Spacer(Modifier.height(12.dp))
        Text(
            "胶囊只存在这台手机，除非你主动发到电脑。",
            fontSize = 11.sp,
            color = VibeFaint,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DetailAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) VibeBlue else Color(0xFFF5F5F7))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (primary) Color.White else color,
        )
    }
}

private fun capsuleTime(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val day = at.toLocalDate()
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (day) {
        LocalDate.now(zone) -> "今天 $time"
        LocalDate.now(zone).minusDays(1) -> "昨天 $time"
        else -> "${day.monthValue} 月 ${day.dayOfMonth} 日 $time"
    }
}

private fun capsuleMeta(capsule: Capsule): String {
    val seconds = capsule.durationMs / 1000
    val duration = "%d:%02d".format(seconds / 60, seconds % 60)
    return if (capsule.synced) "$duration · 已同步到电脑" else "$duration · 只在手机"
}

private fun speedLabel(value: Float): String = when {
    value < 0.85f -> "慢"
    value < 1.2f -> "标准"
    value < 1.6f -> "快"
    else -> "很快"
}
