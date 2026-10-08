package com.lanbridge.model

import org.json.JSONObject
import java.util.UUID

/**
 * WS 控制消息（共享:协议 T03，契约与 ARCHITECTURE.md §4.1 对齐）。
 * 信封必含 type + id + ts；文件字节永远走 HTTP，不进 WS。
 * 注：pair_request/pair_submit/pair_result 已随 F-11 取配对而移除（v1.6）。
 */
sealed class WsMsg {
    abstract val type: String
    abstract val id: String
    val ts: Long = System.currentTimeMillis()

    open fun toJson(): JSONObject = JSONObject().put("type", type).put("id", id).put("ts", ts)

    data class Text(val text: String, val from: String, val msgId: String = UUID.randomUUID().toString()) : WsMsg() {
        override val type = "text"; override val id = msgId
        override fun toJson() = super.toJson().put("text", text).put("from", from)
    }

    data class FileMetaMsg(val meta: FileMeta, val from: String) : WsMsg() {
        override val type = "file_meta"; override val id = meta.id
        override fun toJson() = super.toJson().putAll(meta.toJson()).put("from", from)
    }

    data class FileReady(val fileId: String, val ok: Boolean) : WsMsg() {
        override val type = "file_ready"; override val id = fileId
        override fun toJson() = super.toJson().put("ok", ok)
    }

    data class Ack(val fileId: String, val receivedChunks: List<Int>, val done: Boolean) : WsMsg() {
        override val type = "ack"; override val id = fileId
        override fun toJson() = super.toJson()
            .put("receivedChunks", org.json.JSONArray(receivedChunks)).put("done", done)
    }

    data class Presence(val deviceName: String, val ip: String, val role: String = "phone") : WsMsg() {
        override val type = "presence"; override val id = UUID.randomUUID().toString()
        override fun toJson() = super.toJson().put("deviceName", deviceName).put("ip", ip).put("role", role)
    }

    data class Status(val conn: String) : WsMsg() {
        override val type = "status"; override val id = UUID.randomUUID().toString()
        override fun toJson() = super.toJson().put("conn", conn)
    }

    data class Ping(override val id: String = UUID.randomUUID().toString()) : WsMsg() {
        override val type = "ping"
    }

    data class Pong(override val id: String = UUID.randomUUID().toString()) : WsMsg() {
        override val type = "pong"
    }

    companion object {
        fun parse(raw: String): WsMsg? = runCatching {
            val o = JSONObject(raw)
            when (o.optString("type")) {
                "text" -> Text(o.getString("text"), o.optString("from", "pc"), o.optString("id"))
                "file_meta" -> FileMetaMsg(FileMeta.fromJson(o), o.optString("from", "pc"))
                "file_ready" -> FileReady(o.getString("id"), o.optBoolean("ok", true))
                "ack" -> Ack(o.getString("id"), emptyList(), o.optBoolean("done", false))
                "presence" -> Presence(o.optString("deviceName"), o.optString("ip"), o.optString("role", "pc"))
                "status" -> Status(o.optString("conn"))
                "ping" -> Ping(o.optString("id"))
                "pong" -> Pong(o.optString("id"))
                else -> null
            }
        }.getOrNull()
    }
}
