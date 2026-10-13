package com.lanbridge.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
        fun onRefreshIp() // 点击地址卡片：手动刷新 IP（v1.1.9j）
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

        // 气泡标准内边距（v1.1.9b 提前到 when 外：地址卡片分支也要用）
        val pad = (10 * ctx.resources.displayMetrics.density).toInt()

        // 复位气泡宽度与文字样式：VH 复用可能残留「系统红框提示」设成的 MATCH_PARENT / 红字 / 居中，
        // 普通/地址消息必须归位成「自适应宽 + 起始对齐 + 默认文字色」，否则会串样式。
        (h.bubble.layoutParams as LinearLayout.LayoutParams).apply {
            width = LinearLayout.LayoutParams.WRAP_CONTENT
            h.bubble.layoutParams = this
        }
        h.tvText.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        h.tvText.setTextColor(ContextCompat.getColor(ctx, R.color.n_text_primary))
        // 复位气泡点击监听：防 VH 复用把「地址卡片点击刷新 IP」带给普通消息/红框提示
        h.bubble.setOnClickListener(null)

        when (msg.type) {
            MsgType.SYSTEM_URL -> {
                // v1.1.9b：地址卡片每次必须显式归位「背景+内边距+对齐」——
                // 图片消息的复用会把 bubble 的 padding 清成 0、背景清成透明，
                // 只恢复背景不恢复 padding 会让文字顶到框边、外框视觉失效
                //（表现为发照片/滚动后卡片样式与刚启动时不一致）。
                h.row.gravity = android.view.Gravity.START
                h.bubble.gravity = android.view.Gravity.START // 卡片内文字左对齐
                // v1.1.9j：地址卡片可点击（手动刷新 IP）→ 改用带水波纹的背景；
                // error 态仍为红框（此时点击 = 重试绑定）
                h.bubble.setBackgroundResource(
                    if (msg.connState == "error") R.drawable.bg_system_card_error_ripple
                    else R.drawable.bg_system_card_ripple)
                h.bubble.setPadding(pad, pad, pad, pad)
                h.tvText.visibility = View.VISIBLE
                val refreshHint = ctx.getString(R.string.tap_to_refresh_ip)
                h.tvText.text = when (msg.connState) {
                    "connected" -> ctx.getString(R.string.connected_to, SessionState.peerIp) + "\n" + SessionState.selfUrl + "\n" + refreshHint
                    "error" -> ctx.getString(R.string.port_occupied, SessionState.conflictPort)
                    else -> ctx.getString(R.string.waiting_connection) + "\n" + SessionState.selfUrl + "\n" + refreshHint
                }
                // 点击卡片 = 手动刷新 IP（v1.1.9j）：无网时启动只能绑 0.0.0.0，连上网后
                // 系统网络回调在部分 ROM 上不可靠；用户要求不做轮询，改为点卡片时重读网卡并重绑
                h.bubble.setOnClickListener { if (!selection.selectionMode) callbacks.onRefreshIp() }
                h.btnCopy.visibility = View.VISIBLE
                h.btnCopy.setOnClickListener { callbacks.onCopyUrl() }
                h.ivImage.visibility = View.GONE; h.fileRow.visibility = View.GONE
                h.imageWrap.visibility = View.GONE; h.tvPlay.visibility = View.GONE
                h.progress.visibility = View.GONE; h.tvStatus.visibility = View.GONE
                h.btnResend.visibility = View.GONE
                h.cbSelect.visibility = View.GONE // 地址消息不可勾选（F-17 AC4）
            }
            MsgType.SYSTEM_NOTICE -> {
                // v1.1.9i+：系统红框提示（端口被占用等）——整宽居中、红框红字、不可勾选。
                // 因 ChatActivity 每次变更都自动滚到末尾，这条会可靠地出现在聊天记录底部、立即可见。
                h.row.gravity = Gravity.CENTER_HORIZONTAL
                (h.bubble.layoutParams as LinearLayout.LayoutParams).apply {
                    width = LinearLayout.LayoutParams.MATCH_PARENT
                    h.bubble.layoutParams = this
                }
                h.bubble.gravity = Gravity.CENTER_HORIZONTAL
                h.bubble.setBackgroundResource(R.drawable.bg_system_card_error)
                h.bubble.setPadding(pad, pad, pad, pad)
                h.tvText.visibility = View.VISIBLE
                h.tvText.textAlignment = View.TEXT_ALIGNMENT_CENTER
                h.tvText.setTextColor(ContextCompat.getColor(ctx, R.color.danger_text))
                h.tvText.text = msg.text
                h.btnCopy.visibility = View.GONE
                h.ivImage.visibility = View.GONE; h.fileRow.visibility = View.GONE
                h.imageWrap.visibility = View.GONE; h.tvPlay.visibility = View.GONE
                h.progress.visibility = View.GONE; h.tvStatus.visibility = View.GONE
                h.btnResend.visibility = View.GONE
                h.cbSelect.visibility = View.GONE // 系统提示不可勾选
            }
            else -> {
                h.btnCopy.visibility = View.GONE
                h.bubble.gravity = android.view.Gravity.START
                val out = msg.from == "phone"
                h.row.gravity = if (out) android.view.Gravity.END else android.view.Gravity.START

                val isVideo = msg.type == MsgType.FILE && msg.fileRef?.kind == "video"
                val showImage = msg.type == MsgType.IMAGE || isVideo
                // 图片/视频消息去掉气泡背景与 padding，避免绿/白边；文本/文件保持气泡
                if (showImage) {
                    h.bubble.setBackgroundResource(android.R.color.transparent)
                    h.bubble.setPadding(0, 0, 0, 0)
                } else {
                    h.bubble.setBackgroundResource(if (out) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in)
                    h.bubble.setPadding(pad, pad, pad, pad)
                }

                h.tvText.visibility = if (msg.type == MsgType.TEXT) View.VISIBLE else View.GONE
                if (msg.type == MsgType.TEXT) h.tvText.text = msg.text

                // 视频按微信样式只显缩略图+播放角标，不叠文件卡（文件卡含文件名+大小+问号，冗余）
                h.fileRow.visibility = if (msg.type == MsgType.FILE && msg.fileRef?.kind != "video") View.VISIBLE else View.GONE
                if (msg.type == MsgType.FILE) {
                    val name = msg.fileRef?.name.orEmpty()
                    h.tvFileName.text = name
                    h.tvFileSize.text = formatSize(msg.fileRef?.size ?: 0)
                    bindFileIcon(h, name)
                }

                // 图片 / 视频（视频是 FILE 类型但 kind=video：取首帧 + 播放角标）
                h.imageWrap.visibility = if (showImage) View.VISIBLE else View.GONE
                h.ivImage.visibility = if (showImage) View.VISIBLE else View.GONE
                h.tvPlay.visibility = if (isVideo) View.VISIBLE else View.GONE
                val path = msg.fileRef?.localPath
                if (msg.type == MsgType.IMAGE) {
                    Glide.with(h.ivImage).load(path ?: msg.fileRef?.id).fitCenter().into(h.ivImage)
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
            }
        }

        // 多选模式：点整行切换选中（F-16 AC5，大热区）
        h.row.setOnClickListener {
            if (selection.selectionMode) selection.toggle(msg)
        }
        h.row.setOnLongClickListener {
            callbacks.onLongPress(h.bubble, msg); true
        }
        bindSelection(h, msg) // 由它控制 clickable/longClickable 与圆圈状态
    }

    /**
     * 选中态（v1.1.8 整理）：圆圈显隐 + 勾选态 + 点击/长按归属。
     * 注意：本方法会由 payload 局部刷新单独调用（不重绑气泡内容，防图片闪烁），
     * 因此这里必须只碰「选中相关」的属性，不能依赖 bind() 里的赋值。
     */
    private fun bindSelection(h: VH, msg: Message) {
        val selectable = selection.selectionMode && selection.canSelect(msg)
        h.cbSelect.visibility = if (selectable) View.VISIBLE else View.GONE
        val selected = selection.isSelected(msg.id)
        h.cbSelect.setBackgroundResource(if (selected) R.drawable.bg_circle_checked else R.drawable.bg_select_off)
        h.cbSelect.text = if (selected) "✓" else ""
        // 圆圈本身也做成热区（与点整行等价）；并给出朗读描述，不让 TalkBack 读成一个空圆
        h.cbSelect.contentDescription = h.itemView.context.getString(
            if (selected) R.string.cd_deselect else R.string.cd_select)
        h.cbSelect.setOnClickListener { if (selection.selectionMode) selection.toggle(msg) }
        // 系统消息（地址卡片 / 红框提示）：不参与多选、不长按弹菜单（v1.1.9j）——
        // 菜单项对系统消息无意义，「删除」还会误删首条地址消息
        if (msg.type == MsgType.SYSTEM_URL || msg.type == MsgType.SYSTEM_NOTICE) {
            h.row.isClickable = false
            h.row.isLongClickable = false
            h.ivImage.isClickable = false
            h.ivImage.isLongClickable = false
            return
        }
        if (selectable) {
            // 多选模式：点整行切换选中（含图片区域）；图片不抢触摸，长按在此模式无意义
            h.row.isClickable = true
            h.row.isLongClickable = false
            h.ivImage.isClickable = false
            h.ivImage.isLongClickable = false
        } else {
            // 普通模式：图片自身可点（看大图）+ 可长按（弹菜单）；图片外的气泡区域由 row 接长按
            h.row.isClickable = false
            h.row.isLongClickable = true
            h.ivImage.isClickable = true
            h.ivImage.isLongClickable = true
            h.ivImage.setOnLongClickListener { callbacks.onLongPress(h.bubble, msg); true }
        }
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
                else Glide.with(iv).load(path).fitCenter().into(iv)
            }
        }
    }

    /**
     * v1.1.9d 微信样式文件图标：按扩展名选彩色页面图标。
     * 字母徽标（PDF/W/X/P/?）由 tvFileBadge 覆盖显示；TXT/ZIP/音频的图形已画进图标。
     * 注意：每个 FILE bind 都必须同时重设图标和徽标（VH 复用防残留）。
     */
    private fun bindFileIcon(h: VH, name: String) {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        var res = R.drawable.ic_file_generic
        var badge: String? = "?"
        var small = false
        when (ext) {
            "pdf" -> { res = R.drawable.ic_file_pdf; badge = "PDF"; small = true }
            "doc", "docx", "wps", "rtf", "odt" -> { res = R.drawable.ic_file_doc; badge = "W" }
            "xls", "xlsx", "et", "csv", "ods" -> { res = R.drawable.ic_file_xls; badge = "X" }
            "ppt", "pptx", "dps", "odp" -> { res = R.drawable.ic_file_ppt; badge = "P" }
            "txt", "md", "log" -> { res = R.drawable.ic_file_txt; badge = null }
            "zip", "rar", "7z", "tar", "gz" -> { res = R.drawable.ic_file_zip; badge = null }
            "mp3", "wav", "flac", "aac", "m4a", "ogg", "amr" -> { res = R.drawable.ic_file_audio; badge = null }
            // v1.1.9d 新增：安装包 / 电子书 / 网页代码 / 设计稿 / 种子 / CAD / 联系人 / 日历
            "exe", "msi", "dmg", "deb", "AppImage", "pkg" -> { res = R.drawable.ic_file_installer; badge = null }
            "epub", "mobi", "azw", "azw3", "kfx" -> { res = R.drawable.ic_file_ebook; badge = null }
            "html", "htm", "css", "js", "ts", "jsx", "vue", "php", "json", "xml" -> { res = R.drawable.ic_file_webcode; badge = null }
            "psd", "ai", "cdr", "sketch", "xd" -> { res = R.drawable.ic_file_design; badge = null }
            "torrent" -> { res = R.drawable.ic_file_torrent; badge = null }
            "dwg", "dxf", "skp", "skb", "3ds", "obj", "fbx", "stl", "step", "stp",
            "igs", "iges", "dae", "blend", "max", "c4d", "rvt", "sldprt", "sldasm",
            "ipt", "iam", "sat", "scad" -> { res = R.drawable.ic_file_cad; badge = null }
            "vcf" -> { res = R.drawable.ic_file_contact; badge = null }
            "ics" -> { res = R.drawable.ic_file_calendar; badge = null }
            // apk 与其它未知格式统一走灰色页面 + 问号（ic_file_generic）
        }
        h.ivFileIcon.setImageResource(res)
        if (badge == null) {
            h.tvFileBadge.visibility = View.GONE
        } else {
            h.tvFileBadge.visibility = View.VISIBLE
            h.tvFileBadge.text = badge
            // 多字母徽标（PDF）用小号，单字母/问号用大号
            h.tvFileBadge.textSize = if (small) 8f else 14f
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
        val ivFileIcon: android.widget.ImageView = v.findViewById(R.id.ivFileIcon)
        val tvFileBadge: TextView = v.findViewById(R.id.tvFileBadge)
        val progress: android.widget.ProgressBar = v.findViewById(R.id.progress)
        val tvStatus: TextView = v.findViewById(R.id.tvStatus)
        val btnResend: TextView = v.findViewById(R.id.btnResend)
        val btnCopy: TextView = v.findViewById(R.id.btnCopy)
        val cbSelect: TextView = v.findViewById(R.id.cbSelect)
    }
}
