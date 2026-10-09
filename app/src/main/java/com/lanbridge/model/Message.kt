package com.lanbridge.model

/** 消息类型；SYSTEM_URL = 连接地址系统消息（聊天记录第一条，不置顶不悬浮，v1.6） */
enum class MsgType { TEXT, IMAGE, FILE, VIDEO, SYSTEM_URL }

/** 发送状态（F-19 AC1）；RECEIVING = 正在接收 PC 传来的文件（v1.1.5+ 接收进度） */
enum class MsgStatus { SENDING, SENT, FAILED, RECEIVING }

/** 内存态消息（零持久化，F-04 AC1） */
data class Message(
    val id: String,
    val type: MsgType,
    val from: String,          // "phone" | "pc" | "system"
    val text: String = "",
    val fileRef: FileMeta? = null,
    val ts: Long = System.currentTimeMillis(),
    var status: MsgStatus = MsgStatus.SENT,
    var connState: String = "", // SYSTEM_URL 用：等待连接 / 已连接·IP / 错误
    /** 传输进度百分比 0-100，负数=不显示进度条（接收/发送中由服务端回填） */
    var progress: Int = -1
)
