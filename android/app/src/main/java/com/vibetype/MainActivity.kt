package com.vibetype

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibetype.net.PairLinks
import com.vibetype.ui.App
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** 从二维码链接带进来的配对信息，交给 Compose 去落地。 */
    private val pairRequest = MutableStateFlow<PairRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 按住说话时屏幕不能灭
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handlePairLink(intent)

        setContent {
            val vm: MainViewModel = viewModel()
            val context = LocalContext.current
            var granted by remember { mutableStateOf(hasMicrophone(context)) }
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted = it }
            val request by pairRequest.collectAsState()

            LaunchedEffect(Unit) {
                if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
            LaunchedEffect(request) {
                val pending = request ?: return@LaunchedEffect
                vm.pair(pending.host, pending.port, pending.code)
                pairRequest.value = null
            }

            App(
                vm = vm,
                micGranted = granted,
                onRequestMic = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairLink(intent)
    }

    private fun handlePairLink(intent: Intent?) {
        val raw = intent?.data?.toString() ?: return
        val link = PairLinks.parse(raw) ?: return
        pairRequest.value = PairRequest(link.host, link.port, link.code)
    }

    private fun hasMicrophone(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private data class PairRequest(val host: String, val port: Int, val code: String)
