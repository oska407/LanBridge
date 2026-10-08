package com.lanbridge.chat

import android.graphics.Color
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import android.widget.TextView
import com.lanbridge.R
import com.lanbridge.model.Message
import com.lanbridge.model.MsgType

/**
 * 长按菜单（T13/F-16 AC2~AC3）：ListPopupWindow 锚定气泡，按消息类型动态构建；
 * 「分享到微信」置顶浅绿高亮（本软件核心价值）、「删除」末位标红、高频在上。
 */
object MessageMenu {

    interface Callbacks {
        fun onCopy(msg: Message)
        fun onShareWeChat(msg: Message)
        fun onSave(msg: Message)
        fun onOpenWith(msg: Message)
        fun onMultiSelect(msg: Message)
        fun onDelete(msg: Message)
    }

    private data class Item(val label: String, val highlight: Boolean = false, val danger: Boolean = false)

    fun show(anchor: View, msg: Message, callbacks: Callbacks) {
        val ctx = anchor.context
        val items = when (msg.type) {
            MsgType.TEXT -> listOf(
                Item(ctx.getString(R.string.menu_copy)),
                Item(ctx.getString(R.string.menu_share_wechat), highlight = true),
                Item(ctx.getString(R.string.menu_multi_select)),
                Item(ctx.getString(R.string.menu_delete), danger = true),
            )
            else -> listOf( // IMAGE / FILE / VIDEO
                Item(ctx.getString(R.string.menu_share_wechat), highlight = true),
                Item(ctx.getString(R.string.menu_save)),
                Item(ctx.getString(R.string.menu_open_with)),
                Item(ctx.getString(R.string.menu_multi_select)),
                Item(ctx.getString(R.string.menu_delete), danger = true),
            )
        }

        val popup = ListPopupWindow(ctx)
        popup.setAdapter(object : ArrayAdapter<Item>(ctx, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as View
                val tv = v.findViewById<TextView>(android.R.id.text1)
                tv.textSize = 15f
                when {
                    items[position].danger -> { tv.setTextColor(Color.parseColor("#C9302C")); v.setBackgroundColor(Color.WHITE) }
                    items[position].highlight -> { tv.setTextColor(Color.parseColor("#0A8A43")); v.setBackgroundColor(Color.parseColor("#E8F8EE")) }
                    else -> { tv.setTextColor(Color.parseColor("#1A1A1A")); v.setBackgroundColor(Color.WHITE) }
                }
                return v
            }
        })
        popup.anchorView = anchor
        popup.modal = true
        popup.width = ctx.resources.displayMetrics.widthPixels / 2
        popup.setOnItemClickListener { _: AdapterView<*>, _, pos, _ ->
            popup.dismiss()
            val msg0 = msg
            when (items[pos].label) {
                ctx.getString(R.string.menu_copy) -> callbacks.onCopy(msg0)
                ctx.getString(R.string.menu_share_wechat) -> callbacks.onShareWeChat(msg0)
                ctx.getString(R.string.menu_save) -> callbacks.onSave(msg0)
                ctx.getString(R.string.menu_open_with) -> callbacks.onOpenWith(msg0)
                ctx.getString(R.string.menu_multi_select) -> callbacks.onMultiSelect(msg0)
                ctx.getString(R.string.menu_delete) -> callbacks.onDelete(msg0)
            }
        }
        popup.show()
    }
}
