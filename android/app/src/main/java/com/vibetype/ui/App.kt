package com.vibetype.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.vibetype.AckState
import com.vibetype.Copy
import com.vibetype.MainViewModel
import com.vibetype.ModelState
import com.vibetype.ThemeMode
import com.vibetype.VibeBlue
import com.vibetype.VibeCard
import com.vibetype.VibeCoral
import com.vibetype.VibeFaint
import com.vibetype.VibeGreen
import com.vibetype.VibeInk
import com.vibetype.VibeMuted
import com.vibetype.VibeOrange
import com.vibetype.VibeTypeTheme
import com.vibetype.Transcript
import com.vibetype.net.ConnState
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun App(
    vm: MainViewModel,
    micGranted: Boolean = true,
    onRequestMic: () -> Unit = {},
) {
    val paired by vm.paired.collectAsState()
    val conn by vm.connection.collectAsState()
    val model by vm.model.collectAsState()
    val recording by vm.recording.collectAsState()
    val busy by vm.busy.collectAsState()
    val level by vm.level.collectAsState()
    val partial by vm.partial.collectAsState()
    val last by vm.last.collectAsState()
    val notice by vm.notice.collectAsState()
    val language by vm.language.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.Auto -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val view = LocalView.current

    LaunchedEffect(language) {
        Copy.apply(language, systemChinese = Locale.getDefault().language.startsWith("zh"))
    }
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500)
            vm.clearNotice()
        }
    }

    VibeTypeTheme(dark = dark) {
        Box(
            Modifier
                .fillMaxSize()
                .background(com.vibetype.VibeBackground)
        ) {
            if (paired) {
                RemoteScreen(
                    vm = vm,
                    conn = conn,
                    model = model,
                    recording = recording,
                    busy = busy,
                    level = level,
                    partial = partial,
                    last = last,
                    notice = notice,
                )
            } else {
                PairingScreen(
                    conn = conn,
                    initialHost = vm.settings.host ?: "",
                    initialPort = vm.settings.port.toString(),
                    english = Copy.english,
                    onToggleLanguage = vm::toggleLanguage,
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
    partial: String,
    last: Transcript?,
    notice: String?,
    debuggable: Boolean,
    micGranted: Boolean,
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
                recording -> Copy.t("松开结束", "Release to finish")
                busy -> Copy.t("转写中…", "Transcribing…")
                model is ModelState.Missing -> Copy.t("模型未安装", "Model not installed")
                model is ModelState.Loading -> Copy.t("模型加载中…", "Loading model…")
                model is ModelState.Failed -> Copy.t("模型加载失败", "Model failed to load")
                !ready -> Copy.t("按住说话", "Hold to talk")
                else -> Copy.t("按住说话", "Hold to talk")
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = VibeInk,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle(conn, model, micGranted),
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

        if (partial.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = partial,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = VibeInk,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
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
            Text(Copy.t("X-ASR · 手机本地转写", "X-ASR · On-device transcription"), fontSize = 12.sp, color = VibeFaint)
            Spacer(Modifier.weight(1f))
            if (debuggable) {
                TextButton(onClick = onSelfTest) {
                    Text(Copy.t("自检", "Self-test"), fontSize = 13.sp, color = VibeMuted)
                }
            }
            TextButton(onClick = onRepair) {
                Text(Copy.t("换电脑", "Change computer"), fontSize = 13.sp, color = VibeMuted)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun subtitle(conn: ConnState, model: ModelState, micGranted: Boolean): String = when {
    !micGranted -> Copy.t("点一下按钮，允许使用麦克风", "Tap the button and allow microphone access")
    conn is ConnState.Failed -> conn.message
    conn is ConnState.Connecting -> Copy.t("正在连接电脑…", "Connecting to your computer…")
    conn is ConnState.Connected -> {
        val where = when (conn.platform) {
            "macos" -> "Mac"
            "windows" -> "Windows"
            else -> Copy.t("电脑", "Computer")
        }
        Copy.t("已连接 $where · 文字会打进当前光标", "Connected to $where · Text goes to the cursor")
    }
    model is ModelState.Missing -> Copy.t("把模型放进手机后重启 App", "Add the model to the phone, then restart the app")
    else -> Copy.t("还没连上电脑", "Not connected yet")
}

@Composable
private fun StatusPill(conn: ConnState) {
    val (color, label) = when (conn) {
        ConnState.Idle -> VibeFaint to Copy.t("未连接", "Not connected")
        ConnState.Connecting -> VibeOrange to Copy.t("连接中", "Connecting…")
        is ConnState.Connected -> VibeGreen to Copy.t("已连接", "Connected")
        is ConnState.Failed -> VibeCoral to Copy.t("未连接", "Not connected")
        is ConnState.NeedsPairing -> VibeCoral to Copy.t("未配对", "Not paired")
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(VibeCard)
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
            .background(VibeCard)
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
            Text("X-ASR", fontSize = 11.sp, color = VibeFaint)
            Spacer(Modifier.width(10.dp))
            Text(elapsed, fontSize = 11.sp, color = VibeFaint)
            if (transcript.corrected > 0) {
                Spacer(Modifier.width(8.dp))
                Text(Copy.t("已更正 ${transcript.corrected} 处", "${transcript.corrected} corrected"), fontSize = 10.sp, color = VibeBlue)
            }
            Spacer(Modifier.weight(1f))
            val (label, color) = when (transcript.ack) {
                AckState.Pending -> Copy.t("发送中", "Sending") to VibeMuted
                AckState.Delivered -> Copy.t("已上屏", "Typed") to VibeGreen
                AckState.Failed -> Copy.t("未确认", "No ack") to VibeOrange
            }
            Text(label, fontSize = 11.sp, color = color)
        }
    }
}


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
