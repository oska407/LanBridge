package com.lanbridge.chat

import com.lanbridge.model.Message
import com.lanbridge.model.MsgType

/**
 * 多选模式控制器（T13B）：以 messageId 为键，禁用 position（防消息插入位移串位）。
 *
 * v1.1.8 逻辑整理（此前「多选」几乎不可用，根因有三，见下）：
 *   1. 进入/退出多选、勾选变化**都没有通知 Adapter 刷新** —— 圆圈不出现、点了不变色；
 *      现在所有状态变更统一走 onChanged，由 ChatActivity 统一发 payload 局部刷新。
 *   2. 「全选」只会全选、不会取消全选；现在改为 toggleAll() 全选/取消全选互切，
 *      并由 isAllSelected() 驱动按钮文案在「全选 / 取消全选」之间切换。
 *   3. 进入多选时先 enter() 再 toggle() 会连发两次通知；改为 enter(initial) 一次性带初值。
 *
 * 状态机（唯一真源）：
 *   普通模式 → 长按菜单「多选」  → enter(msg)：进入多选并默认勾选该条
 *   多选模式 → 点整行 / 点圆圈   → toggle(msg)：单条取消或勾选（可只留一条、也可全空）
 *   多选模式 → 全选/取消全选      → toggleAll()：全部勾选或全部取消
 *   多选模式 → 取消 / 返回键      → exit()：清空并回到普通模式
 * 勾选数归零**不自动退出**多选（用户可反选后再选，符合微信/系统相册行为）。
 */
class SelectionController {

    var selectionMode = false
        private set
    val selectedIds = LinkedHashSet<String>() // 保持勾选顺序

    var onChanged: (() -> Unit)? = null

    /** 进入多选；initial 非空且可选时顺带勾选（长按哪条就先选哪条） */
    fun enter(initial: Message? = null) {
        selectionMode = true
        selectedIds.clear()
        if (initial != null && canSelect(initial)) selectedIds.add(initial.id)
        onChanged?.invoke()
    }

    fun exit(): Boolean {
        if (!selectionMode) return false
        selectionMode = false
        selectedIds.clear()
        onChanged?.invoke()
        return true
    }

    /** 单条勾选/取消（可单选、可多选、可反选到 0 条） */
    fun toggle(msg: Message) {
        if (!canSelect(msg)) return
        if (!selectedIds.add(msg.id)) selectedIds.remove(msg.id)
        onChanged?.invoke()
    }

    /** 全选 ⇄ 取消全选；返回操作后的「是否全选」状态，供 UI 切文案 */
    fun toggleAll(messages: List<Message>): Boolean {
        val all = isAllSelected(messages)
        selectedIds.clear()
        if (!all) messages.filter(::canSelect).forEach { selectedIds.add(it.id) }
        onChanged?.invoke()
        return !all
    }

    /** 是否已把可选消息全勾上（无可选项时视作未全选，避免出现永远点不动的「取消全选」） */
    fun isAllSelected(messages: List<Message>): Boolean {
        val n = messages.count(::canSelect)
        return n > 0 && selectedIds.size >= n
    }

    fun isSelected(id: String) = selectedIds.contains(id)

    /** 地址（SYSTEM_URL）卡片与系统红框提示（SYSTEM_NOTICE）均不可勾选 */
    fun canSelect(msg: Message) = msg.type != MsgType.SYSTEM_URL && msg.type != MsgType.SYSTEM_NOTICE
}
