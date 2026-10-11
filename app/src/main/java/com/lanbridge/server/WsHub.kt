package com.lanbridge.server

import com.lanbridge.model.MsgType
import com.lanbridge.model.WsMsg
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * WS 控制中枢（T06）：会话注册 / 广播 / 路由；无配对鉴权（F-11 已取消），
 * 仅保留最新 session（多标签页抢占：新 Tab 抢占、旧 Tab 收 status{conn:"replaced"}，T02C）。
 */
class WsHub {

    private val latest = AtomicReference<DefaultWebSocketServerSession?>(null)

    /** 广播给 PC（单会话模型，保留最新连接） */
    fun broadcast(msg: WsMsg) {
        val s = latest.get() ?: return
        val raw = msg.toJson().toString()
        s.launch { runCatching { s.send(Frame.Text(raw)) } }
    }

    fun connected(): Boolean = latest.get() != null

    /** 注册会话；被抢占的旧 session 发 replaced 并关闭 */
    suspend fun register(session: DefaultWebSocketServerSession, deviceName: String, ip: String) {
        val old = latest.getAndSet(session)
        old?.let {
            runCatching {
                it.send(Frame.Text(WsMsg.Status("replaced").toJson().toString()))
                it.close()
            }
        }
        // 建连即下发 presence + connected 状态（§5.1）
        runCatching {
            session.send(Frame.Text(WsMsg.Presence(deviceName, ip).toJson().toString()))
            session.send(Frame.Text(WsMsg.Status("connected").toJson().toString()))
        }
        // 历史同步（v1.1.9f）：手机端会话为唯一真相源，PC 刷新/重连后自动补齐全部记录，
        // 修复「发送和接收不一致（PC 端少文件/少消息）」——漏掉的事件不再永久丢失
        runCatching {
            val items = SessionState.userMessages().mapNotNull { m ->
                JSONObject().apply {
                    put("id", m.id)
                    put("from", m.from) // "phone" | "pc"，与 file_meta 的 from 同语义
                    when (m.type) {
                        MsgType.TEXT -> { put("kind", "text"); put("text", m.text) }
                        else -> m.fileRef?.let { f ->
                            put("kind", f.kind.ifBlank { "file" }) // image | video | file
                            put("name", f.name); put("mime", f.mime); put("size", f.size)
                            put("url", "/api/files/${f.id}")
                        } ?: return@mapNotNull null
                    }
                }
            }
            session.send(Frame.Text(WsMsg.History(items).toJson().toString()))
        }
        SessionState.setConnStatus("connected", ip)
    }

    fun unregister(session: DefaultWebSocketServerSession) {
        if (latest.compareAndSet(session, null)) {
            SessionState.setConnStatus("waiting")
        }
    }
}
