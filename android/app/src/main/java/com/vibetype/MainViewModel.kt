package com.vibetype

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibetype.asr.AsrResult
import com.vibetype.asr.SenseVoice
import com.vibetype.audio.Recorder
import com.vibetype.net.ConnState
import com.vibetype.net.DesktopClient
import com.vibetype.net.PairingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

sealed interface ModelState {
    data object Missing : ModelState
    data object Loading : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

enum class AckState { Pending, Delivered, Failed }

data class Transcript(
    val text: String,
    val asrMs: Long,
    val lang: String,
    val ack: AckState,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = PairingStore(app)
    val client = DesktopClient(settings)
    private val recorder = Recorder()

    val level: StateFlow<Float> = recorder.level
    val connection: StateFlow<ConnState> = client.state

    private val _model = MutableStateFlow<ModelState>(ModelState.Missing)
    val model: StateFlow<ModelState> = _model.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _last = MutableStateFlow<Transcript?>(null)
    val last: StateFlow<Transcript?> = _last.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _paired = MutableStateFlow(settings.paired)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    private var asr: SenseVoice? = null

    // 内部私有目录：adb push 到外部目录的模型在 Android 11+ 上 App 读不到（FUSE 归属），
    // 调试时用 tools/push_model.sh 走 run-as 管道灌进来。
    val modelDir = SenseVoice.modelDir(app.filesDir)

    /** debug 包才显示「自检」入口。 */
    val debuggable = (app.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    init {
        client.start()
        loadModel()
        viewModelScope.launch {
            client.state.collect { state ->
                if (state is ConnState.NeedsPairing) {
                    // token 已经失效，清掉；地址留着，配对页还能直接带出来
                    settings.token = null
                    _paired.value = false
                }
            }
        }
    }

    fun loadModel() {
        if (_model.value is ModelState.Loading || _model.value is ModelState.Ready) return
        android.util.Log.i(
            "VibeType",
            "modelDir=$modelDir ready=${SenseVoice.isReady(modelDir)} " +
                "model=${java.io.File(modelDir, SenseVoice.MODEL_FILE).length()}"
        )
        if (!SenseVoice.isReady(modelDir)) {
            _model.value = ModelState.Missing
            return
        }
        _model.value = ModelState.Loading
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (asr == null) {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    asr = SenseVoice(modelDir, threads)
                }
                _model.value = ModelState.Ready
            } catch (e: OutOfMemoryError) {
                _model.value = ModelState.Failed("内存不足，模型没加载起来")
            } catch (e: Throwable) {
                _model.value = ModelState.Failed(e.message ?: "模型加载失败")
            }
        }
    }

    fun beginTalk() {
        if (_recording.value || _busy.value || _model.value !is ModelState.Ready) return
        _notice.value = null
        if (recorder.start()) {
            _recording.value = true
        } else {
            _notice.value = "麦克风打不开，检查录音权限"
        }
    }

    fun endTalk() {
        if (!_recording.value) return
        _recording.value = false
        val pcm = recorder.stop()
        val minSamples = Recorder.SAMPLE_RATE * 4 / 10 // 400 ms
        if (pcm.size < minSamples) {
            _notice.value = "说话时间太短"
            return
        }
        val trimmed = recorder.trimSilence(pcm)
        if (trimmed.isEmpty()) {
            _notice.value = "没听到声音"
            return
        }
        process(trimmed)
    }

    fun cancelTalk() {
        if (!_recording.value) return
        _recording.value = false
        recorder.cancel()
    }

    /**
     * 自检：把 assets 里的 16 kHz WAV 当成一次录音，跑完整的转写 + 发送。
     * 模拟器上没法按住说话时用这个验链路。
     */
    fun selfTest() {
        if (_busy.value) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val pcm = try {
                val bytes = app.assets.open("selftest.wav").use { it.readBytes() }
                readWavPcm(bytes)
            } catch (e: Throwable) {
                null
            }
            if (pcm == null || pcm.isEmpty()) {
                _notice.value = "自检音频读取失败"
                return@launch
            }
            process(pcm)
        }
    }

    private fun readWavPcm(bytes: ByteArray): ShortArray {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = (bytes[offset + 4].toInt() and 0xff) or
                ((bytes[offset + 5].toInt() and 0xff) shl 8) or
                ((bytes[offset + 6].toInt() and 0xff) shl 16) or
                ((bytes[offset + 7].toInt() and 0xff) shl 24)
            if (id == "data") {
                val start = offset + 8
                val length = minOf(size, bytes.size - start).coerceAtLeast(0)
                val buffer = java.nio.ByteBuffer.wrap(bytes, start, length)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                val out = ShortArray(buffer.remaining())
                buffer.get(out)
                return out
            }
            offset += 8 + size + (size and 1)
        }
        return ShortArray(0)
    }

    private fun process(pcm: ShortArray) {
        val engine = asr ?: run {
            _notice.value = "模型还没准备好"
            return
        }
        _busy.value = true
        viewModelScope.launch(Dispatchers.Default) {
            val result: AsrResult? = try {
                engine.transcribe(pcm)
            } catch (e: Throwable) {
                null
            }
            if (result == null || result.text.isBlank()) {
                _notice.value = "没听清，再来一次"
                _busy.value = false
                return@launch
            }

            val durationMs = pcm.size * 1000 / Recorder.SAMPLE_RATE
            _last.value = Transcript(result.text, result.elapsedMs, result.lang, AckState.Pending)
            val delivered = client.sendText(result.text, durationMs, result.lang)
            _last.value = _last.value?.copy(
                ack = if (delivered) AckState.Delivered else AckState.Failed
            )
            _busy.value = false
        }
    }

    fun pair(host: String, port: Int, code: String) {
        settings.save(host.trim(), port, code.trim())
        settings.deviceToken = null
        _paired.value = true
        client.restart()
    }

    fun unpair() {
        settings.clear()
        _last.value = null
        _paired.value = false
        client.restart()
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun copyLast(): String? = _last.value?.text

    override fun onCleared() {
        client.stop()
        asr?.release()
        asr = null
        recorder.cancel()
    }

    /** 给 UI 用的耗时展示。 */
    fun formatMs(ms: Long): String = if (ms >= 1000) {
        val tenths = (ms / 100.0).roundToInt() / 10.0
        "${tenths}s"
    } else {
        "${ms}ms"
    }
}
