package com.lanbridge.chat

import com.lanbridge.model.Message

/** 多选模式控制器（T13B）：以 messageId 为键，禁用 position（防消息插入位移串位） */
class SelectionController {

    var selectionMode = false
        private set
    val selectedIds = LinkedHashSet<String>() // 保持勾选顺序

    var onChanged: (() -> Unit)? = null

    fun enter() {
        selectionMode = true
        selectedIds.clear()
        onChanged?.invoke()
    }

    fun exit(): Boolean {
        if (!selectionMode) return false
        selectionMode = false
        selectedIds.clear()
        onChanged?.invoke()
        return true
    }

    fun toggle(msg: Message) {
        if (msg.type == com.lanbridge.model.MsgType.SYSTEM_URL) return // 地址消息不可勾选（F-17 AC4）
        if (!selectedIds.add(msg.id)) selectedIds.remove(msg.id)
        onChanged?.invoke()
    }

    fun selectAll(messages: List<Message>) {
        selectedIds.clear()
        messages.filter { it.type != com.lanbridge.model.MsgType.SYSTEM_URL }
            .forEach { selectedIds.add(it.id) }
        onChanged?.invoke()
    }

    fun isSelected(id: String) = selectedIds.contains(id)
}
