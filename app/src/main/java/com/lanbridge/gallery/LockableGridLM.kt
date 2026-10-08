package com.lanbridge.gallery

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 可锁滚动的 GridLayoutManager（T16A）：划选期禁滚，仅 requestDisallow 不够（§2.1.9-2） */
class LockableGridLM(context: android.content.Context, span: Int) :
    GridLayoutManager(context, span) {

    var scrollLocked = false
    override fun canScrollVertically(): Boolean = !scrollLocked && super.canScrollVertically()
}
