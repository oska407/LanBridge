package com.lanbridge.util

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 热点网关 IP 获取（R8 多源回退）：
 * DhcpInfo.serverAddress → NetworkInterface 枚举 192.168.x.x → 预置 192.168.43.1。
 */
object HotspotIp {

    fun get(context: Context): String =
        networkInterface() ?: localDhcpInfo(context) ?: FALLBACK

    /** 仅当 DhcpInfo 的 serverAddress 确实是本机网卡地址时才用，
     * 否则手机连普通 WiFi 时会拿到路由器地址，导致 bind 失败（闪退）。 */
    private fun localDhcpInfo(ctx: Context): String? {
        val ip = dhcpInfo(ctx) ?: return null
        return if (isLocalAddress(ip)) ip else null
    }

    private fun isLocalAddress(ip: String): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .any { it.hostAddress == ip }
    }.getOrDefault(false)

    private fun dhcpInfo(ctx: Context): String? = runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val dhcp = wm?.dhcpInfo ?: return@runCatching null
        if (dhcp.serverAddress == 0) null else intToIp(dhcp.serverAddress)
    }.getOrNull()

    /**
     * 网卡选择（v1.1.9f 重写优先级）：
     * 1) Wi-Fi/热点网卡（wlan*/ap*/softap*）最优先；
     * 2) 其余非蜂窝网卡的 192.168.*；
     * 3) 兜底：任意非蜂窝网卡的私网地址。
     * 此前任意网卡的 10.x 都可入选且 ".1 结尾优先"——蜂窝网卡(rmnet/ccmni/ccinet)的
     * CGNAT 地址恰好可能以 .1 结尾，一旦抢占，服务会绑到 PC 永远不可达的蜂窝网卡上，
     * 网络回调一触发就"服务重启→PC 断连→无法下载"。
     */
    private fun networkInterface(): String? = runCatching {
        val nifs = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filter { nif -> CELLULAR_PREFIXES.none { nif.name.contains(it) } }
            .toList()
        fun ips(nif: NetworkInterface) = nif.inetAddresses.asSequence()
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.startsWith("192.168.") || it.startsWith("172.") || it.startsWith("10.") }
            .toList()
        val wlanIps = nifs.filter { it.name.contains("wlan") || it.name.contains("ap") }
            .flatMap { ips(it) }
        val lanIps = nifs.flatMap { ips(it) }
        wlanIps.firstOrNull { it.endsWith(".1") }
            ?: wlanIps.firstOrNull()
            ?: lanIps.firstOrNull { it.startsWith("192.168.") }
            ?: lanIps.firstOrNull { it.endsWith(".1") }
            ?: lanIps.firstOrNull()
    }.getOrNull()

    private val CELLULAR_PREFIXES = listOf("rmnet", "ccmni", "ccinet", "tun", "tap", "usb")

    private fun intToIp(addr: Int): String =
        "${addr and 0xFF}.${addr shr 8 and 0xFF}.${addr shr 16 and 0xFF}.${addr shr 24 and 0xFF}"

    private const val FALLBACK = "192.168.43.1"
}
