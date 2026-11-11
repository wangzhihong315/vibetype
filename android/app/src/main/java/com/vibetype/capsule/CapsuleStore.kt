package com.vibetype.capsule

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一条闪念。灵感本身，不是待办。 */
data class Capsule(
    val id: String,
    val text: String,
    val createdAt: Long,
    /** 录音时长，毫秒；0 表示手动敲进去的。 */
    val durationMs: Int = 0,
    /** 是否已经同步到电脑。 */
    val synced: Boolean = false,
)

/**
 * 胶囊只存在手机本地：一个 JSON 文件，读进内存后整体重写。
 * 条数不会多到需要数据库，简单可靠比抽象重要。
 */
class CapsuleStore(context: Context) {

    private val file = File(context.filesDir, "capsules.json")
    private val _items = MutableStateFlow<List<Capsule>>(emptyList())
    val items: StateFlow<List<Capsule>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        val parsed = try {
            JSONArray(file.readText())
        } catch (e: Throwable) {
            JSONArray()
        }
        val list = ArrayList<Capsule>(parsed.length())
        for (i in 0 until parsed.length()) {
            val item = parsed.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val text = item.optString("text")
            if (id.isEmpty() || text.isEmpty()) continue
            list += Capsule(
                id = id,
                text = text,
                createdAt = item.optLong("createdAt"),
                durationMs = item.optInt("durationMs"),
                synced = item.optBoolean("synced"),
            )
        }
        _items.value = list.sortedByDescending { it.createdAt }
    }

    suspend fun add(text: String, durationMs: Int): Capsule = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        val capsule = Capsule(
            id = UUID.randomUUID().toString(),
            text = trimmed,
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs,
            synced = false,
        )
        _items.value = (listOf(capsule) + _items.value).sortedByDescending { it.createdAt }
        persist()
        capsule
    }

    suspend fun updateText(id: String, text: String) = withContext(Dispatchers.IO) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(text = text.trim()) else it
        }
        persist()
    }

    suspend fun markSynced(id: String) = withContext(Dispatchers.IO) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(synced = true) else it
        }
        persist()
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        _items.value = _items.value.filterNot { it.id == id }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        _items.value.forEach { capsule ->
            array.put(
                JSONObject().apply {
                    put("id", capsule.id)
                    put("text", capsule.text)
                    put("createdAt", capsule.createdAt)
                    put("durationMs", capsule.durationMs)
                    put("synced", capsule.synced)
                }
            )
        }
        runCatching {
            val temp = File(file.parentFile, "capsules.json.tmp")
            temp.writeText(array.toString())
            temp.renameTo(file)
        }
    }
}
