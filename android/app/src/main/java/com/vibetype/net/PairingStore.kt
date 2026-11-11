package com.vibetype.net

import android.content.Context
import android.content.SharedPreferences

/** 配对信息：桌面的地址、端口、长期 token。 */
class PairingStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vibetype.pairing", Context.MODE_PRIVATE)

    var host: String?
        get() = prefs.getString("host", null)
        set(value) = prefs.edit().putString("host", value).apply()

    var port: Int
        get() = prefs.getInt("port", 47823)
        set(value) = prefs.edit().putInt("port", value).apply()

    /** 配对码或长期 deviceToken，都放在这个字段里。 */
    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().putString("token", value).apply()

    /** 桌面在 welcome 里下发的长期 token。 */
    var deviceToken: String?
        get() = prefs.getString("deviceToken", null)
        set(value) = prefs.edit().putString("deviceToken", value).apply()

    var desktopName: String?
        get() = prefs.getString("desktopName", null)
        set(value) = prefs.edit().putString("desktopName", value).apply()

    /** 左手模式：触控板右缘滚动条和按键区一起镜像。 */
    var leftHanded: Boolean
        get() = prefs.getBoolean("leftHanded", false)
        set(value) = prefs.edit().putBoolean("leftHanded", value).apply()

    /** 手势说明第一次自动弹，之后从设置里进。 */
    var seenGestures: Boolean
        get() = prefs.getBoolean("seenGestures", false)
        set(value) = prefs.edit().putBoolean("seenGestures", value).apply()

    val paired: Boolean
        get() = !host.isNullOrBlank() && !token.isNullOrBlank()

    fun save(host: String, port: Int, token: String) {
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("token", token)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
