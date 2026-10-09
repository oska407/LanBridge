package com.lanbridge.gallery

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.lanbridge.R

/** 缩略图加载（T15）：仅系统缩略图零原图解码 —— Glide 直接吃 MediaStore uri（内置 MINI 缓存路径） */
object ThumbLoader {
    fun load(iv: ImageView, uri: Uri, isVideo: Boolean) {
        Glide.with(iv.context)
            .load(uri)
            .centerCrop()
            .override(256, 256) // 网格 256×256，单图解码后 ≤200KB
            .into(iv)
    }

    fun loadThumbnailReserved(ctx: android.content.Context, uri: Uri, size: Int = 256): android.graphics.Bitmap? =
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null)
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver,
                    android.content.ContentUris.parseId(uri), MediaStore.Images.Thumbnails.MINI_KIND, null)
            }
        }.getOrNull()
}

/**
 * 网格 Adapter（T15/T16）：游标分页仅绑定可见项；划选/多选一律 payload 局部刷新，
 * onBindViewHolder(payload 非空) 只改圆圈+遮罩，绝不重新 load 缩略图（AC6.6）。
 */
class GalleryAdapter(
    private val store: SelectionStore,
) : RecyclerView.Adapter<GalleryAdapter.MediaVH>() {

    companion object { const val PAYLOAD_SELECTION = "payload_sel" }

    private val items = mutableListOf<GalleryRepository.MediaItem>()
    var onSelectionChanged: ((Int) -> Unit)? = null
    var onSingleClick: ((Int) -> Unit)? = null // 划选期被拦截，仅未移动的抬起走这里（AC6.3）

    fun submitPage(list: List<GalleryRepository.MediaItem>, append: Boolean) {
        if (append) { val start = items.size; items.addAll(list); notifyItemRangeInserted(start, list.size) }
        else { items.clear(); items.addAll(list); notifyDataSetChanged() }
    }

    fun itemAt(pos: Int) = items[pos]

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaVH =
        MediaVH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun onBindViewHolder(h: MediaVH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) { bind(h, position); return }
        bindSelection(h, items[position].id)
    }

    override fun onBindViewHolder(h: MediaVH, position: Int) = bind(h, position)

    /** 只刷新选中态：遮罩 + 圆点徽章（选中显示勾选顺序号，与微信一致），绝不重载缩略图 */
    private fun bindSelection(h: MediaVH, id: Long) {
        val selected = store.isSelected(id)
        h.viewMask.visibility = if (selected) View.VISIBLE else View.GONE
        if (selected) {
            h.viewCircle.setBackgroundResource(R.drawable.bg_circle_checked)
            h.viewCircle.text = (store.orderedIds.indexOf(id) + 1).toString()
        } else {
            h.viewCircle.setBackgroundResource(R.drawable.bg_circle_unchecked)
            h.viewCircle.text = ""
        }
    }

    private fun bind(h: MediaVH, position: Int) {
        val item = items[position]
        h.mediaId = item.id
        ThumbLoader.load(h.ivThumb, item.uri, item.isVideo)
        bindSelection(h, item.id)
        // 首版：点格子或圆圈均可切换选中（预览页 P1 接入后改为点格=预览）
        h.itemView.setOnClickListener { onSingleClick?.invoke(position) }
    }

    class MediaVH(v: View) : RecyclerView.ViewHolder(v) {
        val ivThumb: ImageView = v.findViewById(R.id.ivThumb)
        val viewMask: View = v.findViewById(R.id.viewMask)
        val viewCircle: TextView = v.findViewById(R.id.viewCircle)
        var mediaId: Long? = null
    }
}
