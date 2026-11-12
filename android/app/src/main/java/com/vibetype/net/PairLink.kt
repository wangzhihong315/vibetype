package com.vibetype.net

import android.net.Uri

/** 电脑端二维码里的配对信息：vibetype://pair?h=&p=&c= */
data class PairLink(val host: String, val port: Int, val code: String)

object PairLinks {

    fun parse(raw: String): PairLink? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("vibetype", ignoreCase = true)) return null
        if (uri.host?.equals("pair", ignoreCase = true) != true) return null
        val host = uri.getQueryParameter("h")?.trim().orEmpty()
        val code = uri.getQueryParameter("c")?.trim().orEmpty()
        if (host.isEmpty() || code.isEmpty()) return null
        val port = uri.getQueryParameter("p")
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: 47823
        return PairLink(host, port, code)
    }
}
