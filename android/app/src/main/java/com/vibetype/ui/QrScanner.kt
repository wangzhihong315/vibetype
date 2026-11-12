package com.vibetype.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.vibetype.VibeCoral
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫电脑上的二维码。识别到 vibetype://pair 后直接回调，不经过系统相机。
 */
@Composable
fun QrScannerDialog(
    title: String,
    lead: String,
    closeLabel: String,
    cameraError: String,
    fallbackHint: String,
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        var error by remember { mutableStateOf<String?>(null) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            CameraPreview(
                onScanned = onScanned,
                onError = { error = it },
            )
            ScanOverlay(title = title, lead = lead)
            Text(
                closeLabel,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(20.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
            Text(
                fallbackHint,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.34f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 22.dp),
            )
            if (error != null) {
                Text(
                    cameraError,
                    fontSize = 12.sp,
                    color = Color(0xFFFF8A72),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 32.dp, vertical = 120.dp),
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(
    onScanned: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val found = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, QrCodeAnalyzer { text ->
                    if (!text.startsWith("vibetype://", ignoreCase = true)) return@QrCodeAnalyzer
                    if (found.compareAndSet(false, true)) {
                        previewView.post { onScanned(text) }
                    }
                })
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                onError(e.message ?: "camera unavailable")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching {
                if (future.isDone) future.get().unbindAll()
            }
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ScanOverlay(title: String, lead: String) {
    val transition = rememberInfiniteTransition(label = "scan")
    val line by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "line",
    )

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val side = 250.dp.toPx()
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val dim = Color.Black.copy(alpha = 0.52f)
            drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, top))
            drawRect(
                dim,
                topLeft = Offset(0f, top + side),
                size = Size(size.width, size.height - top - side),
            )
            drawRect(dim, topLeft = Offset(0f, top), size = Size(left, side))
            drawRect(
                dim,
                topLeft = Offset(left + side, top),
                size = Size(size.width - left - side, side),
            )

            val corner = 34.dp.toPx()
            val stroke = 3.dp.toPx()
            val color = Color.White
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke, StrokeCap.Round)
            }
            // 左上
            line(left, top + corner, left, top)
            line(left, top, left + corner, top)
            // 右上
            line(left + side - corner, top, left + side, top)
            line(left + side, top, left + side, top + corner)
            // 左下
            line(left, top + side - corner, left, top + side)
            line(left, top + side, left + corner, top + side)
            // 右下
            line(left + side - corner, top + side, left + side, top + side)
            line(left + side, top + side, left + side, top + side - corner)

            drawLine(
                VibeCoral.copy(alpha = 0.9f),
                Offset(left + 18.dp.toPx(), top + side * line),
                Offset(left + side - 18.dp.toPx(), top + side * line),
                2.dp.toPx(),
                StrokeCap.Round,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                lead,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}

private class QrCodeAnalyzer(
    private val onFound: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = QRCodeReader()
    private var lastAttempt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAttempt < 100) return
            lastAttempt = now
            decode(image)?.let(onFound)
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val data = ByteArray(width * height)

        if (pixelStride == 1 && rowStride == width) {
            buffer.rewind()
            buffer.get(data)
        } else {
            val row = ByteArray(rowStride)
            var out = 0
            for (y in 0 until height) {
                val start = y * rowStride
                if (start >= buffer.limit()) break
                buffer.position(start)
                val count = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, count)
                var x = 0
                while (x < width && out < data.size) {
                    val index = x * pixelStride
                    data[out++] = if (index < count) row[index] else 0
                    x++
                }
            }
        }

        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decode(bitmap).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
