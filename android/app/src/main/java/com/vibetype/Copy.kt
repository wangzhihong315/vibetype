package com.vibetype

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 界面语言。Auto 跟随系统，其它为强制指定。 */
enum class AppLanguage(val id: String) {
    Auto("auto"),
    Chinese("zh"),
    English("en");

    companion object {
        fun fromId(id: String?): AppLanguage = entries.firstOrNull { it.id == id } ?: Auto
    }
}

/** 外观。Auto 跟随系统，其它为强制指定。 */
enum class ThemeMode(val id: String) {
    Auto("auto"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: Auto
    }
}

/**
 * 文案只有一个入口。文案散落在 Composable 之外（通知、语音提示），
 * 所以用全局状态而不是 CompositionLocal。
 */
object Copy {

    var english by mutableStateOf(false)
        private set

    fun apply(language: AppLanguage, systemChinese: Boolean) {
        english = when (language) {
            AppLanguage.Auto -> !systemChinese
            AppLanguage.Chinese -> false
            AppLanguage.English -> true
        }
    }

    fun t(zh: String, en: String): String = if (english) en else zh
}
