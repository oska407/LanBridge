package com.lanbridge.server

import com.lanbridge.model.WsMsg
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.BindException
import java.net.URLEncoder

/**
 * Ktor 内嵌服务（T04/T05/T06/T07）：HTTP + WS 同端口。
 * 仅绑定热点网卡 IP（不绑 0.0.0.0，F-11 唯一缓解）；端口占用不自动换（F-20 AC9）。
 */
class EmbeddedServer(
    private val hub: WsHub,
    private val fileStore: FileStore,
    private val webDir: File,
    var onPortConflict: ((Int) -> Unit)? = null,
) {
    private var engine: ApplicationEngine? = null
    @Volatile var port: Int = 8080
    @Volatile var host: String = ""

    suspend fun start(ip: String, port: Int) {
        stop()
        this.host = ip; this.port = port
        try {
            engine = embeddedServer(CIO, port = port, host = ip) {
                install(WebSockets)
                routing { routes() }
            }.also { it.start(wait = false) }
        } catch (e: BindException) {
            // 不自动换端口，提示去设置改（F-20 AC9）
            onPortConflict?.invoke(port)
            throw e
        }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        engine = null
    }

    fun selfUrl() = "http://$host:$port"

    private fun io.ktor.server.routing.Routing.routes() {
        staticFiles("/", webDir) { default("index.html") }

        get("/api/info") {
            val json = JSONObject()
                .put("ip", host).put("deviceName", SessionState.deviceName)
                .put("selfUrl", selfUrl()).put("version", "1.0.0")
            call.respondText(json.toString(), ContentType.Application.Json)
        }

        webSocket("/ws") {
            hub.register(this, SessionState.deviceName, host)
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    when (val msg = WsMsg.parse(frame.readText())) {
                        is WsMsg.Ping -> send(Frame.Text(WsMsg.Pong(msg.id).toJson().toString()))
                        is WsMsg.Text -> {
                            // PC→手机 文本消息入内存会话
                            val m = com.lanbridge.model.Message(
                                msg.msgId.ifEmpty { java.util.UUID.randomUUID().toString() },
                                com.lanbridge.model.MsgType.TEXT, "pc", text = msg.text
                            )
                            SessionState.addMessage(m)
                        }
                        is WsMsg.FileMetaMsg -> {
                            // PC 上传完成通告 → 自动落盘（F-20 AC4，无下载按钮）
                            if (msg.from == "pc") TransferEngine.onIncomingFile(msg.meta)
                        }
                        is WsMsg.FileReady -> {
                            // 转发成功即清内部拷贝（F-20 AC12）
                            SessionState.transfers[msg.fileId]?.localPath?.let { p ->
                                File(p).takeIf { it.exists() }?.delete()
                            }
                        }
                        else -> {}
                    }
                }
            } finally {
                hub.unregister(this)
            }
        }

        // PC→手机 分块上传（raw 字节 + 头部定位，§4.2）
        post("/api/files") {
            val fileId = call.request.header("X-File-Id")
                ?: return@post call.respondText(
                    """{"ok":false,"error":"missing X-File-Id"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest)
            val index = call.request.header("X-Chunk-Index")?.toIntOrNull() ?: 0
            val chunkSize = call.request.header("X-Chunk-Size")?.toIntOrNull() ?: 1_048_576
            val offsetInChunk = call.request.header("X-Chunk-Offset")?.toIntOrNull() ?: 0

            val channel = call.receiveChannel()
            val buf = ByteArray(BUF)
            var written = 0
            while (!channel.isClosedForRead) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n == -1) break
                if (n > 0) {
                    fileStore.writeChunk(fileId, index, chunkSize, buf.copyOf(n), offsetInChunk + written)
                    written += n
                }
            }
            call.respondText("""{"ok":true,"fileId":"$fileId"}""", ContentType.Application.Json)
        }

        // 手机→PC 下载（支持 Range 断点续传；中文文件名 RFC5987，防乱码 R7）
        get("/api/files/{id}") {
            val id = call.parameters["id"] ?: return@get call.respondText(
                "bad id", status = HttpStatusCode.BadRequest)
            val meta = SessionState.transfers[id]
            val f = runCatching { fileStore.assemble(id) }.getOrNull()
                ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
            val total = f.length()
            val encodedName = URLEncoder.encode(meta?.name ?: id, "UTF-8").replace("+", "%20")
            call.response.header("Accept-Ranges", "bytes")
            call.response.header("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
            val contentType = runCatching { ContentType.parse(meta?.mime ?: "application/octet-stream") }
                .getOrDefault(ContentType.Application.OctetStream)
            val range = call.request.header("Range")
            if (range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").substringBefore("-").toLongOrNull() ?: 0L
                call.response.header("Content-Range", "bytes $start-${total - 1}/$total")
                call.respondBytesWriter(contentType, HttpStatusCode.PartialContent, contentLength = total - start) {
                    fileStore.openStream(id, start).use { raf ->
                        val buf = ByteArray(BUF)
                        var remain = total - start
                        while (remain > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                            if (n == -1) break
                            writeFully(buf, 0, n)
                            remain -= n
                        }
                    }
                }
            } else {
                call.respondBytesWriter(contentType, contentLength = total) {
                    fileStore.openStream(id).use { raf ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            val n = raf.read(buf)
                            if (n == -1) break
                            writeFully(buf, 0, n)
                        }
                    }
                }
            }
        }
    }

    companion object { private const val BUF = 1 shl 20 }
}
