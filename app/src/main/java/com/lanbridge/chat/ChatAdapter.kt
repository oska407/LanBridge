package com.lanbridge.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.lanbridge.R
import com.lanbridge.model.Message
import com.lanbridge.model.MsgStatus
import com.lanbridge.model.MsgType
import com.lanbridge.server.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 气泡 Adapter（T10）：左右分列、payload 局部刷新（选中态/地址态不重绑内容）；
 * 时间分隔线仅相邻消息间隔 >5 分钟显示（F-20 AC10）。
 */
class ChatAdapter(
    private val selection: SelectionController,
    private val callbacks: Callbacks,
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    interface Callbacks {
        fun onCopy(msg: Message)
        fun onShare(msg: Message)
        fun onSave(msg: Message)
        fun onOpenWith(msg: Message)
        fun onLongPress(v: View, msg: Message)
        fun onResend(msg: Message)
        fun onCopyUrl()
        fun onPreviewImage(msg: Message) // 点图片看大图（v1.1.5）
    }

    private val items = mutableListOf<Message>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submit(list: List<Message>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun messageAt(pos: Int): Message = items[pos]

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = bind(holder, position, false)

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) { bind(holder, position, false); return }
        // 局部刷新：仅选中圈 / 地址状态（不重绑气泡内容，防止图片闪烁）
        val msg = items[position]
        if (payloads.contains(SessionState.PAYLOAD_SELECTION)) {
            bindSelection(holder, msg)
        } else {
            bind(holder, position, false)
        }
    }

    private fun bind(h: VH, pos: Int, payloadOnly: Boolean) {
        val msg = items[pos]
        val ctx = h.itemView.context

        // 时间分隔线（>5 分钟）
        val showTime = pos == 0 || (msg.ts - items[pos - 1].ts) > 5 * 60 * 1000
        h.tvTime.visibility = if (showTime) View.VISIBLE else View.GONE
        if (showTime) h.tvTime.text = timeFmt.format(Date(msg.ts))

        when (msg.type) {
            MsgType.SYSTEM_URL -> {
                h.bubble.setBackgroundResource(R.drawable.bg_system_card)
                h.bubble.gravity = android.view.Gravity.CENTER
                h.tvText.visibility = View.VISIBLE
                h.tvText.text = when (msg.connState) {
                    "connected" -> ctx.getString(R.string.connected_to, SessionState.peerIp) + "\n" + SessionState.selfUrl
                    "error" -> ctx.getString(R.string.port_occupied, 8080)
                    else -> ctx.getString(R.string.waiting_connection) + "\n" + SessionState.selfUrl
                }
                h.btnCopy.visibility = View.VISIBLE
                h.btnCopy.setOnClickListener { callbacks.onCopyUrl() }
                h.ivImage.visibility = View.GONE; h.fileRow.visibility = View.GONE
                h.imageWrap.visibility = View.GONE; h.tvPlay.visibility = View.GONE
                h.progress.visibility = View.GONE; h.tvStatus.visibility = View.GONE
                h.btnResend.visibility = View.GONE
                h.cbSelect.visibility = View.GONE // 地址消息不可勾选（F-17 AC4）
            }
            else -> {
                h.btnCopy.visibility = View.GONE
                h.bubble.gravity = android.view.Gravity.START
                val out = msg.from == "phone"
                h.bubble.setBackgroundResource(if (out) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in)
                h.row.gravity = if (out) android.view.Gravity.END else android.view.Gravity.START

                h.tvText.visibility = if (msg.type == MsgType.TEXT) View.VISIBLE else View.GONE
                if (msg.type == MsgType.TEXT) h.tvText.text = msg.text

                h.fileRow.visibility = if (msg.type == MsgType.FILE) View.VISIBLE else View.GONE
                if (msg.type == MsgType.FILE) {
                    h.tvFileName.text = msg.fileRef?.name.orEmpty()
                    h.tvFileSize.text = formatSize(msg.fileRef?.size ?: 0)
                }

                // 图片 / 视频（视频是 FILE 类型但 kind=video：取首帧 + 播放角标）
                val isVideo = msg.type == MsgType.FILE && msg.fileRef?.kind == "video"
                val showImage = msg.type == MsgType.IMAGE || isVideo
                h.imageWrap.visibility = if (showImage) View.VISIBLE else View.GONE
                h.ivImage.visibility = if (showImage) View.VISIBLE else View.GONE
                h.tvPlay.visibility = if (isVideo) View.VISIBLE else View.GONE
                val path = msg.fileRef?.localPath
                if (msg.type == MsgType.IMAGE) {
                    Glide.with(h.ivImage).load(path ?: msg.fileRef?.id).centerCrop().into(h.ivImage)
                    h.ivImage.setOnClickListener { callbacks.onPreviewImage(msg) } // 点图看大图
                } else if (isVideo && !path.isNullOrBlank()) {
                    loadVideoFrame(h.ivImage, path)
                    h.ivImage.setOnClickListener { callbacks.onOpenWith(msg) } // 点击调系统播放器
                } else {
                    h.ivImage.setOnClickListener(null)
                }

                // 发送/接收状态（F-19 AC1；RECEIVING = PC 正在上传过来）
                when (msg.status) {
                    MsgStatus.FAILED -> {
                        h.tvStatus.visibility = View.VISIBLE
                        // 接收失败无法在手机侧重发（文件在 PC），提示回电脑端点重发
                        val mine = msg.from == "phone"
                        h.tvStatus.text = if (mine) ctx.getString(R.string.send_failed)
                                          else ctx.getString(R.string.recv_failed_hint)
                        h.btnResend.visibility = if (mine) View.VISIBLE else View.GONE
                        if (mine) h.btnResend.setOnClickListener { callbacks.onResend(msg) }
                    }
                    MsgStatus.SENDING -> {
                        h.tvStatus.visibility = View.VISIBLE
                        h.tvStatus.text = "…"
                        h.btnResend.visibility = View.GONE
                    }
                    MsgStatus.RECEIVING -> {
                        h.tvStatus.visibility = View.VISIBLE
                        h.tvStatus.text = if (msg.progress >= 0) "接收中 ${msg.progress}%" else "接收中…"
                        h.btnResend.visibility = View.GONE
                    }
                    else -> { h.tvStatus.visibility = View.GONE; h.btnResend.visibility = View.GONE }
                }
                // 接收进度条（PC 上传分块时回填 0-100）
                if (msg.progress >= 0 && msg.status == MsgStatus.RECEIVING) {
                    h.progress.visibility = View.VISIBLE
                    h.progress.progress = msg.progress
                } else h.progress.visibility = View.GONE
                bindSelection(h, msg)
            }
        }

        // 多选模式：点整行切换选中（F-16 AC5，大热区）
        h.row.isClickable = selection.selectionMode
        h.row.isLongClickable = true
        h.row.setOnClickListener {
            if (selection.selectionMode) selection.toggle(msg)
        }
        h.row.setOnLongClickListener {
            callbacks.onLongPress(h.bubble, msg); true
        }
    }

    private fun bindSelection(h: VH, msg: Message) {
        val selectable = selection.selectionMode && msg.type != MsgType.SYSTEM_URL
        h.cbSelect.visibility = if (selection.selectionMode && msg.type != MsgType.SYSTEM_URL)
            View.VISIBLE else View.GONE
        h.cbSelect.setBackgroundResource(
            if (selection.isSelected(msg.id)) R.drawable.bg_circle_checked else R.drawable.bg_circle_unchecked)
        if (!selectable) h.row.isClickable = false
    }

    /** 视频首帧缩略图（v1.1.5）：IO 线程取帧避免卡滚动，取不到帧时回退 Glide 兜底 */
    private fun loadVideoFrame(iv: android.widget.ImageView, path: String) {
        iv.tag = path // 复用防串图
        CoroutineScope(Dispatchers.IO).launch {
            val bmp = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(path)
                    r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally { runCatching { r.release() } }
            }.getOrNull()
            if (iv.tag != path) return@launch
            iv.post {
                if (bmp != null) iv.setImageBitmap(bmp)
                else Glide.with(iv).load(path).centerCrop().into(iv)
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvTime)
        val row: LinearLayout = v.findViewById(R.id.row)
        val bubble: LinearLayout = v.findViewById(R.id.bubble)
        val tvText: TextView = v.findViewById(R.id.tvText)
        val ivImage: android.widget.ImageView = v.findViewById(R.id.ivImage)
        val imageWrap: View = v.findViewById(R.id.imageWrap)
        val tvPlay: TextView = v.findViewById(R.id.tvPlay)
        val fileRow: View = v.findViewById(R.id.fileRow)
        val tvFileName: TextView = v.findViewById(R.id.tvFileName)
        val tvFileSize: TextView = v.findViewById(R.id.tvFileSize)
        val progress: android.widget.ProgressBar = v.findViewById(R.id.progress)
        val tvStatus: TextView = v.findViewById(R.id.tvStatus)
        val btnResend: TextView = v.findViewById(R.id.btnResend)
        val btnCopy: TextView = v.findViewById(R.id.btnCopy)
        val cbSelect: View = v.findViewById(R.id.cbSelect)
    }
}
