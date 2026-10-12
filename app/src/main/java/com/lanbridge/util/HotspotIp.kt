package com.lanbridge.util

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 绑定地址选择（R8 多源回退）：
 * 网卡枚举（优先 wlan/ap 私网段）→ DhcpInfo（需确认为本机地址）→ 0.0.0.0（绑所有网卡）。
 *
 * v1.1.9i（修复 `BindException: Cannot assign requested address` 闪退）：
 * **硬约束：返回的地址必须是此刻本机网卡上真实存在的地址。**
 * 旧版最后兜底写死 192.168.43.1（安卓热点默认网关），只有手机真的开着热点时它才是本机地址。
 * 一旦手机只连蜂窝数据、或 Wi-Fi 尚未取得 IPv4，该地址在本机根本不存在，bind() 抛
 * EADDRNOTAVAIL；而这个异常是在 Ktor 内部协程里异步抛出的，会绕过调用方的 try/catch
 * 直接崩溃进程（详见 EmbeddedServer.start 的注释）。故兜底改为 0.0.0.0（永远可绑），
 * 并在返回前用 isLocalAddress 复核枚举 / DhcpInfo 的结果，堵住「枚举到已消失地址」的竞态。
 */
object HotspotIp {

    /** 通配地址：绑定所有网卡，永远可绑（仅在没有可用局域网地址时使用） */
    const val BIND_ALL = "0.0.0.0"

    /** 绑定地址：可用的本机私网地址优先；一个都没有就绑 0.0.0.0（绝不返回不可绑的地址）。 */
    fun get(context: Context): String =
        networkInterface()?.takeIf { isLocalAddress(it) }
            ?: localDhcpInfo(context)?.takeIf { isLocalAddress(it) }
            ?: BIND_ALL

    /** 展示用地址（设置页 / 二维码 / 提示语）：有本机私网地址就显示它，否则显示 0.0.0.0。 */
    fun displayIp(): String = networkInterface()?.takeIf { isLocalAddress(it) } ?: BIND_ALL

    /** 仅当 DhcpInfo 的 serverAddress 确实是本机网卡地址时才用，
     * 否则手机连普通 WiFi 时会拿到路由器地址，导致 bind 失败（闪退）。 */
    private fun localDhcpInfo(ctx: Context): String? {
        val ip = dhcpInfo(ctx) ?: return null
        return if (isLocalAddress(ip)) ip else null
    }

    /** 该 IPv4 是否是当前某块已启用网卡上的地址（bind 前置校验，防 EADDRNOTAVAIL）。 */
    private fun isLocalAddress(ip: String): Boolean {
        if (ip.isBlank() || ip == BIND_ALL) return false
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .any { it.hostAddress == ip }
        }.getOrDefault(false)
    }

    private fun dhcpInfo(ctx: Context): String? = runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val dhcp = wm?.dhcpInfo ?: return@runCatching null
        if (dhcp.serverAddress == 0) null else intToIp(dhcp.serverAddress)
    }.getOrNull()

    /**
     * 网卡选择（v1.1.9f 重写优先级）：
     * 1) Wi-Fi/热点网卡（wlan0、ap0、softap0 等）最优先；
     * 2) 其余非蜂窝网卡的 192.168. 段地址；
     * 3) 兜底：任意非蜂窝网卡的私网地址。
     * 此前任意网卡的 10.x 都可入选且「.1 结尾优先」——蜂窝网卡(rmnet/ccmni/ccinet)的
     * CGNAT 地址恰好可能以 .1 结尾，一旦抢占，服务会绑到 PC 永远不可达的蜂窝网卡上，
     * 网络回调一触发就「服务重启、PC 断连、无法下载」。
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
}
