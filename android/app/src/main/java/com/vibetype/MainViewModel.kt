package com.vibetype

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibetype.asr.Corrected
import com.vibetype.asr.TermCorrector
import com.vibetype.asr.XAsr
import com.vibetype.audio.Recorder
import com.vibetype.net.ConnState
import com.vibetype.net.DesktopClient
import com.vibetype.net.PairingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val corrected: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = PairingStore(app)
    val client = DesktopClient(settings)
    private val recorder = Recorder()
    private val corrector = TermCorrector(app.assets)

    val level: StateFlow<Float> = recorder.level
    val connection: StateFlow<ConnState> = client.state

    private val _model = MutableStateFlow<ModelState>(ModelState.Missing)
    val model: StateFlow<ModelState> = _model.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** 录音过程中不断增长的文字，用于浮层里的实时显示。 */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _last = MutableStateFlow<Transcript?>(null)
    val last: StateFlow<Transcript?> = _last.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _paired = MutableStateFlow(settings.paired)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    private var asr: XAsr? = null

    /** debug 包才显示「自检」入口。 */
    val debuggable = (app.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    init {
        client.start()
        loadModel()
        viewModelScope.launch {
            client.state.collect { state ->
                if (state is ConnState.NeedsPairing) {
                    settings.token = null
                    _paired.value = false
                }
            }
        }
    }

    fun loadModel() {
        if (_model.value is ModelState.Loading || _model.value is ModelState.Ready) return
        val app = getApplication<Application>()
        if (!XAsr.isReady(app.assets)) {
            _model.value = ModelState.Missing
            return
        }
        _model.value = ModelState.Loading
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (asr == null) {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    asr = XAsr(app.assets, threads)
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
        _partial.value = ""

        val engine = asr ?: run {
            _notice.value = "模型还没准备好"
            return
        }
        engine.start()
        recorder.onChunk = { chunk ->
            val text = try {
                engine.accept(chunk)
            } catch (e: Throwable) {
                ""
            }
            if (text.isNotEmpty()) {
                _partial.value = text
            }
        }
        if (recorder.start()) {
            _recording.value = true
        } else {
            recorder.onChunk = null
            engine.cancel()
            _notice.value = "麦克风打不开，检查录音权限"
        }
    }

    fun endTalk() {
        if (!_recording.value) return
        _recording.value = false
        recorder.onChunk = null
        val pcm = recorder.stop()
        val minSamples = Recorder.SAMPLE_RATE * 4 / 10
        if (pcm.size < minSamples) {
            asr?.cancel()
            _partial.value = ""
            _notice.value = "说话时间太短"
            return
        }
        finishAndSend(pcm.size * 1000 / Recorder.SAMPLE_RATE)
    }

    fun cancelTalk() {
        if (!_recording.value) return
        _recording.value = false
        recorder.onChunk = null
        recorder.cancel()
        asr?.cancel()
        _partial.value = ""
    }

    /** 松手后补静音、取最终文本，走术语纠正再发送。 */
    private fun finishAndSend(durationMs: Int) {
        val engine = asr ?: return
        _busy.value = true
        viewModelScope.launch(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            val raw = try {
                engine.finish()
            } catch (e: Throwable) {
                ""
            }
            val elapsed = System.currentTimeMillis() - started
            _partial.value = ""
            if (raw.isBlank()) {
                _notice.value = "没听清，再来一次"
                _busy.value = false
                return@launch
            }
            val corrected: Corrected = corrector.correct(raw)
            val text = corrected.text
            _last.value = Transcript(text, elapsed, "zh", AckState.Pending, corrected.changes)
            val delivered = client.sendText(text, durationMs, "zh")
            _last.value = _last.value?.copy(
                ack = if (delivered) AckState.Delivered else AckState.Failed
            )
            _busy.value = false
        }
    }

    /**
     * 自检：把 assets 里的 16 kHz WAV 当成一次实时录音，按 100 ms 喂给流式引擎。
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
            val engine = asr ?: run {
                _notice.value = "模型还没准备好"
                return@launch
            }

            _busy.value = true
            _partial.value = ""
            engine.start()
            var offset = 0
            while (offset < pcm.size) {
                val count = minOf(1600, pcm.size - offset)
                val chunk = pcm.copyOfRange(offset, offset + count)
                val text = try {
                    engine.accept(chunk)
                } catch (e: Throwable) {
                    ""
                }
                if (text.isNotEmpty()) _partial.value = text
                offset += count
                delay(25)
            }
            val started = System.currentTimeMillis()
            val raw = try {
                engine.finish()
            } catch (e: Throwable) {
                ""
            }
            val elapsed = System.currentTimeMillis() - started
            _partial.value = ""
            if (raw.isBlank()) {
                _notice.value = "自检没听到内容"
                _busy.value = false
                return@launch
            }
            val corrected = corrector.correct(raw)
            _last.value = Transcript(corrected.text, elapsed, "zh", AckState.Pending, corrected.changes)
            val delivered = client.sendText(corrected.text, pcm.size * 1000 / Recorder.SAMPLE_RATE, "zh")
            _last.value = _last.value?.copy(
                ack = if (delivered) AckState.Delivered else AckState.Failed
            )
            _busy.value = false
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

    fun formatMs(ms: Long): String = if (ms >= 1000) {
        val tenths = (ms / 100.0).roundToInt() / 10.0
        "${tenths}s"
    } else {
        "${ms}ms"
    }
}
