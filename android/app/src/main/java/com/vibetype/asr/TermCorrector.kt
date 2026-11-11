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
class TermCorrector(assets: AssetManager) {

    private val aliases = mutableListOf<Pair<Regex, String>>()
    private val context = mutableListOf<Pair<Regex, String>>()
    private val latin = mutableMapOf<String, String>()

    init {
        val raw = assets.open("terms.json").bufferedReader().use { it.readText() }
        val json = JSONObject(raw)

        json.optJSONArray("aliases")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONArray(i)
                aliases += Regex(Regex.escape(item.getString(0)), RegexOption.IGNORE_CASE) to
                    item.getString(1)
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
        return Corrected(text, changes)
    }
}
