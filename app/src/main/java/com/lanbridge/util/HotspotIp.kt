package com.lanbridge.util

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 热点网关 IP 获取（R8 多源回退）：
 * DhcpInfo.serverAddress → NetworkInterface 枚举 192.168.x.x → 预置 192.168.43.1。
 */
object HotspotIp {

    fun get(context: Context): String =
        dhcpInfo(context) ?: networkInterface() ?: FALLBACK

    private fun dhcpInfo(ctx: Context): String? = runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val dhcp = wm?.dhcpInfo ?: return@runCatching null
        if (dhcp.serverAddress == 0) null else intToIp(dhcp.serverAddress)
    }.getOrNull()

    private fun networkInterface(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.startsWith("192.168.") || it.startsWith("172.") || it.startsWith("10.") }
            .toList()
        // 热点网关通常是 .1；优先取
        candidates.firstOrNull { it.endsWith(".1") } ?: candidates.firstOrNull()
    }.getOrNull()

    private fun intToIp(addr: Int): String =
        "${addr and 0xFF}.${addr shr 8 and 0xFF}.${addr shr 16 and 0xFF}.${addr shr 24 and 0xFF}"

    private const val FALLBACK = "192.168.43.1"
}
