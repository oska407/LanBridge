package com.lanbridge.server

import android.content.Context
import com.lanbridge.model.FileMeta
import com.lanbridge.model.Message
import com.lanbridge.model.MsgStatus
import com.lanbridge.model.MsgType
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * 内存会话态（T04）。零持久化：进程退出/服务销毁即清，磁盘 0 残留（F-04 AC1）。
 * 连接地址为 messages 第一条 SYSTEM_URL 系统消息（v1.6，不置顶不悬浮）。
 */
object SessionState {
    const val TYPE_SYSTEM = "SYSTEM"
    const val PAYLOAD_URL = "payload_url"
    const val PAYLOAD_SELECTION = "payload_selection"

    var deviceName: String = "LanBridge"
    var selfUrl: String = ""
    var peerIp: String = ""
    var connStatus: String = "waiting"   // waiting | connected | disconnected
    var notifyEnabled: Boolean = true    // 一个开关管两端（F-19 AC2）

    val messages = Collections.synchronizedList(mutableListOf<Message>())
    val transfers = Collections.synchronizedMap(mutableMapOf<String, FileMeta>())

    private val listeners = mutableListOf<(List<Message>) -> Unit>()
    private val statusListeners = mutableListOf<() -> Unit>()

    fun init(ctx: Context) {
        deviceName = android.os.Build.MODEL ?: "LanBridge"
        // 首条：连接地址系统消息（F-17 AC2）
        if (messages.isEmpty()) {
            messages.add(Message(UUID.randomUUID().toString(), MsgType.SYSTEM_URL, "system",
                connState = "waiting"))
        }
    }

    /** 过滤收口（v1.6 必须约束）：多选/全选/保存统计/计数一律走它 */
    fun userMessages(): List<Message> = synchronized(messages) {
        messages.filter { it.type != MsgType.SYSTEM_URL }
    }

    fun addMessage(m: Message) {
        messages.add(m)
        notifyChanged()
    }

    fun updateMessage(id: String, transform: (Message) -> Unit) {
        synchronized(messages) {
            messages.firstOrNull { it.id == id }?.let(transform)
        }
        notifyChanged()
    }

    /** 清空用户消息但保留首条地址消息（F-19 AC3）；不清临时文件 */
    fun clearMessages() {
        synchronized(messages) {
            val keep = messages.filter { it.type == MsgType.SYSTEM_URL }
            messages.clear()
            messages.addAll(keep)
        }
        notifyChanged()
    }

    fun setConnStatus(status: String, ip: String? = null) {
        connStatus = status
        ip?.let { peerIp = it }
        synchronized(messages) {
            messages.firstOrNull { it.type == MsgType.SYSTEM_URL }?.let {
                it.connState = when (status) {
                    "connected" -> "connected"
                    "waiting" -> "waiting"
                    else -> "error"
                }
            }
        }
        notifyChanged()
        statusListeners.forEach { it() }
    }

    fun onMessagesChanged(l: (List<Message>) -> Unit) { listeners.add(l) }
    fun removeListener(l: (List<Message>) -> Unit) { listeners.remove(l) }

    fun notifyChanged() {
        val snapshot = synchronized(messages) { messages.toList() }
        listeners.forEach { it(snapshot) }
    }
}
