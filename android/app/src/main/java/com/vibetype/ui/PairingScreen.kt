package com.vibetype.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vibetype.VibeBlue
import com.vibetype.VibeCard
import com.vibetype.VibeCoral
import com.vibetype.VibeFaint
import com.vibetype.VibeInk
import com.vibetype.VibeMuted
import com.vibetype.net.ConnState
import com.vibetype.net.PairLinks

@Composable
fun PairingScreen(
    conn: ConnState,
    initialHost: String = "",
    initialPort: String = "47823",
    english: Boolean = false,
    onToggleLanguage: () -> Unit = {},
    onPair: (String, Int, String) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var port by rememberSaveable { mutableStateOf(initialPort) }
    var code by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraDenied = !granted
        if (granted) showScanner = true
    }
    fun openScanner() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) showScanner = true else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    val copy = pairingCopy(english)

    val connecting = conn is ConnState.Connecting
    val error = localError
        ?: (conn as? ConnState.NeedsPairing)?.message
        ?: (conn as? ConnState.Failed)?.message

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onToggleLanguage) {
                Text(if (english) "中文" else "English", fontSize = 13.sp, color = VibeBlue)
            }
        }
        Text(copy.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = VibeInk)
        Spacer(Modifier.height(10.dp))
        Text(
            copy.lead,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = VibeMuted,
        )

        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(VibeBlue.copy(alpha = 0.10f))
                .clickable { openScanner() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                copy.scan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = VibeBlue,
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(copy.scanHint, fontSize = 11.sp, color = VibeFaint)
        if (cameraDenied) {
            Spacer(Modifier.height(4.dp))
            Text(copy.cameraDenied, fontSize = 11.sp, color = VibeCoral)
        }

        Spacer(Modifier.height(24.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(VibeCard)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it
                    localError = null
                },
                label = { Text(copy.address) },
                placeholder = { Text("192.168.1.23") },
                singleLine = true,
                enabled = !connecting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { input -> port = input.filter { it.isDigit() }.take(5) },
                    label = { Text(copy.port) },
                    singleLine = true,
                    enabled = !connecting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { input -> code = input.filter { it.isDigit() }.take(6) },
                    label = { Text(copy.code) },
                    singleLine = true,
                    enabled = !connecting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1.4f),
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        Button(
            onClick = {
                localError = validate(host, port, code, english)
                if (localError == null) {
                    onPair(host, port.toInt(), code)
                }
            },
            enabled = !connecting,
            colors = ButtonDefaults.buttonColors(containerColor = VibeBlue),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                if (connecting) copy.connecting else copy.connect,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(error, fontSize = 13.sp, color = VibeCoral)
        }

        Spacer(Modifier.height(28.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(VibeCard.copy(alpha = 0.65f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Step("1", copy.step1)
            Step("2", copy.step2)
            Step("3", copy.step3)
        }

        Spacer(Modifier.height(26.dp))
        Text(
            copy.footer,
            fontSize = 12.sp,
            color = VibeFaint,
        )
        Spacer(Modifier.height(24.dp))

        if (showScanner) {
            QrScannerDialog(
                title = copy.scanTitle,
                lead = copy.scanLead,
                closeLabel = copy.close,
                cameraError = copy.cameraError,
                fallbackHint = copy.scanFallback,
                onDismiss = { showScanner = false },
                onScanned = { raw ->
                    val link = PairLinks.parse(raw)
                    if (link != null) {
                        showScanner = false
                        host = link.host
                        port = link.port.toString()
                        code = link.code
                        localError = null
                        onPair(link.host, link.port, link.code)
                    }
                },
            )
        }
    }
}

@Composable
private fun Step(index: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            index,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = VibeBlue,
            modifier = Modifier.width(20.dp),
        )
        Text(text, fontSize = 13.sp, color = VibeMuted)
    }
}

private fun validate(host: String, port: String, code: String, english: Boolean): String? {
    val trimmed = host.trim()
    if (trimmed.isEmpty()) return if (english) "Enter the computer address" else "先填电脑地址"
    val parts = trimmed.split(".")
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) {
        return if (english) {
            "That address doesn't look right — try 192.168.1.23"
        } else {
            "地址格式不对，应该是 192.168.1.23 这样"
        }
    }
    val portValue = port.toIntOrNull()
    if (portValue == null || portValue !in 1..65535) {
        return if (english) "Invalid port" else "端口不对"
    }
    if (code.length != 6) {
        return if (english) "The pairing code is 6 digits" else "配对码是六位数字"
    }
    return null
}

private data class PairingCopy(
    val title: String,
    val lead: String,
    val address: String,
    val port: String,
    val code: String,
    val connecting: String,
    val connect: String,
    val step1: String,
    val step2: String,
    val step3: String,
    val footer: String,
    val scan: String,
    val scanHint: String,
    val cameraDenied: String,
    val scanTitle: String,
    val scanLead: String,
    val close: String,
    val cameraError: String,
    val scanFallback: String,
)

private fun pairingCopy(english: Boolean): PairingCopy = if (english) {
    PairingCopy(
        title = "Connect to your computer",
        lead = "Open VibeType on your computer. The first screen shows the address, port and a 6-digit code.",
        address = "Computer address",
        port = "Port",
        code = "Pairing code",
        connecting = "Connecting…",
        connect = "Connect",
        step1 = "Put your phone and computer on the same Wi-Fi",
        step2 = "Copy the address and code from the computer window",
        step3 = "Connect here once — it reconnects automatically after that",
        footer = "Audio never leaves the phone. Only text goes over the network.",
        scan = "Scan the QR code on your computer",
        scanHint = "Scanning fills in the address and pairing code for you",
        cameraDenied = "No camera permission. You can still enter the address below.",
        scanTitle = "Point at the QR code",
        scanLead = "Open the QR code on the desktop Connect page",
        close = "Close",
        cameraError = "Camera unavailable. Enter the address below instead.",
        scanFallback = "Black screen? Close and enter the address below.",
    )
} else {
    PairingCopy(
        title = "连接你的电脑",
        lead = "在电脑上打开 VibeType，窗口第一页会显示地址、端口和六位配对码。",
        address = "电脑地址",
        port = "端口",
        code = "配对码",
        connecting = "连接中…",
        connect = "连接",
        step1 = "电脑和手机连同一个 Wi-Fi",
        step2 = "电脑端窗口里抄下地址和配对码",
        step3 = "回到这里连接，之后会自动重连",
        footer = "录音只在手机本地转写，过网的只有文字。",
        scan = "扫电脑上的二维码",
        scanHint = "扫一下会自动填好地址和配对码",
        cameraDenied = "没有相机权限，可以继续在下面手动输入。",
        scanTitle = "对准电脑上的二维码",
        scanLead = "在电脑端「连接」页打开二维码",
        close = "关闭",
        cameraError = "相机不可用，请在下面手动输入地址。",
        scanFallback = "一直黑屏？关闭后在下面手动输入地址。",
    )
}
