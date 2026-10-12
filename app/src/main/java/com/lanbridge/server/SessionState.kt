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
    var conflictPort: Int = 0            // 最近一次端口冲突的端口（红框提示用真实端口，而非写死 8080）
    var notifyEnabled: Boolean = true    // 一个开关管两端（F-19 AC2）

    val messages = Collections.synchronizedList(mutableListOf<Message>())
    val transfers = Collections.synchronizedMap(mutableMapOf<String, FileMeta>())

    // 监听器列表：会被 Ktor IO 线程遍历、主线程增删，必须并发安全 + 单个失败不影响其它
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<Message>) -> Unit>()
    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** PC 来消息回调（与列表刷新解耦，用于 Toast 等即时反馈） */
    private val incomingListeners = java.util.concurrent.CopyOnWriteArrayList<(Message) -> Unit>()

    fun init(ctx: Context) {
        deviceName = android.os.Build.MODEL ?: "LanBridge"
        // 首条：连接地址系统消息（F-17 AC2）
        if (messages.isEmpty()) {
            messages.add(Message(UUID.randomUUID().toString(), MsgType.SYSTEM_URL, "system",
                connState = "waiting"))
        }
    }

    /** 过滤收口（v1.6 必须约束）：多选/全选/保存统计/计数一律走它；系统红框提示不算用户消息 */
    fun userMessages(): List<Message> = synchronized(messages) {
        messages.filter { it.type != MsgType.SYSTEM_URL && it.type != MsgType.SYSTEM_NOTICE }
    }

    fun addMessage(m: Message) {
        messages.add(m)
        notifyChanged()
    }

    /**
     * 在聊天记录末尾插入一条系统红框提示（v1.1.9i+）：端口被占用等异常引导。
     * 自带去重：先移除已有的 SYSTEM_NOTICE，保证同一类提示只有一条、始终是最新的。
     * 不计入 userMessages（不可勾选、不进转发/保存统计）。
     */
    fun pushSystemNotice(text: String) {
        synchronized(messages) {
            messages.removeAll { it.type == MsgType.SYSTEM_NOTICE }
            messages.add(Message(UUID.randomUUID().toString(), MsgType.SYSTEM_NOTICE, "system", text = text))
        }
        notifyChanged()
    }

    fun updateMessage(id: String, transform: (Message) -> Unit) {
        synchronized(messages) {
            messages.firstOrNull { it.id == id }?.let(transform)
        }
        notifyChanged()
    }

    /** 移除单条消息（接收占位气泡被真实消息替换时用） */
    fun removeMessage(id: String) {
        synchronized(messages) { messages.removeAll { it.id == id } }
        notifyChanged()
    }

    /** 清空用户消息但保留首条地址消息（F-19 AC3）；系统红框提示一并清掉；不清临时文件 */
    fun clearMessages() {
        synchronized(messages) {
            val keep = messages.filter { it.type == MsgType.SYSTEM_URL }
            messages.clear()
            messages.addAll(keep)
        }
        notifyChanged()
    }

    /** 移除所有系统红框提示（端口冲突解除/用户改端口后调用，避免红框残留） */
    fun clearSystemNotices() {
        synchronized(messages) {
            messages.removeAll { it.type == MsgType.SYSTEM_NOTICE }
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

    fun onIncoming(l: (Message) -> Unit) { incomingListeners.add(l) }
    fun removeIncomingListener(l: (Message) -> Unit) { incomingListeners.remove(l) }

    /** PC 端来消息：列表刷新之外的即时反馈（Toast/通知），不计入 messages 变更 */
    fun notifyIncoming(m: Message) {
        incomingListeners.forEach { runCatching { it(m) } }
    }

    fun notifyChanged() {
        val snapshot = synchronized(messages) { messages.toList() }
        listeners.forEach { runCatching { it(snapshot) } } // 单个监听异常不阻断其它监听
    }
}
