package com.lanbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.lanbridge.R
import com.lanbridge.chat.ChatActivity
import com.lanbridge.server.EmbeddedServer
import com.lanbridge.server.FileStore
import com.lanbridge.server.SessionState
import com.lanbridge.server.TransferEngine
import com.lanbridge.settings.SettingsRepository
import com.lanbridge.util.CrashLogger
import com.lanbridge.util.HotspotIp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 前台服务（T27）：Ktor 生命周期承载 + 常驻通知（复制链接/停止服务 action）+ WakeLock。
 * Android 14 强制 foregroundServiceType="dataSync"（Manifest 已声明）。
 */
class BridgeForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e -> CrashLogger.logThrowable(e, "service-scope") })
    private var server: EmbeddedServer? = null
    private val launchLock = Mutex() // 串行化 launchServer，防止重复 startForegroundService 并发起多实例
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentIp: String = ""
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastRestartAt = 0L // 服务重启防抖（网络回调可能高频触发）

    override fun onCreate() {
        super.onCreate()
        createChannel()
        TransferEngine.init(FileStore(FileStore.dirOf(this)), this)
        // 启动即按水位清理一次残留（上次异常退出可能留下大文件）
        scope.launch {
            com.lanbridge.util.CacheGuard.trim(
                this@BridgeForegroundService,
                SettingsRepository.get(this@BridgeForegroundService).cacheLimitMb)
        }
    }

    /** Android 8+ 必须预先创建通知渠道，否则前台通知不显示、服务可能被判定未前台化。 */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_COPY_LINK -> { copyLink(); return START_STICKY }
        }
        // Android 14 上 startForeground 类型校验失败会直接抛异常带崩进程：
        // 先落盘记录根因再停服，避免"屡次停止运行"却查无日志
        try {
            startForeground(NOTIF_ID, buildNotification(waiting = true))
        } catch (e: Throwable) {
            CrashLogger.logThrowable(e, "start-foreground")
            stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        val ip = HotspotIp.get(this)
        currentIp = ip
        scope.launch { launchServer(ip) }
        registerNetworkWatch() // 网络切换后地址自愈（v1.1.5）
    }

    /** 监听默认网络变化：热点↔WiFi 切换、IP 变更后自动用新地址重启服务，
     *  并刷新通知与聊天区那条地址消息（不再需要手动重启）。 */
    private fun registerNetworkWatch() {
        if (netCallback != null) return
        val cm = runCatching { getSystemService(ConnectivityManager::class.java) }.getOrNull() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onNetworkChanged("网络已连接") }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { onNetworkChanged("链路属性变化") }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { onNetworkChanged("网络能力变化") }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure { CrashLogger.logThrowable(it, "net-watch") }
        netCallback = cb
    }

    private fun onNetworkChanged(reason: String) {
        val newIp = HotspotIp.get(this)
        if (newIp.isBlank() || newIp == currentIp) return // 抖动/未真正变化，忽略
        // 防抖（v1.1.9f）：5 秒内不重复重启。onCapabilitiesChanged 高频回调期间
        // 网卡枚举可能瞬时抖动，重启服务会踢掉所有 PC 连接，表现为"网络会中断、无法下载"
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartAt < 5000) return
        lastRestartAt = now
        CrashLogger.log("net", "IP 变更 $currentIp → $newIp（$reason），重启服务以生效")
        currentIp = newIp
        scope.launch {
            server?.stop()
            server = null
            launchServer(newIp)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@BridgeForegroundService,
                    "网络已切换，新地址 http://$newIp:${SettingsRepository.get(this@BridgeForegroundService).serverPort}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun launchServer(ip: String) {
        // 复制资源（可能失败）：先在外层判定，失败直接返回，不进锁、不起服务
        val webDir = runCatching { copyWebAssets() }
            .onFailure { CrashLogger.logThrowable(it, "copy-web-assets") }
            .getOrNull()
        if (webDir == null) {
            SessionState.setConnStatus("error")
            return
        }
        launchLock.withLock {
            // 重启安全：已有实例先停掉再起，修复「设置里改端口→重启服务」时旧 server 仍在、
            // 新端口根本没被 bind（旧红框残留、服务 DOWN）的 bug；Mutex 串行化防止并发重复起实例。
            server?.stop()
            server = null
            val port = SettingsRepository.get(this).serverPort
            val s = EmbeddedServer(TransferEngine.hub, TransferEngine.fileStore, webDir,
                onPortConflict = { p ->
                    // 可能从 IO 线程回调：SessionState 操作为线程安全，可直接调用；Toast 切主线程
                    SessionState.conflictPort = p
                    SessionState.setConnStatus("error")
                    // 在聊天记录里用红框提示（端口被占用），引导用户去设置中修改端口
                    SessionState.pushSystemNotice("⚠ " + getString(R.string.port_occupied, p))
                    Handler(Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            this@BridgeForegroundService,
                            getString(R.string.port_occupied, p), android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                })
            runCatching { s.start(ip, port) }
                .onSuccess {
                    server = s
                    currentIp = s.host // 与实际绑定地址对齐（回退 0.0.0.0 时尤其重要）
                    SessionState.selfUrl = selfUrlOf(s)
                    SessionState.setConnStatus("waiting")
                    SessionState.clearSystemNotices() // 端口冲突红框：服务正常起来后清除
                    acquireWake()
                    updateNotification()
                }
                .onFailure { e ->
                    CrashLogger.logThrowable(e, "server-start ip=$ip port=$port")
                    // 指定 IP 不是本机地址（热点没开 / 连的 WiFi 不是热点 / 关 Wi-Fi）时回退 0.0.0.0，
                    // 至少服务能起来，用户可手动看手机 IP 访问；否则直接闪退。
                    // 只有当「原地址不是 0.0.0.0」才回退：若本就绑 0.0.0.0 仍失败，必是端口被占，
                    // 此时 start() 内部已触发 onPortConflict（端口占用提示），无需再无意义重试。
                    if (e is java.net.BindException && ip != HotspotIp.BIND_ALL) {
                        runCatching {
                            CrashLogger.log("server-start", "回退绑定 0.0.0.0:$port（原地址 $ip 不可绑）")
                            s.start("0.0.0.0", port)
                        }                        .onSuccess {
                            server = s
                            currentIp = s.host
                            SessionState.selfUrl = selfUrlOf(s)
                            SessionState.setConnStatus("waiting")
                            SessionState.clearSystemNotices() // 端口冲突红框：服务正常起来后清除
                            acquireWake()
                            updateNotification()
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this@BridgeForegroundService,
                                    "服务已启动，但绑定地址受限（已自动回退到所有网卡）",
                                    Toast.LENGTH_LONG).show()
                            }
                        }.onFailure { fallbackErr ->
                            // 0.0.0.0 也失败 = 端口被占，start() 内部已触发 onPortConflict，这里仅记录
                            CrashLogger.logThrowable(fallbackErr, "server-start-fallback 0.0.0.0")
                        }
                    }
                }
        }
    }

    /** 绑定 0.0.0.0 时 selfUrl 会是 http://0.0.0.0:port（无法直接访问），补一句可读提示。 */
    private fun selfUrlOf(s: EmbeddedServer): String =
        if (s.host == HotspotIp.BIND_ALL) {
            "http://${HotspotIp.displayIp()}:${s.port}（未绑定具体网卡，请确认手机与电脑在同一局域网）"
        } else s.selfUrl()

    /** assets/pc-web → cacheDir/pc-web（Ktor staticFiles 需磁盘目录，整树递归拷贝）。
     *  每次启动强制重新拷贝：版本升级若未卸载，旧 cacheDir/pc-web 会残留，
     *  直接 return 会让旧 HTML/JS/CSS 继续被服务，导致修复（如断连条配色）不可见。 */
    private fun copyWebAssets(): File {
        val dir = File(cacheDir, "pc-web")
        dir.deleteRecursively()
        dir.mkdirs()
        fun rec(path: String, out: File) {
            val list = assets.list(path).orEmpty()
            if (list.isEmpty()) {
                assets.open(path).use { ins -> out.outputStream().use { ins.copyTo(it) } }
            } else {
                out.mkdirs()
                for (name in list) rec("$path/$name", File(out, name))
            }
        }
        rec("pc-web", dir)
        return dir
    }

    private fun copyLink() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", SessionState.selfUrl))
    }

    private fun buildNotification(waiting: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, ChatActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val copy = PendingIntent.getService(this, 1,
            Intent(this, BridgeForegroundService::class.java).setAction(ACTION_COPY_LINK),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, BridgeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_running,
                if (waiting) getString(R.string.waiting_connection) else SessionState.selfUrl))
            .setContentText(SessionState.selfUrl)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, getString(R.string.copy_link), copy)
            .addAction(0, getString(R.string.stop_service), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(SessionState.connStatus != "connected"))
    }

    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LanBridge:transfer").apply {
            setReferenceCounted(false); acquire(30 * 60 * 1000L) // 带超时防遗漏释放
        }
        // WifiLock（v1.1.9f）：保持 Wi-Fi 高性能模式。只有 CPU 唤醒锁时，App 切后台
        // （如系统文件选择器、相册、锁屏）Wi-Fi 仍会进省电休眠，PC 端表现为断连/无法下载
        if (wifiLock == null) runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@runCatching
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LanBridge:wifi").apply {
                setReferenceCounted(false); acquire() // 服务存活期间持有，onDestroy 释放
            }
        }.onFailure { CrashLogger.logThrowable(it, "wifi-lock") }
    }

    override fun onDestroy() {
        netCallback?.let { cb ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        netCallback = null
        server?.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        TransferEngine.fileStore.clearAll() // 磁盘 0 残留（T28）
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_STOP = "com.lanbridge.STOP"
        const val ACTION_COPY_LINK = "com.lanbridge.COPY_LINK"
        private const val CHANNEL = "lanbridge_transfer"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, BridgeForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}
