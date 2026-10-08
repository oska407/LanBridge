package com.lanbridge.gallery

/**
 * 选中态存储（T16A）：以 mediaId(_ID) 为键、禁用 position（AC6.5）；
 * orderedIds 保证发送顺序 = 勾选顺序；上限 100 张（AC6.4，不静默截断）。
 */
class SelectionStore(private val limit: Int = 100) {

    private val selected = LinkedHashSet<Long>()
    val orderedIds: List<Long> get() = selected.toList()

    var onLimitReached: (() -> Unit)? = null

    fun size() = selected.size

    /** @return 选中状态是否变化 */
    fun set(id: Long, selectedState: Boolean): Boolean {
        if (selectedState) {
            if (selected.contains(id)) return false
            if (selected.size >= limit) { onLimitReached?.invoke(); return false }
            return selected.add(id)
        }
        return selected.remove(id)
    }

    fun isSelected(id: Long) = selected.contains(id)
    fun isFull() = selected.size >= limit
    fun clear() = selected.clear()
}
