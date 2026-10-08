package com.lanbridge.gallery

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * 滑动连续多选（T16A，F-06 AC6）：OnItemTouchListener 手势状态机。
 * DOWN 记 anchor 并定目标态（起项未选→整段选中；已选→整段取消，可反向划回）；
 * MOVE 判 |dx|>|dy| 且超 touchSlop 进 DRAG_SELECT（否则交还垂直滚动）；
 * MOVE 中 findChildViewUnder→position，对 [min(last,cur)..max(last,cur)] 区间填充防漏格（AC6.2）；
 * UP/CANCEL 退出恢复滚动。刷新走 payload 局部刷新，绝不重载缩略图（AC6.6）。
 */
class DragSelectHelper(
    private val rv: RecyclerView,
    private val store: SelectionStore,
) : RecyclerView.OnItemTouchListener {

    private val touchSlop = android.view.ViewConfiguration.get(rv.context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var anchorPos = RecyclerView.NO_POSITION
    private var targetState = false
    private var lastPos = RecyclerView.NO_POSITION
    private val pending = mutableSetOf<Int>()

    val isActive get() = dragging

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                anchorPos = posAt(e.x, e.y)
                lastPos = anchorPos
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && anchorPos != RecyclerView.NO_POSITION) {
                    if (abs(e.x - downX) > touchSlop && abs(e.x - downX) > abs(e.y - downY)) {
                        // 横向意图 → 进入划选；纵向手势=滚列表（与微信一致）
                        val vh = rv.findViewHolderForAdapterPosition(anchorPos)
                        val id = (vh as? GalleryAdapter.MediaVH)?.mediaId
                        if (id != null) {
                            targetState = !store.isSelected(id)
                            store.set(id, targetState)
                            pending.add(anchorPos)
                            dragging = true
                            (rv.layoutManager as? LockableGridLM)?.scrollLocked = true
                            rv.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
                if (dragging) dragTo(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    (rv.layoutManager as? LockableGridLM)?.scrollLocked = false
                    flush()
                }
                anchorPos = RecyclerView.NO_POSITION
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_MOVE) dragTo(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            dragging = false
            (rv.layoutManager as? LockableGridLM)?.scrollLocked = false
            flush()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun posAt(x: Float, y: Float): Int {
        val child = rv.findChildViewUnder(x, y) ?: return RecyclerView.NO_POSITION
        return rv.getChildAdapterPosition(child)
    }

    private fun dragTo(e: MotionEvent) {
        val pos = posAt(e.x, e.y)
        if (pos == RecyclerView.NO_POSITION || pos == lastPos) return
        // 区间填充（AC6.2）：last→current 之间全部应用同一目标态
        val from = minOf(lastPos, pos); val to = maxOf(lastPos, pos)
        for (p in from..to) pending.add(p)
        lastPos = pos
        flushThrottled()
    }

    /** 一帧内合并提交（AC6.6 局部刷新不砸帧率） */
    private fun flushThrottled() {
        if (pending.size > 8) flush() // 快速滑动时按批提交
    }

    private fun flush() {
        if (pending.isEmpty()) { return }
        val adapter = rv.adapter as? GalleryAdapter ?: run { pending.clear(); return }
        for (p in pending) {
            val vh = rv.findViewHolderForAdapterPosition(p) as? GalleryAdapter.MediaVH ?: continue
            val id = vh.mediaId ?: continue
            if (store.set(id, targetState)) adapter.notifyItemChanged(p, GalleryAdapter.PAYLOAD_SELECTION)
        }
        pending.clear()
        adapter.onSelectionChanged?.invoke(store.size())
    }
}
