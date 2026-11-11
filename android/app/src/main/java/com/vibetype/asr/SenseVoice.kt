package com.vibetype.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

data class AsrResult(
    val text: String,
    val lang: String,
    val emotion: String,
    val event: String,
    val elapsedMs: Long,
)

/**
 * SenseVoiceSmall int8，跑在手机 CPU 上。
 * 非自回归，一段话一次前向；不用网络。
 */
class SenseVoice(modelDir: File, threads: Int = 4) {

    private val recognizer: OfflineRecognizer

    init {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(modelDir, "model.int8.onnx").absolutePath,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(config = config)
    }

    fun transcribe(pcm16: ShortArray): AsrResult {
        val started = System.currentTimeMillis()
        val stream = recognizer.createStream()
        try {
            val samples = FloatArray(pcm16.size) { pcm16[it] / 32768f }
            stream.acceptWaveform(samples, 16_000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return AsrResult(
                text = result.text.trim(),
                lang = result.lang.ifBlank { "auto" },
                emotion = result.emotion,
                event = result.event,
                elapsedMs = System.currentTimeMillis() - started,
            )
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer.release()
    }

    companion object {
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"

        fun modelDir(base: File): File = File(base, "models/sensevoice")

        fun isReady(dir: File): Boolean {
            val model = File(dir, MODEL_FILE)
            return model.isFile && model.length() > 0 && File(dir, TOKENS_FILE).isFile
        }
    }
}
