package com.vibetype.net

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
class DesktopClient(private val settings: PairingStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { loop() }
    }

    /** 重新配对后调用：断开旧连接，立刻用新的 host/token 重连。 */
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
            .put("engine", "sensevoice-int8")
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
                _state.value = ConnState.NeedsPairing(e.message ?: "需要重新配对")
                return
            } catch (e: Exception) {
                _state.value = ConnState.Failed(humanize(e))
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun serve(host: String, port: Int, token: String) {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 4_000)
            s.tcpNoDelay = true
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

            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = input.readLine() ?: throw IOException("桌面端断开了连接")
                handle(line)
            }
        } finally {
            closeSocket()
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
                _state.value = ConnState.Connected(server, platform)
            }

            "error" -> {
                val code = json.optString("code", "error")
                val message = when (code) {
                    "bad_token" -> "配对码无效或已过期"
                    "unsupported_version" -> "桌面端版本不兼容，请升级"
                    "too_many_devices" -> "设备数量已满，请在电脑上移除旧设备"
                    else -> json.optString("msg", "桌面端返回错误")
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

    private fun closeSocket() {
        writer = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    private fun humanize(e: Exception): String = when (e) {
        is SocketTimeoutException -> "连接超时，确认电脑和手机在同一 Wi-Fi"
        is ConnectException -> "连不上电脑，确认 VibeType 已打开"
        else -> e.message ?: "连接失败"
    }

    private class PairingRequired(message: String) : Exception(message)

    companion object {
        private const val TAG = "DesktopClient"
        const val PROTOCOL_VERSION = 1
        const val APP_VERSION = "0.1.0"
    }
}
