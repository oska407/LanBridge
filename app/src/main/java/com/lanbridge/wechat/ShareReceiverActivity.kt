package com.lanbridge.wechat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lanbridge.R
import com.lanbridge.model.FileMeta
import com.lanbridge.server.SessionState
import com.lanbridge.server.TransferEngine
import com.lanbridge.service.BridgeForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 微信分享入站（T18）：ACTION_SEND / SEND_MULTIPLE，mimeType */*。
 * onCreate/onNewIntent 内同步拷贝（防临时权限失效 Q3）；拷贝完成后经 TransferEngine 推给 PC（T19）。
 * 独立任务栈 + noHistory：返回键回到聊天页（T10D）。
 */
class ShareReceiverActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) { finish(); return }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                if (uri == null) { toastFail(); finish(); return }
                ensureServer()
                scope.launch {
                    val name = InboundCopier.queryDisplayName(this@ShareReceiverActivity, uri)
                    val copied = InboundCopier.copyUriToLocal(this@ShareReceiverActivity, uri, name)
                        ?: run { toastFail(); publishFallbackText(name); return@launch }
                    publish(copied, name)
                }
                finish() // 关闭透明页，后台协程继续拷贝与广播（scope 不绑定 Activity 生命周期）
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM).orEmpty()
                if (uris.isEmpty()) { toastFail(); finish(); return }
                ensureServer()
                scope.launch {
                    for (uri in uris) {
                        val name = InboundCopier.queryDisplayName(this@ShareReceiverActivity, uri)
                        val copied = InboundCopier.copyUriToLocal(this@ShareReceiverActivity, uri, name)
                            ?: continue
                        publish(copied, name)
                    }
                }
                finish()
            }
            else -> finish()
        }
    }

    private fun publish(file: java.io.File, name: String) {
        val mime = guessMime(name)
        val meta = FileMeta(
            id = java.util.UUID.randomUUID().toString(), name = name, mime = mime,
            size = file.length(), chunkSize = 1_048_576,
            totalChunks = ((file.length() + 1_048_575) / 1_048_576).toInt(),
            kind = if (mime.startsWith("image/")) "image" else "file",
            localPath = file.absolutePath
        )
        // 走 FileStore 落位（与 TransferEngine.doPublish 相同路径），由服务广播
        scope.launch {
            val fs = TransferEngine.fileStore
            val dest = java.io.File(fs.dir, "${meta.id}.part")
            java.io.RandomAccessFile(dest, "rw").use { out ->
                file.inputStream().use { ins ->
                    val buf = ByteArray(1 shl 20)
                    while (true) { val n = ins.read(buf); if (n == -1) break; out.write(buf, 0, n) }
                }
            }
            SessionState.transfers[meta.id] = meta
            SessionState.addMessage(com.lanbridge.model.Message(
                java.util.UUID.randomUUID().toString(),
                if (meta.kind == "image") com.lanbridge.model.MsgType.IMAGE else com.lanbridge.model.MsgType.FILE,
                "phone", fileRef = meta))
            TransferEngine.hub.broadcast(com.lanbridge.model.WsMsg.FileMetaMsg(meta, "phone"))
        }
    }

    private fun publishFallbackText(name: String) {
        // 拷贝失败：明确提示不崩溃（F-07 AC2），并尝试以文本通知 PC
        TransferEngine.hub.broadcast(com.lanbridge.model.WsMsg.Text("[文件转发失败] $name", "phone"))
    }

    private fun toastFail() = Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show()

    /** 确保传输服务在运行（幂等）：TransferEngine 已在 Application 初始化，此处仅尝试拉起前台服务，
     *  使分享能真正推送到 PC。若服务已在运行，BridgeForegroundService.startServer 内部会判定为已启动而跳过。 */
    private fun ensureServer() {
        runCatching { BridgeForegroundService.start(this@ShareReceiverActivity) }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "mp4" -> "video/mp4"; "mov" -> "video/quicktime"
            "pdf" -> "application/pdf"; "txt" -> "text/plain"
            "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }
    }

    // TIRAMISU 前后兼容取 extra
    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<T>? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java)
        else @Suppress("DEPRECATION") getParcelableArrayListExtra(key)
}
