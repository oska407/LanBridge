package com.lanbridge.server

import com.lanbridge.model.FileMeta
import com.lanbridge.model.Message
import com.lanbridge.model.MsgType
import com.lanbridge.model.WsMsg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * 传输引擎：文件发布 / 文本发送（串行队列并发=1，F-20 AC6 由协程单消费者保证顺序）。
 * 手机端是服务端：文件"发送"= 落入 FileStore + WS 广播 file_meta，PC 主动 GET 拉取（§5.3 反向）。
 */
object TransferEngine {

    lateinit var hub: WsHub
        private set
    lateinit var fileStore: FileStore
        private set

    private val queue = kotlinx.coroutines.channels.Channel<Job>(capacity = kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var started = false
    @Volatile private var initialized = false

    private data class Job(val run: suspend () -> Unit)

    /** 幂等初始化：Application 与前台服务都会调用，仅首次生效，避免重复创建 hub/worker。 */
    fun init(fs: FileStore) {
        if (initialized) return
        initialized = true
        fileStore = fs
        hub = WsHub()
        ensureWorker()
    }

    private fun ensureWorker() {
        if (started) return
        started = true
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            for (job in queue) {
                runCatching { job.run() } // 串行：同一时刻只传一个文件（F-20 AC6）
            }
        }
    }

    /** 发送文本：入内存会话 + WS 广播 */
    fun sendText(text: String): Message {
        val m = Message(UUID.randomUUID().toString(), MsgType.TEXT, "phone", text = text,
            status = com.lanbridge.model.MsgStatus.SENT)
        SessionState.addMessage(m)
        hub.broadcast(WsMsg.Text(text, "phone", m.id))
        return m
    }

    /**
     * 发布本地文件给 PC：流式拷入 FileStore（不整文件入内存）→ 广播 file_meta。
     * kind=image/video/file；视频不压缩不转码直传（F-20 AC1）。
     */
    fun publishFile(src: File, displayName: String, mime: String, kind: String, isOriginal: Boolean) {
        queue.trySend(Job { doPublish(src, displayName, mime, kind, isOriginal) })
    }

    private suspend fun doPublish(src: File, displayName: String, mime: String, kind: String, isOriginal: Boolean) = withContext(Dispatchers.IO) {
        if (!src.exists()) return@withContext
        val id = UUID.randomUUID().toString()
        val dest = File(fileStoreDir(), "$id.part")
        RandomAccessFile(dest, "rw").use { out ->
            src.inputStream().use { ins ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                }
            }
        }
        val meta = FileMeta(
            id = id, name = displayName, mime = mime, size = src.length(),
            chunkSize = 1_048_576, totalChunks = ((src.length() + 1_048_575) / 1_048_576).toInt(),
            isOriginal = isOriginal, kind = kind, localPath = src.absolutePath
        )
        SessionState.transfers[id] = meta
        val msg = Message(UUID.randomUUID().toString(),
            if (kind == "image") MsgType.IMAGE else MsgType.FILE, "phone", fileRef = meta)
        SessionState.addMessage(msg)
        hub.broadcast(WsMsg.FileMetaMsg(meta, "phone"))
    }

    /** 接收 PC 文件元数据（PC 上传完成后 file_ready → 组装落盘 cacheDir，自动落盘无下载按钮 F-20 AC4） */
    fun onIncomingFile(meta: FileMeta) {
        queue.trySend(Job {
            val f = runCatching { fileStore.assemble(meta.id) }.getOrNull() ?: return@Job
            meta.localPath = f.absolutePath
            SessionState.transfers[meta.id] = meta
            val msg = Message(UUID.randomUUID().toString(),
                if (meta.kind == "image") MsgType.IMAGE else MsgType.FILE, "pc", fileRef = meta)
            SessionState.addMessage(msg)
        })
    }

    fun fileStoreDir() = fileStore.dir
}
