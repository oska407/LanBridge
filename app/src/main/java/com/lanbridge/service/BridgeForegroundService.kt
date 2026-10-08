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
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.lanbridge.R
import com.lanbridge.chat.ChatActivity
import com.lanbridge.server.EmbeddedServer
import com.lanbridge.server.FileStore
import com.lanbridge.server.SessionState
import com.lanbridge.server.TransferEngine
import com.lanbridge.settings.SettingsRepository
import com.lanbridge.util.HotspotIp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 前台服务（T27）：Ktor 生命周期承载 + 常驻通知（复制链接/停止服务 action）+ WakeLock。
 * Android 14 强制 foregroundServiceType="dataSync"（Manifest 已声明）。
 */
class BridgeForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: EmbeddedServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        TransferEngine.init(FileStore(FileStore.dirOf(this)))
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
        startForeground(NOTIF_ID, buildNotification(waiting = true))
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return // 幂等：重复 startForegroundService/restart 不再起第二个实例
        val ip = HotspotIp.get(this)
        val port = SettingsRepository.get(this).serverPort
        scope.launch {
            val webDir = copyWebAssets()
            val s = EmbeddedServer(TransferEngine.hub, TransferEngine.fileStore, webDir,
                onPortConflict = { p ->
                    SessionState.setConnStatus("error")
                    android.widget.Toast.makeText(
                        this@BridgeForegroundService,
                        getString(R.string.port_occupied, p), android.widget.Toast.LENGTH_LONG
                    ).show()
                })
            runCatching { s.start(ip, port) }
                .onSuccess {
                    server = s
                    SessionState.selfUrl = s.selfUrl()
                    SessionState.setConnStatus("waiting")
                    acquireWake()
                    updateNotification()
                }
        }
    }

    /** assets/pc-web → cacheDir/pc-web（Ktor staticFiles 需磁盘目录，整树递归拷贝） */
    private fun copyWebAssets(): File {
        val dir = File(cacheDir, "pc-web")
        if (dir.exists()) return dir
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
    }

    override fun onDestroy() {
        server?.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
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
