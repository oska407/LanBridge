package com.lanbridge.gallery

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 可锁滚动的 GridLayoutManager（T16A）：划选期禁滚；forceScroll 供边缘自动滚屏临时放行 */
class LockableGridLM(context: android.content.Context, span: Int) :
    GridLayoutManager(context, span) {

    var scrollLocked = false
    var forceScroll = false
    override fun canScrollVertically(): Boolean =
        (forceScroll || !scrollLocked) && super.canScrollVertically()
}
