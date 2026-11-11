package com.vibetype.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * X-ASR 960ms 流式 zh-en 模型（Apache-2.0）。
 *
 * 录音过程中不断喂 16 kHz PCM，随时可以拿到当前文本做实时显示；
 * 松手后补 800 ms 静音把最后一段冲出来，再取最终结果。
 */
class XAsr(assets: AssetManager, threads: Int = 2) {

    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream? = null

    init {
        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$ASSET_DIR/encoder.int8.onnx",
                    decoder = "$ASSET_DIR/decoder.onnx",
                    joiner = "$ASSET_DIR/joiner.int8.onnx",
                ),
                tokens = "$ASSET_DIR/tokens.txt",
                numThreads = threads,
                modelingUnit = "bpe",
                bpeVocab = "$ASSET_DIR/bpe.vocab",
                debug = false,
            ),
            decodingMethod = "modified_beam_search",
            hotwordsFile = "$ASSET_DIR/hotwords.txt",
            hotwordsScore = 2.0f,
            enableEndpoint = false,
            maxActivePaths = 4,
        )
        recognizer = OnlineRecognizer(assetManager = assets, config = config)
    }

    @Synchronized
    fun start() {
        stream?.release()
        stream = recognizer.createStream()
    }

    /** 喂一段音频，返回当前累计文本。 */
    @Synchronized
    fun accept(pcm16: ShortArray): String {
        val current = stream ?: return ""
        val samples = FloatArray(pcm16.size) { pcm16[it] / 32768f }
        current.acceptWaveform(samples, SAMPLE_RATE)
        while (recognizer.isReady(current)) {
            recognizer.decode(current)
        }
        return recognizer.getResult(current).text.trim()
    }

    /** 补一段静音，收尾并返回最终文本。 */
    @Synchronized
    fun finish(tailMs: Int = 1200): String {
        val current = stream ?: return ""
        val tail = FloatArray(SAMPLE_RATE * tailMs / 1000)
        current.acceptWaveform(tail, SAMPLE_RATE)
        current.inputFinished()
        while (recognizer.isReady(current)) {
            recognizer.decode(current)
        }
        val text = recognizer.getResult(current).text.trim()
        current.release()
        stream = null
        return text
    }

    @Synchronized
    fun cancel() {
        stream?.release()
        stream = null
    }

    @Synchronized
    fun release() {
        stream?.release()
        stream = null
        recognizer.release()
    }

    companion object {
        const val ASSET_DIR = "models/x-asr"
        private const val SAMPLE_RATE = 16_000

        fun isReady(assets: AssetManager): Boolean = try {
            listOf(
                "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx",
                "tokens.txt", "bpe.vocab", "hotwords.txt",
            ).forEach { assets.open("$ASSET_DIR/$it").close() }
            true
        } catch (e: Exception) {
            false
        }
    }
}
