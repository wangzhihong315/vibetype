package com.vibetype.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 16 kHz / 单声道 / PCM16 录音，按住说话期间一直往内存里攒。
 * 语音量很小（1 分钟约 1.9 MB），不需要落盘。
 */
class Recorder(private val sampleRate: Int = SAMPLE_RATE) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val chunks = Collections.synchronizedList(ArrayList<ShortArray>())

    @Volatile
    private var recording = false

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    val isRecording: Boolean
        get() = recording

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false

        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        } catch (e: Exception) {
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        chunks.clear()
        _level.value = 0f
        recording = true
        record = rec
        rec.startRecording()

        // 100 ms 一帧，UI 的光晕和能量都按这个节奏更新
        thread = Thread {
            val buffer = ShortArray(sampleRate / 10)
            while (recording) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) {
                    chunks.add(buffer.copyOf(read))
                    _level.value = rms(buffer, read)
                }
            }
        }.also { it.start() }
        return true
    }

    /** 停止并返回整段 PCM16（小端）。 */
    fun stop(): ShortArray {
        recording = false
        thread?.join(600)
        thread = null
        record?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        record = null
        _level.value = 0f

        val all = synchronized(chunks) { chunks.toList() }
        val total = all.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (chunk in all) {
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            offset += chunk.size
        }
        chunks.clear()
        return out
    }

    fun cancel() {
        recording = false
        thread?.join(400)
        thread = null
        record?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        record = null
        chunks.clear()
        _level.value = 0f
    }

    private fun rms(buffer: ShortArray, count: Int): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        val value = (sqrt(sum / count) / 32768.0).toFloat()
        // 人声的 RMS 一般在 0.02–0.25，放大到 0–1 给 UI 用
        return (value * 5f).coerceIn(0f, 1f)
    }

    /** 去掉首尾静音，保留一点余量，避免吞字。 */
    fun trimSilence(pcm: ShortArray): ShortArray {
        val frame = sampleRate / 100
        if (pcm.size < frame * 3) return ShortArray(0)

        val frames = pcm.size / frame
        val energy = FloatArray(frames)
        var peak = 0f
        for (i in 0 until frames) {
            var sum = 0.0
            for (j in 0 until frame) {
                val v = pcm[i * frame + j].toDouble()
                sum += v * v
            }
            val value = sqrt(sum / frame).toFloat()
            energy[i] = value
            peak = max(peak, value)
        }

        if (peak < 300f) return ShortArray(0)

        val threshold = max(320f, peak * 0.06f)
        var start = 0
        while (start < frames && energy[start] < threshold) start++
        var end = frames - 1
        while (end > start && energy[end] < threshold) end--
        if (end <= start) return ShortArray(0)

        val pad = 15 // 150 ms
        val from = max(0, start - pad) * frame
        val to = minOf(pcm.size, (end + 1 + pad) * frame)
        return pcm.copyOfRange(from, to)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
