package com.lanbridge.gallery

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * 滑动连续多选（T16A，F-06 AC6）：OnItemTouchListener 手势状态机。
 * DOWN 记 anchor 并定目标态（起项未选→整段选中；已选→整段取消，可反向划回）；
 * MOVE 判 |dx|>|dy| 且超 touchSlop 进 DRAG_SELECT（否则交还垂直滚动）；
 * MOVE 中 findChildViewUnder→position，对 [min(last,cur)..max(last,cur)] 区间填充防漏格（AC6.2）。
 * v1.1.5 丝滑化：每步即时 payload 刷新（原版攒 9 格才刷，视觉严重滞后）；
 * 选中按 adapter position 取 id 落库（原版只刷可见 VH，屏外格会漏选）；
 * 划到列表上下边缘自动滚屏，格子从指下持续滚过（与微信一致）。
 */
class DragSelectHelper(
    private val rv: RecyclerView,
    private val store: SelectionStore,
) : RecyclerView.OnItemTouchListener {

    private val touchSlop = ViewConfiguration.get(rv.context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var dragging = false
    private var anchorPos = RecyclerView.NO_POSITION
    private var targetState = false
    private var lastPos = RecyclerView.NO_POSITION
    private val pending = mutableSetOf<Int>()

    val isActive get() = dragging

    private val lm get() = rv.layoutManager as? LockableGridLM

    // ---- 边缘自动滚屏（16ms/帧，速度随深入边缘程度加快） ----
    private val edgeZone = (rv.resources.displayMetrics.density * 72).toInt().coerceAtLeast(64)
    private val autoScroll = object : Runnable {
        override fun run() {
            if (!dragging) return
            val dy = when {
                lastY < edgeZone -> -scrollSpeed(lastY)
                lastY > rv.height - edgeZone -> scrollSpeed(rv.height - lastY)
                else -> 0
            }
            if (dy != 0) {
                lm?.forceScroll = true
                rv.scrollBy(0, dy)
                lm?.forceScroll = false
                dragTo(lastX, lastY) // 格子在指下移动，重算跨过的区间
            }
            rv.postOnAnimation(this)
        }
    }
    private fun scrollSpeed(depth: Float): Int =
        (depth / edgeZone * 28f).toInt().coerceIn(6, 36)

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                anchorPos = posAt(e.x, e.y)
                lastPos = anchorPos
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x; lastY = e.y
                if (!dragging && anchorPos != RecyclerView.NO_POSITION) {
                    if (abs(e.x - downX) > touchSlop && abs(e.x - downX) > abs(e.y - downY)) {
                        // 横向意图 → 进入划选；纵向手势=滚列表（与微信一致）
                        val vh = rv.findViewHolderForAdapterPosition(anchorPos)
                        val id = (vh as? GalleryAdapter.MediaVH)?.mediaId
                        if (id != null) {
                            targetState = !store.isSelected(id)
                            store.set(id, targetState)
                            dragging = true
                            lm?.scrollLocked = true
                            rv.requestDisallowInterceptTouchEvent(true)
                            rv.adapter?.notifyItemChanged(anchorPos, GalleryAdapter.PAYLOAD_SELECTION)
                            rv.postOnAnimation(autoScroll)
                            rv.adapter?.let { it as? GalleryAdapter }?.onSelectionChanged?.invoke(store.size())
                        }
                    }
                }
                if (dragging) dragTo(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
        }
        return dragging
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        lastX = e.x; lastY = e.y
        if (e.actionMasked == MotionEvent.ACTION_MOVE && dragging) dragTo(e.x, e.y)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) stop()
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun stop() {
        if (dragging) {
            dragging = false
            lm?.scrollLocked = false
            rv.removeCallbacks(autoScroll)
            flush()
        }
        anchorPos = RecyclerView.NO_POSITION
    }

    private fun posAt(x: Float, y: Float): Int {
        val child = rv.findChildViewUnder(x, y) ?: return RecyclerView.NO_POSITION
        return rv.getChildAdapterPosition(child)
    }

    private fun dragTo(x: Float, y: Float) {
        lastX = x; lastY = y
        val pos = posAt(x, y)
        if (pos == RecyclerView.NO_POSITION || pos == lastPos) return
        // 区间填充（AC6.2）：last→current 之间全部应用同一目标态
        val from = minOf(lastPos, pos); val to = maxOf(lastPos, pos)
        for (p in from..to) pending.add(p)
        lastPos = pos
        flush() // 每步即时刷新：划到哪亮到哪（v1.1.5 丝滑化）
    }

    /** 即时提交：按 adapter position 取 id 落库，屏外格同样生效 */
    private fun flush() {
        if (pending.isEmpty()) return
        val adapter = rv.adapter as? GalleryAdapter ?: run { pending.clear(); return }
        for (p in pending) {
            if (p < 0 || p >= adapter.itemCount) continue
            val id = adapter.itemAt(p).id
            if (store.set(id, targetState)) adapter.notifyItemChanged(p, GalleryAdapter.PAYLOAD_SELECTION)
        }
        pending.clear()
        adapter.onSelectionChanged?.invoke(store.size())
    }
}
