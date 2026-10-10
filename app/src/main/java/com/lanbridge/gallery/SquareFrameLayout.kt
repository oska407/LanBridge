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
        setMeasuredDimension(s, s)
    }
}
