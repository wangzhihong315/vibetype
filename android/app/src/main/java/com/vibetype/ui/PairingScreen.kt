package com.vibetype.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetype.VibeBlue
import com.vibetype.VibeCard
import com.vibetype.VibeCoral
import com.vibetype.VibeFaint
import com.vibetype.VibeInk
import com.vibetype.VibeMuted
import com.vibetype.net.ConnState

@Composable
fun PairingScreen(
    conn: ConnState,
    initialHost: String = "",
    initialPort: String = "47823",
    onPair: (String, Int, String) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var port by rememberSaveable { mutableStateOf(initialPort) }
    var code by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

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
        Spacer(Modifier.height(56.dp))
        Text("连接你的电脑", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = VibeInk)
        Spacer(Modifier.height(10.dp))
        Text(
            "在电脑上打开 VibeType，窗口第一页会显示地址、端口和六位配对码。",
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = VibeMuted,
        )

        Spacer(Modifier.height(34.dp))

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
                label = { Text("电脑地址") },
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
                    label = { Text("端口") },
                    singleLine = true,
                    enabled = !connecting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { input -> code = input.filter { it.isDigit() }.take(6) },
                    label = { Text("配对码") },
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
                localError = validate(host, port, code)
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
                if (connecting) "连接中…" else "连接",
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
            Step("1", "电脑和手机连同一个 Wi-Fi")
            Step("2", "电脑端窗口里抄下地址和配对码")
            Step("3", "回到这里连接，之后会自动重连")
        }

        Spacer(Modifier.height(26.dp))
        Text(
            "录音只在手机本地转写，过网的只有文字。",
            fontSize = 12.sp,
            color = VibeFaint,
        )
        Spacer(Modifier.height(24.dp))
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

private fun validate(host: String, port: String, code: String): String? {
    val trimmed = host.trim()
    if (trimmed.isEmpty()) return "先填电脑地址"
    val parts = trimmed.split(".")
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) {
        return "地址格式不对，应该是 192.168.1.23 这样"
    }
    val portValue = port.toIntOrNull()
    if (portValue == null || portValue !in 1..65535) return "端口不对"
    if (code.length != 6) return "配对码是六位数字"
    return null
}
