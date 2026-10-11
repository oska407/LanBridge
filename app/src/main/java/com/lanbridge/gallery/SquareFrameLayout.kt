package com.lanbridge.gallery

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 正方形容器：以宽度为基准强制高度相等，用于相册网格项。
 * 配合 ImageView centerCrop 即「取短边为边长、中心区域裁剪」的正方形预览（需求①）。
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val s = measuredWidth
        // 关键：用最终正方形尺寸按 EXACTLY 重测一遍子 View。
        // 之前只 super.onMeasure(原始 spec) 后直接改自身尺寸——父级传入的高度 spec 是
        // AT_MOST（wrap_content），match_parent 的 ImageView 按旧 spec 量出偏小的高度，
        // 布局时填不满正方形，格底露出黑边，视觉上「行距 ≠ 列距」（竖向黑带更宽）。
        val wSpec = MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY)
        super.onMeasure(wSpec, hSpec)
        setMeasuredDimension(s, s)
    }
}
