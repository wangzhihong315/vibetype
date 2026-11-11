package com.vibetype

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibetype.asr.Corrected
import com.vibetype.asr.TermCorrector
import com.vibetype.asr.XAsr
import com.vibetype.audio.Recorder
import com.vibetype.capsule.Capsule
import com.vibetype.capsule.CapsuleStore
import com.vibetype.net.ConnState
import com.vibetype.net.DesktopClient
import com.vibetype.net.PairingStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val capsuleStore = CapsuleStore(app)

    val capsules: StateFlow<List<Capsule>> = capsuleStore.items

    /** 胶囊录制的实时文字，和语音键分开，浮层颜色也不一样。 */
    private val _capsulePartial = MutableStateFlow("")
    val capsulePartial: StateFlow<String> = _capsulePartial.asStateFlow()

    private val _capsuleRecording = MutableStateFlow(false)
    val capsuleRecording: StateFlow<Boolean> = _capsuleRecording.asStateFlow()

    private val _capsuleSaving = MutableStateFlow(false)
    val capsuleSaving: StateFlow<Boolean> = _capsuleSaving.asStateFlow()

    /** 刚存下一条胶囊时发一次，用来放「飞进胶囊」的动效。 */
    private val _capsuleSaved = MutableSharedFlow<Capsule>(extraBufferCapacity = 4)
    val capsuleSaved: SharedFlow<Capsule> = _capsuleSaved.asSharedFlow()

    val level: StateFlow<Float> = recorder.level
    val connection: StateFlow<ConnState> = client.state
    val keymap = client.keymap

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

    private val _leftHanded = MutableStateFlow(settings.leftHanded)
    val leftHanded: StateFlow<Boolean> = _leftHanded.asStateFlow()

    private val _pointerSpeed = MutableStateFlow(settings.pointerSpeed)
    val pointerSpeed: StateFlow<Float> = _pointerSpeed.asStateFlow()

    private val _naturalScroll = MutableStateFlow(settings.naturalScroll)
    val naturalScroll: StateFlow<Boolean> = _naturalScroll.asStateFlow()

    private var asr: XAsr? = null

    /** debug 包才显示「自检」入口。 */
    val debuggable = (app.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    init {
        client.start()
        loadModel()
        viewModelScope.launch { capsuleStore.load() }
        viewModelScope.launch {
            client.state.collect { state ->
                when (state) {
                    is ConnState.NeedsPairing -> {
                        settings.token = null
                        _paired.value = false
                    }
                    is ConnState.Connected -> syncPendingCapsules()
                    else -> Unit
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
        if (_recording.value || _busy.value || _capsuleRecording.value || _capsuleSaving.value) return
        if (_model.value !is ModelState.Ready) return
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

    /**
     * 胶囊录制：和语音键走同一套流式引擎，但文字不上屏，落成本地一条胶囊。
     * 没连电脑也能用，胶囊本来就是留给自己回头看的。
     */
    fun beginCapsule() {
        if (_recording.value || _capsuleRecording.value || _busy.value || _capsuleSaving.value) return
        if (_model.value !is ModelState.Ready) {
            _notice.value = "模型还没准备好"
            return
        }
        _notice.value = null
        _capsulePartial.value = ""
        val engine = asr ?: return
        engine.start()
        recorder.onChunk = { chunk ->
            val text = runCatching { engine.accept(chunk) }.getOrDefault("")
            if (text.isNotEmpty()) _capsulePartial.value = text
        }
        if (recorder.start()) {
            _capsuleRecording.value = true
        } else {
            recorder.onChunk = null
            engine.cancel()
            _notice.value = "麦克风打不开，检查录音权限"
        }
    }

    fun endCapsule() {
        if (!_capsuleRecording.value) return
        _capsuleRecording.value = false
        recorder.onChunk = null
        val pcm = recorder.stop()
        val minSamples = Recorder.SAMPLE_RATE * 4 / 10
        if (pcm.size < minSamples) {
            asr?.cancel()
            _capsulePartial.value = ""
            _notice.value = "说话时间太短"
            return
        }
        saveCapsule(pcm.size * 1000 / Recorder.SAMPLE_RATE)
    }

    fun cancelCapsule() {
        if (!_capsuleRecording.value) return
        _capsuleRecording.value = false
        recorder.onChunk = null
        recorder.cancel()
        asr?.cancel()
        _capsulePartial.value = ""
    }

    private fun saveCapsule(durationMs: Int) {
        val engine = asr ?: return
        _capsuleSaving.value = true
        viewModelScope.launch(Dispatchers.Default) {
            val raw = runCatching { engine.finish() }.getOrDefault("")
            _capsulePartial.value = ""
            if (raw.isBlank()) {
                _capsuleSaving.value = false
                _notice.value = "没听清，再来一次"
                return@launch
            }
            val text = corrector.correct(raw).text
            val capsule = capsuleStore.add(text, durationMs)
            client.sendCapsule(capsule.id, capsule.text, capsule.createdAt, capsule.durationMs) {
                viewModelScope.launch { capsuleStore.markSynced(capsule.id) }
            }
            _capsuleSaving.value = false
            _capsuleSaved.tryEmit(capsule)
        }
    }

    /** 连上电脑后把还没抄过去的胶囊补发一遍；手机离线时记的灵感不会丢。 */
    private fun syncPendingCapsules() {
        val pending = capsules.value.filter { !it.synced }
        if (pending.isEmpty()) return
        pending.forEach { capsule ->
            client.sendCapsule(
                capsule.id,
                capsule.text,
                capsule.createdAt,
                capsule.durationMs,
            ) {
                viewModelScope.launch { capsuleStore.markSynced(capsule.id) }
            }
        }
    }

    fun deleteCapsule(id: String) {
        viewModelScope.launch { capsuleStore.remove(id) }
    }

    fun renameCapsule(id: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { capsuleStore.updateText(id, text) }
    }

    /** 把一条胶囊打进电脑光标，成功后标记为已同步。 */
    fun sendCapsuleToDesktop(capsule: Capsule) {
        if (client.state.value !is ConnState.Connected) {
            _notice.value = "先连上电脑"
            return
        }
        viewModelScope.launch {
            val ok = client.sendText(capsule.text, 0, "zh")
            if (ok) {
                capsuleStore.markSynced(capsule.id)
                _notice.value = "已发到电脑光标"
            } else {
                _notice.value = "没发出去，检查连接"
            }
        }
    }

    fun copyCapsule(capsule: Capsule) {
        val app = getApplication<Application>()
        val manager = app.getSystemService(android.content.ClipboardManager::class.java)
        manager?.setPrimaryClip(
            android.content.ClipData.newPlainText("VibeType 胶囊", capsule.text)
        )
        _notice.value = "已复制"
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
            val audioMs = pcm.size * 1000 / Recorder.SAMPLE_RATE
            _notice.value = "自检 · 音频 %.1fs · 转写 %dms · RTF %.2f".format(
                audioMs / 1000.0, elapsed, elapsed.toDouble() / audioMs
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

    fun sendMove(dx: Float, dy: Float) = client.sendPointer("move", "", dx, dy)

    fun sendScroll(dx: Float, dy: Float) = client.sendPointer("scroll", "", dx, dy)

    fun sendClick(button: String) = client.sendPointer("click", button)

    /** 长按拖动：先按住左键，移动照旧走 move，松手再抬起。 */
    fun sendPointerDown() = client.sendPointer("down", "left")

    fun sendPointerUp() = client.sendPointer("up", "left")

    fun sendKey(name: String) = client.sendKey(name)

    fun setLeftHanded(value: Boolean) {
        settings.leftHanded = value
        _leftHanded.value = value
    }

    fun setPointerSpeed(value: Float) {
        val clamped = value.coerceIn(0.6f, 2f)
        settings.pointerSpeed = clamped
        _pointerSpeed.value = clamped
    }

    fun setNaturalScroll(value: Boolean) {
        settings.naturalScroll = value
        _naturalScroll.value = value
    }

    /** 结果卡看一会儿就收起来，失败时留着。 */
    fun clearLast() {
        if (_last.value?.ack != AckState.Failed) {
            _last.value = null
        }
        _partial.value = if (_recording.value) _partial.value else ""
    }

    fun gesturesSeen(): Boolean = settings.seenGestures

    fun markGesturesSeen() {
        settings.seenGestures = true
    }

    fun pair(host: String, port: Int, code: String) {        settings.save(host.trim(), port, code.trim())
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
