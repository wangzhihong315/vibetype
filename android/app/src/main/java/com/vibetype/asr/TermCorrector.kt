package com.vibetype.asr

import android.content.res.AssetManager
import org.json.JSONObject

data class Corrected(val text: String, val changes: Int)

/**
 * 术语纠正层：ASR 之后、上屏之前跑，纯本地、毫秒级。
 *
 * 三类规则：
 * 1. 别名：five type -> VibeType，闪电胶囊 -> 闪念胶囊
 * 2. 上下文：跑一遍词 -> 跑一遍 CI
 * 3. 英文近似词：bran -> branch，forth -> force
 */
class TermCorrector(rawJson: String) {

    constructor(assets: AssetManager) : this(
        assets.open("terms.json").bufferedReader().use { it.readText() }
    )

    private val aliases = mutableListOf<Pair<Regex, String>>()
    private val context = mutableListOf<Pair<Regex, String>>()
    private val latin = mutableMapOf<String, String>()

    init {
        val json = JSONObject(rawJson)

        json.optJSONArray("aliases")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONArray(i)
                aliases += phraseRegex(item.getString(0)) to item.getString(1)
            }
        }
        json.optJSONArray("context")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONArray(i)
                context += Regex(item.getString(0)) to item.getString(1)
            }
        }
        json.optJSONObject("latin")?.let { obj ->
            for (key in obj.keys()) {
                latin[key.lowercase()] = obj.getString(key)
            }
        }
    }

    fun correct(input: String): Corrected {
        var text = input
        var changes = 0

        // 先修英文单词/缩写的大小写，再跑短语别名。
        // 反过来的话 "node js" -> "Node.js" 会被后面的 js -> JS 再改坏成 Node.JS。
        val latinPattern = Regex("[A-Za-z]+")
        text = latinPattern.replace(text) { match ->
            val original = match.value
            val fixed = latin[original.lowercase()]
            if (fixed != null && fixed != original) {
                changes += 1
                fixed
            } else {
                original
            }
        }

        for ((pattern, replacement) in aliases) {
            val next = pattern.replace(text, replacement)
            if (next != text) {
                changes += 1
                text = next
            }
        }
        for ((pattern, replacement) in context) {
            val next = pattern.replace(text, replacement)
            if (next != text) {
                changes += 1
                text = next
            }
        }
        return Corrected(text, changes)
    }

    /** 短语别名按词边界匹配，避免 "ai" 命中 "said" 这类子串误伤。 */
    private fun phraseRegex(raw: String): Regex {
        val prefix = if (raw.firstOrNull()?.isAsciiAlnum() == true) "(?<![A-Za-z0-9])" else ""
        val suffix = if (raw.lastOrNull()?.isAsciiAlnum() == true) "(?![A-Za-z0-9])" else ""
        return Regex(prefix + Regex.escape(raw) + suffix, RegexOption.IGNORE_CASE)
    }

    private fun Char.isAsciiAlnum(): Boolean = code < 128 && isLetterOrDigit()
}
