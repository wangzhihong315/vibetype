package com.vibetype.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

data class AsrResult(
    val text: String,
    val lang: String,
    val emotion: String,
    val event: String,
    val elapsedMs: Long,
)

/**
 * SenseVoiceSmall int8，模型随 APK 一起打包，直接从 assets 加载。
 * 非自回归，一段话一次前向；不用网络。
 */
class SenseVoice(assets: AssetManager, threads: Int = 4) {

    private val recognizer: OfflineRecognizer

    init {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$ASSET_DIR/model.int8.onnx",
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = "$ASSET_DIR/tokens.txt",
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(assetManager = assets, config = config)
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
        const val ASSET_DIR = "models/sensevoice"

        fun isReady(assets: AssetManager): Boolean = try {
            assets.open("$ASSET_DIR/model.int8.onnx").close()
            assets.open("$ASSET_DIR/tokens.txt").close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
