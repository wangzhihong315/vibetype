package com.vibetype.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetype.AckState
import com.vibetype.MainViewModel
import com.vibetype.ModelState
import com.vibetype.VibeBlue
import com.vibetype.VibeCoral
import com.vibetype.VibeFaint
import com.vibetype.VibeGreen
import com.vibetype.VibeInk
import com.vibetype.VibeMuted
import com.vibetype.VibeOrange
import com.vibetype.VibeTypeTheme
import com.vibetype.Transcript
import com.vibetype.net.ConnState

@Composable
fun App(vm: MainViewModel) {
    val paired by vm.paired.collectAsState()
    val conn by vm.connection.collectAsState()
    val model by vm.model.collectAsState()
    val recording by vm.recording.collectAsState()
    val busy by vm.busy.collectAsState()
    val level by vm.level.collectAsState()
    val last by vm.last.collectAsState()
    val notice by vm.notice.collectAsState()

    VibeTypeTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(com.vibetype.VibeBackground)
        ) {
            if (paired) {
                HomeScreen(
                    conn = conn,
                    model = model,
                    recording = recording,
                    busy = busy,
                    level = level,
                    last = last,
                    notice = notice,
                    debuggable = vm.debuggable,
                    formatMs = vm::formatMs,
                    onBegin = vm::beginTalk,
                    onEnd = vm::endTalk,
                    onCancel = vm::cancelTalk,
                    onSelfTest = vm::selfTest,
                    onRepair = vm::unpair,
                )
            } else {
                PairingScreen(
                    conn = conn,
                    initialHost = vm.settings.host ?: "",
                    initialPort = vm.settings.port.toString(),
                    onPair = vm::pair,
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    conn: ConnState,
    model: ModelState,
    recording: Boolean,
    busy: Boolean,
    level: Float,
    last: Transcript?,
    notice: String?,
    debuggable: Boolean,
    formatMs: (Long) -> String,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    onSelfTest: () -> Unit,
    onRepair: () -> Unit,
) {
    val ready = model is ModelState.Ready

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 26.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VibeType", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = VibeInk)
            Spacer(Modifier.weight(1f))
            StatusPill(conn)
        }

        Spacer(Modifier.weight(0.8f))

        MicButton(
            enabled = ready && !busy,
            recording = recording,
            level = level,
            onBegin = onBegin,
            onEnd = onEnd,
            onCancel = onCancel,
        )

        Spacer(Modifier.height(22.dp))

        Text(
            text = when {
                recording -> "松开结束"
                busy -> "转写中…"
                model is ModelState.Missing -> "模型未安装"
                model is ModelState.Loading -> "模型加载中…"
                model is ModelState.Failed -> "模型加载失败"
                !ready -> "按住说话"
                else -> "按住说话"
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = VibeInk,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle(conn, model),
            fontSize = 14.sp,
            color = VibeMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        AnimatedVisibility(visible = notice != null) {
            Text(
                text = notice ?: "",
                fontSize = 14.sp,
                color = VibeCoral,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.weight(0.7f))

        if (last != null) {
            TranscriptCard(last, formatMs(last.asrMs))
            Spacer(Modifier.height(14.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SenseVoice · 手机本地转写", fontSize = 12.sp, color = VibeFaint)
            Spacer(Modifier.weight(1f))
            if (debuggable) {
                TextButton(onClick = onSelfTest) {
                    Text("自检", fontSize = 13.sp, color = VibeMuted)
                }
            }
            TextButton(onClick = onRepair) {
                Text("换电脑", fontSize = 13.sp, color = VibeMuted)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun subtitle(conn: ConnState, model: ModelState): String = when {
    conn is ConnState.Failed -> conn.message
    conn is ConnState.Connecting -> "正在连接电脑…"
    conn is ConnState.Connected -> {
        val where = when (conn.platform) {
            "macos" -> "Mac"
            "windows" -> "Windows"
            else -> "电脑"
        }
        "已连接 $where · 文字会打进当前光标"
    }
    model is ModelState.Missing -> "把模型放进手机后重启 App"
    else -> "还没连上电脑"
}

@Composable
private fun StatusPill(conn: ConnState) {
    val (color, label) = when (conn) {
        ConnState.Idle -> VibeFaint to "未连接"
        ConnState.Connecting -> VibeOrange to "连接中"
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
private fun MicButton(
    enabled: Boolean,
    recording: Boolean,
    level: Float,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    val animated by animateFloatAsState(
        targetValue = if (recording) level else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "level",
    )

    val accentTop = if (recording) Color(0xFFFF8A65) else Color(0xFF4DA3FF)
    val accentBottom = if (recording) VibeCoral else VibeBlue

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onBegin()
                        val released = tryAwaitRelease()
                        if (released) onEnd() else onCancel()
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension / 2f
            for (i in 0..2) {
                val radius = base * (0.52f + 0.13f * i + animated * 0.09f)
                drawCircle(
                    color = accentBottom.copy(alpha = (0.10f - i * 0.026f) * (0.45f + animated)),
                    radius = radius,
                    center = center,
                )
            }
        }

        Box(
            modifier = Modifier
                .size(172.dp)
                .shadow(18.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(accentTop, accentBottom),
                    )
                )
                .then(if (enabled) Modifier else Modifier.clip(CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(78.dp)) {
                drawWaveform(
                    color = Color.White.copy(alpha = if (enabled) 0.95f else 0.55f),
                    level = if (recording) animated else 0.18f,
                )
            }
        }
    }
}

/** 五根竖条，按实时音量缩放，代替麦克风图标。 */
private fun DrawScope.drawWaveform(color: Color, level: Float) {
    val weights = floatArrayOf(0.42f, 0.68f, 0.94f, 0.62f, 0.38f)
    val barWidth = size.width * 0.10f
    val gap = size.width * 0.075f
    val totalWidth = barWidth * weights.size + gap * (weights.size - 1)
    var x = (size.width - totalWidth) / 2f
    val centerY = size.height / 2f
    val maxHalf = size.height * 0.46f
    for (weight in weights) {
        val boost = 0.55f + level * 0.85f
        val half = (maxHalf * weight * boost).coerceAtMost(maxHalf)
        drawRoundRect(
            color = color,
            topLeft = Offset(x, centerY - half),
            size = Size(barWidth, half * 2f),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
        x += barWidth + gap
    }
}

@Composable
private fun TranscriptCard(transcript: Transcript, elapsed: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = transcript.text,
            fontSize = 17.sp,
            lineHeight = 26.sp,
            color = VibeInk,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SenseVoice", fontSize = 11.sp, color = VibeFaint)
            Spacer(Modifier.width(10.dp))
            Text(elapsed, fontSize = 11.sp, color = VibeFaint)
            Spacer(Modifier.weight(1f))
            val (label, color) = when (transcript.ack) {
                AckState.Pending -> "发送中" to VibeMuted
                AckState.Delivered -> "已上屏" to VibeGreen
                AckState.Failed -> "未确认" to VibeOrange
            }
            Text(label, fontSize = 11.sp, color = color)
        }
    }
}
