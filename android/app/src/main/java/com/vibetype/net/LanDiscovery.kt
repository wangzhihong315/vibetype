package com.vibetype.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * 局域网里的自动发现。电脑换 IP 之后，手机不用再问用户新地址。
 *
 * 协议很短：手机向 47824 广播 "VIBETYPE_DISCOVER"，桌面端回一个 JSON，
 * 里面带稳定 id、TCP 端口和平台。身份校验仍然走 TCP 的 deviceToken。
 */
object LanDiscovery {

    private const val PORT = 47824
    private const val MAGIC = "VIBETYPE_DISCOVER"

    data class Peer(val host: String, val port: Int, val desktopId: String)

    suspend fun find(expectDesktopId: String?, timeoutMs: Long = 1_600): List<Peer> =
        withContext(Dispatchers.IO) {
            runCatching { scan(expectDesktopId, timeoutMs) }.getOrDefault(emptyList())
        }

    private suspend fun scan(expectDesktopId: String?, timeoutMs: Long): List<Peer> {
            val peers = LinkedHashMap<String, Peer>()
            val socket = DatagramSocket()
            try {
                socket.broadcast = true
                socket.soTimeout = 250
                val targets = broadcastTargets()
                val payload = MAGIC.toByteArray(Charsets.UTF_8)
                repeat(2) {
                    for (target in targets) {
                        runCatching {
                            socket.send(DatagramPacket(payload, payload.size, target, PORT))
                        }
                    }
                    delay(120)
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(512)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (json.optInt("vibetype") != 1) continue
                    val id = json.optString("id")
                    if (!expectDesktopId.isNullOrBlank() && id != expectDesktopId) continue
                    val host = packet.address?.hostAddress ?: continue
                    val port = json.optInt("port", 47823)
                    if (port !in 1..65535) continue
                    peers["$host:$port"] = Peer(host, port, id)
                }
            } finally {
                runCatching { socket.close() }
            }
            return peers.values.toList()
    }

    /** 所有网卡的广播地址 + 全局广播。跨网段时广播到不了，仍可手动填地址。 */
    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.interfaceAddresses) {
                    address.broadcast?.let { out.add(it) }
                }
            }
        }
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        return out.toList()
    }
}
