package com.vibetype.net

import com.vibetype.Copy
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data class Connected(val server: String, val platform: String) : ConnState
    data class Failed(val message: String) : ConnState

    /** 配对码/设备 token 失效：不要自动重试，回到配对页。 */
    data class NeedsPairing(val message: String) : ConnState
}

/**
 * 桌面端的 TCP 长连接。断线自动重连，指数退避。
 * 只有文字过网，音频不出手机。
 */
/** 手机底部按键：标签由桌面端下发，点下去只回传 id。 */
data class KeySlot(val id: String, val label: String)

private val defaultKeymap = listOf(
    KeySlot("k1", "回车"),
    KeySlot("k2", "删除"),
    KeySlot("k3", "Esc"),
    KeySlot("k4", "切换"),
    KeySlot("k5", "自定义"),
)

class DesktopClient(private val settings: PairingStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()

    /** 用户点「重新连接」时立刻打断退避等待。 */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastDiscoveryAt = 0L

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _keymap = MutableStateFlow(defaultKeymap)
    val keymap: StateFlow<List<KeySlot>> = _keymap.asStateFlow()

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    /** 指针/按键事件走单消费者，保证顺序，不阻塞 UI 线程。 */
    private val pointerFrames = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (line in pointerFrames) {
                val out = writer ?: continue
                try {
                    writeLine(out, line)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun sendPointer(action: String, button: String = "", dx: Float = 0f, dy: Float = 0f) {
        if (_state.value !is ConnState.Connected) return
        val line = JSONObject()
            .put("t", "pointer")
            .put("action", action)
            .put("button", button)
            .put("dx", dx)
            .put("dy", dy)
            .toString()
        pointerFrames.trySend(line)
    }

    fun sendKey(name: String) {
        if (_state.value !is ConnState.Connected) return
        pointerFrames.trySend(JSONObject().put("t", "key").put("name", name).toString())
    }

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { loop() }
    }

    /** 先广播找一次，再立刻重连；换 Wi-Fi / 换办公室后不用用户改地址。 */
    fun retryNow() {
        if (_state.value is ConnState.Connected) return
        scope.launch {
            discoverNow()
            // 广播这一秒里可能已经自己连上了，别再打断一次。
            if (_state.value !is ConnState.Connected) restart()
        }
    }

    /** 重新配对后调用：断开旧连接，立刻用新的 host/token 重连。 */
    @Synchronized
    fun restart() {
        loopJob?.cancel()
        closeSocket()
        loopJob = scope.launch { loop() }
    }

    fun stop() {
        loopJob?.cancel()
        closeSocket()
        scope.cancel()
    }

    /**
     * 发一段文字，等桌面端 ack。返回 false 表示没发出去、5 秒没确认，
     * 或桌面端两种注入都失败。失败不重发，避免同一段话上屏两次。
     */
    /** 胶囊只是抄一份到电脑，方便桌面查看和 MCP 读取；丢了也不影响手机上的原件。 */
    fun sendCapsule(
        id: String,
        text: String,
        createdAt: Long,
        durationMs: Int,
        onSent: (() -> Unit)? = null,
    ) {
        val out = writer ?: return
        if (_state.value !is ConnState.Connected) return
        val frame = JSONObject()
            .put("t", "capsule")
            .put("id", id)
            .put("text", text)
            .put("createdAt", createdAt)
            .put("ms", durationMs)
            .toString()
        scope.launch {
            try {
                writeLine(out, frame)
                onSent?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "sendCapsule failed: ${e.message}")
            }
        }
    }

    suspend fun sendText(text: String, durationMs: Int, lang: String): Boolean {
        val out = writer ?: return false
        if (_state.value !is ConnState.Connected) return false

        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        val frame = JSONObject()
            .put("t", "text")
            .put("id", id)
            .put("text", text)
            .put("ms", durationMs)
            .put("lang", lang)
            .put("engine", "x-asr")
            .toString()

        return try {
            writeLine(out, frame)
            withTimeoutOrNull(5_000) { deferred.await() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "sendText failed: ${e.message}")
            false
        } finally {
            pending.remove(id)
        }
    }

    /** 桌面上改了键位会推过来，手机不用重连。 */
    private fun applyKeymap(json: JSONObject) {
        val keys = json.optJSONArray("keys") ?: return
        if (keys.length() == 0) return
        val parsed = (0 until keys.length()).mapNotNull { index ->
            val item = keys.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val label = item.optString("label")
            if (id.isBlank() || label.isBlank()) null else KeySlot(id, label)
        }
        if (parsed.size == 5) _keymap.value = parsed
    }

    private suspend fun loop() {
        var backoff = 1_000L
        while (coroutineContext.isActive) {
            val host = settings.host
            val token = settings.token
            if (host.isNullOrBlank() || token.isNullOrBlank()) {
                _state.value = ConnState.Idle
                delay(1_500)
                continue
            }

            _state.value = ConnState.Connecting
            try {
                serve(host, settings.port, token)
                // 服务端正常关连接：稍等再连
                backoff = 1_000L
                delay(500)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: PairingRequired) {
                _state.value = ConnState.NeedsPairing(e.message ?: Copy.t("需要重新配对", "Pair again"))
                return
            } catch (e: ServerClosed) {
                // 桌面端正常收起连接（空闲超时/重启），静默重连，不报错
                _state.value = ConnState.Connecting
                backoff = 1_000L
                delay(300)
            } catch (e: Exception) {
                // restart() 会取消旧 loop 并关掉旧 socket；旧 loop 的 readLine
                // 会抛 SocketException，但这不代表新连接失败。
                if (!coroutineContext.isActive) break
                // 成功连过之后再掉线，退避要归零，不能沿用之前的指数值
                val wasConnected = _state.value is ConnState.Connected
                _state.value = ConnState.Failed(humanize(e))
                if (wasConnected) backoff = 1_000L
                // 地址可能随网络变了：先在同一网段广播找一次，找到就立刻重试。
                if (maybeDiscover()) {
                    backoff = 1_000L
                    continue
                }
                withTimeoutOrNull(backoff) { wake.receive() }
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    private suspend fun discoverNow(): LanDiscovery.Peer? {
        if (settings.deviceToken.isNullOrBlank()) return null
        val expected = settings.desktopId?.takeIf { it.isNotBlank() }
        val peer = LanDiscovery.find(expected).firstOrNull() ?: return null
        settings.host = peer.host
        settings.port = peer.port
        lastDiscoveryAt = System.currentTimeMillis()
        Log.i(TAG, "discovered ${peer.host}:${peer.port}")
        return peer
    }

    private suspend fun maybeDiscover(): Boolean {
        if (System.currentTimeMillis() - lastDiscoveryAt < 6_000) return false
        return discoverNow() != null
    }

    private suspend fun serve(host: String, port: Int, token: String) {
        val s = Socket()
        var heartbeat: Job? = null
        try {
            s.connect(InetSocketAddress(host, port), 4_000)
            // 重启时旧连接可能在这之后才连上；别把已经过期的连接写进共享状态。
            coroutineContext.ensureActive()
            s.tcpNoDelay = true
            // 桌面端 20 秒回一次 pong；45 秒没有任何数据就当作半开连接，
            // 否则换网/对端消失时界面会一直停在“已连接”。
            s.soTimeout = 45_000
            socket = s

            val out = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            writer = out
            writeLine(
                out,
                JSONObject()
                    .put("t", "hello")
                    .put("v", PROTOCOL_VERSION)
                    .put("token", token)
                    .put(
                        "device",
                        JSONObject()
                            .put("name", android.os.Build.MODEL)
                            .put("platform", "android")
                            .put("app", APP_VERSION)
                    )
                    .toString()
            )

            // 桌面端 60 秒没收到任何东西会断；20 秒一个 ping 保持长连接
            heartbeat = scope.launch {
                while (isActive) {
                    delay(20_000)
                    try {
                        writeLine(out, JSONObject().put("t", "ping").toString())
                    } catch (_: Exception) {
                        break
                    }
                }
            }

            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = input.readLine() ?: throw ServerClosed()
                handle(line)
            }
        } finally {
            heartbeat?.cancel()
            closeSocket(s)
        }
    }

    private fun handle(line: String) {
        val json = try {
            JSONObject(line)
        } catch (e: Exception) {
            return
        }
        when (json.optString("t")) {
            "welcome" -> {
                val server = json.optString("server", "vibetype")
                val platform = json.optString("platform", "")
                val issued = json.optString("deviceToken", "")
                if (issued.isNotBlank()) {
                    settings.deviceToken = issued
                    settings.token = issued
                }
                if (platform.isNotBlank()) settings.desktopName = platform
                val desktopId = json.optString("desktopId", "")
                if (desktopId.isNotBlank()) settings.desktopId = desktopId
                applyKeymap(json)
                _state.value = ConnState.Connected(server, platform)
            }

            "keymap" -> applyKeymap(json)

            "error" -> {
                val code = json.optString("code", "error")
                val message = when (code) {
                    "bad_token" -> Copy.t("配对码无效或已过期", "Invalid or expired pairing code")
                    "unsupported_version" -> Copy.t("桌面端版本不兼容，请升级", "Desktop app version is incompatible. Please update.")
                    "too_many_devices" -> Copy.t("设备数量已满，请在电脑上移除旧设备", "Device limit reached. Remove an old device on the computer.")
                    else -> json.optString("msg", Copy.t("桌面端返回错误", "The desktop app returned an error"))
                }
                if (code == "bad_token") throw PairingRequired(message)
                throw IOException(message)
            }

            "ack" -> {
                val id = json.optString("id")
                val ok = json.optBoolean("ok", false)
                pending.remove(id)?.complete(ok)
            }

            "pong" -> Unit
            else -> Unit
        }
    }

    private fun writeLine(out: BufferedWriter, line: String) {
        synchronized(writeLock) {
            out.write(line)
            out.newLine()
            out.flush()
        }
    }

    /** expected 不为空时，只关自己那条连接：避免旧协程把新连接一起关掉。 */
    private fun closeSocket(expected: Socket? = null) {
        synchronized(writeLock) {
            if (expected != null && socket !== expected) return
            writer = null
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    private fun humanize(e: Exception): String = when (e) {
        is SocketTimeoutException -> Copy.t("连接超时，确认电脑和手机在同一 Wi-Fi", "Connection timed out. Make sure both devices are on the same Wi-Fi.")
        is ConnectException -> Copy.t("连不上电脑，确认 VibeType 已打开", "Can't reach the computer. Make sure VibeType is running.")
        else -> e.message ?: Copy.t("连接失败", "Connection failed")
    }

    private class PairingRequired(message: String) : Exception(message)

    /** 桌面端主动关连接，属于正常情况。 */
    private class ServerClosed : Exception(Copy.t("桌面端断开了连接", "The desktop app disconnected"))

    companion object {
        private const val TAG = "DesktopClient"
        const val PROTOCOL_VERSION = 1
        const val APP_VERSION = "0.2.0"
    }
}
