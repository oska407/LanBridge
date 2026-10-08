package com.lanbridge.model

/** 消息类型；SYSTEM_URL = 连接地址系统消息（聊天记录第一条，不置顶不悬浮，v1.6） */
enum class MsgType { TEXT, IMAGE, FILE, VIDEO, SYSTEM_URL }

/** 发送状态（F-19 AC1） */
enum class MsgStatus { SENDING, SENT, FAILED }

/** 内存态消息（零持久化，F-04 AC1） */
data class Message(
    val id: String,
    val type: MsgType,
    val from: String,          // "phone" | "pc" | "system"
    val text: String = "",
    val fileRef: FileMeta? = null,
    val ts: Long = System.currentTimeMillis(),
    var status: MsgStatus = MsgStatus.SENT,
    var connState: String = "" // SYSTEM_URL 用：等待连接 / 已连接·IP / 错误
)
