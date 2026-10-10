package com.lanbridge.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.lanbridge.R
import com.lanbridge.gallery.GalleryActivity
import com.lanbridge.media.SaveToDownloads
import com.lanbridge.model.Message
import com.lanbridge.server.SessionState
import com.lanbridge.server.TransferEngine
import com.lanbridge.service.BridgeForegroundService
import com.lanbridge.settings.SettingsActivity
import com.lanbridge.util.CrashLogger
import com.lanbridge.wechat.InboundCopier
import com.lanbridge.wechat.OutboundShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 聊天页（T10）：根页面无返回箭头、锁竖屏、edge-to-edge 三处 bottom inset、
 * 工具条四项（发文件/发照片/设置/清空，清空末位标红 + 二次确认）。
 */
class ChatActivity : AppCompatActivity(), ChatAdapter.Callbacks, MessageMenu.Callbacks {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var selection: SelectionController
    private lateinit var titleBar: View
    private lateinit var toolBar: View
    private lateinit var composer: View
    private lateinit var selectionBar: View
    private lateinit var selectionActionBar: View
    private lateinit var tvSelCount: TextView
    private lateinit var btnSend: TextView
    private lateinit var etInput: TextView
    private var pendingSendText: String? = null
    private lateinit var msgListener: (List<Message>) -> Unit
    private lateinit var incomingListener: (Message) -> Unit

    private val requestMedia =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openGallery()
            else Snackbar.make(recycler, "LanBridge 需要相册权限才能发送照片", Snackbar.LENGTH_LONG)
                .setAction("再去一次") { openGallery() }.show() // 权限四段式（T10E）
        }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            // 直发通道（F-04 AC5）：选完即发，不进输入区；已有文字一并带出（AC5.1）
            pendingSendText?.let { TransferEngine.sendText(it); pendingSendText = null; etInput.text = "" }
            uris.forEach { uri -> publishPickedFile(uri) }
        }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val paths = r.data?.getStringArrayListExtra(GalleryActivity.RESULT_FILES) ?: return@registerForActivityResult
            val original = r.data?.getBooleanExtra(GalleryActivity.RESULT_ORIGINAL, false) ?: false
            val names = r.data?.getStringArrayListExtra(GalleryActivity.RESULT_NAMES) ?: arrayListOf()
            val pkg = r.data?.getBooleanExtra(GalleryActivity.RESULT_PACKAGE, false) ?: false
            pendingSendText?.let { TransferEngine.sendText(it); pendingSendText = null; etInput.text = "" }
            if (pkg) {
                dispatchPackage(paths, names, original)
            } else {
                paths.forEachIndexed { i, p ->
                    val name = names.getOrNull(i) ?: java.io.File(p).name
                    sendImageFile(java.io.File(p), name, original)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_chat)

        titleBar = findViewById(R.id.titleBar)
        toolBar = findViewById(R.id.toolBar)
        composer = findViewById(R.id.composer)
        selectionBar = findViewById(R.id.selectionBar)
        selectionActionBar = findViewById(R.id.selectionActionBar)
        tvSelCount = findViewById(R.id.tvSelCount)
        recycler = findViewById(R.id.recycler)
        btnSend = findViewById(R.id.btnSend)
        etInput = findViewById(R.id.etInput)

        // edge-to-edge：标题栏 top 内缩状态栏；根容器 bottom 内缩导航栏/键盘（二者取并集）。
        // 仅对根容器统一加底部 inset —— 之前对 toolBar（仅48dp）单独加 bars.bottom+8 的
        // padding，在 3 按钮导航栏下该 padding 超过工具条高度，把 5 个按钮挤出可视区，
        // 表现即「安卓端缺少工具栏」。现改为根容器统一内缩，工具条/输入区均完整可见。
        // v1.1.5：底部 inset 改 systemBars∪ime —— 导航栏显隐两种状态都跟随其上缘，
        // 键盘弹出时输入区也随键盘上移（decorFitsSystemWindows(false) 下框架不再自动避让）。
        val rootView = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            titleBar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + 8)
            rootView.updatePadding(bottom = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom)
            WindowInsetsCompat.CONSUMED
        }

        selection = SelectionController()
        selection.onChanged = { syncSelectionUi() }
        adapter = ChatAdapter(selection, this)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter
        recycler.isVerticalScrollBarEnabled = false // 安卓无滚动条（F-18 AC1）

        adapter.submit(SessionState.messages.toList())
        // 监听器保存引用：onDestroy 必须反注册，否则页面重建后旧监听堆积、新消息可能被旧页吃掉
        msgListener = { list ->
            runOnUiThread {
                adapter.submit(list)
                recycler.scrollToPosition((list.size - 1).coerceAtLeast(0))
                syncSelectionUi()
                syncHeader()
            }
        }
        SessionState.onMessagesChanged(msgListener)

        // PC 来消息即时反馈：有 Toast 但没气泡 = 显示层问题；连 Toast 都没有 = 服务端没收到
        incomingListener = { m ->
            runOnUiThread {
                val tip = if (m.type == com.lanbridge.model.MsgType.TEXT) m.text
                          else (m.fileRef?.name ?: "文件")
                Toast.makeText(this, "收到 PC：$tip", Toast.LENGTH_SHORT).show()
            }
        }
        SessionState.onIncoming(incomingListener)
        syncHeader()
        // 启动时显式同步一次工具条/输入区可见性（默认非多选 → 工具条/输入区可见）
        syncSelectionUi()

        // 工具条（五项）
        findViewById<View>(R.id.btnFile).setOnClickListener {
            pendingSendText = etInput.text?.toString()?.takeIf { it.isNotBlank() }
            pickFiles.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.btnPhoto).setOnClickListener { checkMediaThenGallery() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }

        // 发送（IME actionSend + 按钮兜底，T10D）
        etInput.setOnClickListener { }
        btnSend.setOnClickListener { sendText() }

        // 多选操作栏
        findViewById<View>(R.id.btnSelExit).setOnClickListener { selection.exit() }
        findViewById<View>(R.id.btnSelAll).setOnClickListener { selection.selectAll(SessionState.messages.toList()) }
        findViewById<View>(R.id.btnSaveBatch).setOnClickListener { batchSave() }
        findViewById<View>(R.id.btnForwardBatch).setOnClickListener { batchShare() }
        findViewById<View>(R.id.btnCopyBatch).setOnClickListener { batchCopy() }
        findViewById<View>(R.id.btnDeleteBatch).setOnClickListener { batchDelete() }

        BridgeForegroundService.start(this)

        // 崩溃诊断：上次异常退出/记录的堆栈弹窗展示，支持一键复制回传
        CrashLogger.latest(this)?.let { showCrashReport(it) }
    }

    /** 回到前台强制重绘一次：服务侧在后台入列的消息，即使错过刷新也不会"看不见" */
    override fun onResume() {
        super.onResume()
        val list = SessionState.messages.toList()
        adapter.submit(list)
        recycler.scrollToPosition((list.size - 1).coerceAtLeast(0))
        syncHeader()
        syncSelectionUi()
    }

    override fun onDestroy() {
        runCatching { SessionState.removeListener(msgListener) }
        runCatching { SessionState.removeIncomingListener(incomingListener) }
        super.onDestroy()
    }

    private fun showCrashReport(trace: String) {
        val tv = TextView(this).apply {
            text = trace
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("检测到上次运行异常")
            .setView(scroll)
            .setPositiveButton("复制日志") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("lanbridge_crash", trace))
                Toast.makeText(this, "已复制，请粘贴发送给开发者", Toast.LENGTH_LONG).show()
                CrashLogger.clear(this)
            }
            .setNegativeButton("关闭并清除") { _, _ -> CrashLogger.clear(this) }
            .setNeutralButton("仅关闭", null)
            .show()
    }

    private fun syncHeader() {
        val tvConn = findViewById<TextView>(R.id.tvConn)
        val dot = findViewById<View>(R.id.statusDot)
        when (SessionState.connStatus) {
            "connected" -> { tvConn.text = getString(R.string.connected_to, SessionState.peerIp); dot.setBackgroundResource(R.drawable.bg_circle_checked) }
            "error" -> { tvConn.text = getString(R.string.port_occupied, com.lanbridge.settings.SettingsRepository.get(this).serverPort); dot.setBackgroundResource(R.drawable.bg_circle_unchecked) }
            else -> { tvConn.text = getString(R.string.waiting_connection); dot.setBackgroundResource(R.drawable.bg_circle_unchecked) }
        }
    }

    private fun sendText() {
        val text = etInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        TransferEngine.sendText(text)
        etInput.text = ""
    }

    private fun checkMediaThenGallery() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            openGallery()
        } else {
            requestMedia.launch(perm) // 按需申请，不做一次性全量弹窗（F-20 AC11）
        }
    }

    private fun openGallery() {
        galleryLauncher.launch(Intent(this, GalleryActivity::class.java))
    }

    /** SAF 直发通道（F-20 AC3：安卓发文件多选、无张数上限、直发） */
    private fun publishPickedFile(uri: Uri) {
        val name = InboundCopier.queryDisplayName(this, uri)
        val copied = InboundCopier.copyUriToLocal(this, uri, name) ?: run {
            Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show(); return
        }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        TransferEngine.publishFile(copied, name, mime, "file", false)
    }

    /** 相册图片发送：非原图按设置压缩（F-06 AC4），视频/原图直传（F-20 AC1）。
     *  originalName 为相册原文件名（MediaStore DISPLAY_NAME），全程透传，
     *  传输名与保存名都不改；kind/mime 由原名扩展名推导，避免视频被当图片。 */
    private fun sendImageFile(src: java.io.File, originalName: String, original: Boolean) {
        val settings = com.lanbridge.settings.SettingsRepository.get(this)
        val (kind, mime) = kindMimeFromName(originalName)
        if (original || settings.compressMaxLongSide <= 0) {
            TransferEngine.publishFile(src, originalName, mime, kind, true)
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val out = com.lanbridge.media.ImageCompressor.compress(
                src, settings.compressMaxLongSide, settings.compressQuality,
                java.io.File(cacheDir, "lanbridge_out"))
            val f = out ?: src
            // 压缩后仍沿用原图文件名（用户要求传输/保存不改名），内容是否重编码由「原图」开关决定
            TransferEngine.publishFile(f, originalName, mime, kind, false)
        }
    }

    /** 由文件名扩展名推导 kind/mime：图片按 image 系列、视频按 video 系列，兜底 image/jpeg */
    private fun kindMimeFromName(name: String): Pair<String, String> {
        val n = name.lowercase()
        return when {
            n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".mkv") ||
                n.endsWith(".webm") || n.endsWith(".mov") -> "video" to "video/mp4"
            n.endsWith(".png") -> "image" to "image/png"
            n.endsWith(".webp") -> "image" to "image/webp"
            n.endsWith(".gif") -> "image" to "image/gif"
            n.endsWith(".heic") || n.endsWith(".heif") -> "image" to "image/heic"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image" to "image/jpeg"
            else -> "image" to "image/jpeg"
        }
    }

    /** 打包发送（v1.1.6）：逐个按原图/压缩策略备好文件，再压成单个 zip 一次性发出（压缩在 IO 线程） */
    private fun dispatchPackage(paths: ArrayList<String>, names: ArrayList<String>, original: Boolean) {
        if (paths.isEmpty()) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val entries = paths.mapIndexedNotNull { i, p ->
                val src = java.io.File(p)
                if (!src.exists()) return@mapIndexedNotNull null
                val name = names.getOrNull(i) ?: src.name
                prepareSendFile(src, name, original) to name
            }
            if (entries.isEmpty()) return@launch
            val zip = java.io.File(java.io.File(cacheDir, "lanbridge_zip"),
                "LanBridge_打包_${System.currentTimeMillis()}.zip")
            if (com.lanbridge.util.ZipUtil.zip(entries, zip)) {
                TransferEngine.publishFile(zip, zip.name, "application/zip", "file", false)
            } else {
                runOnUiThread {
                    Toast.makeText(this@ChatActivity, R.string.send_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 取实际要发送的文件：原图直传，否则按设置压缩（压缩失败回退原文件） */
    private fun prepareSendFile(src: java.io.File, originalName: String, original: Boolean): java.io.File {
        val settings = com.lanbridge.settings.SettingsRepository.get(this)
        if (original || settings.compressMaxLongSide <= 0) return src
        return com.lanbridge.media.ImageCompressor.compress(
            src, settings.compressMaxLongSide, settings.compressQuality,
            java.io.File(cacheDir, "lanbridge_out")) ?: src
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_confirm_title)
            .setMessage(R.string.clear_confirm_msg)
            .setPositiveButton(R.string.toolbar_clear) { _, _ ->
                SessionState.clearMessages() // 保留首条地址消息（F-19 AC3）
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- SelectionController UI（F-16 AC5） ----
    private fun syncSelectionUi() {
        val inSel = selection.selectionMode
        selectionBar.visibility = if (inSel) View.VISIBLE else View.GONE
        selectionActionBar.visibility = if (inSel) View.VISIBLE else View.GONE
        toolBar.visibility = if (inSel) View.GONE else View.VISIBLE
        composer.visibility = if (inSel) View.GONE else View.VISIBLE
        tvSelCount.text = getString(R.string.selected_count, selection.selectedIds.size)
        val enabled = selection.selectedIds.isNotEmpty()
        val hasText = selectedMessages().any { it.type == com.lanbridge.model.MsgType.TEXT }
        findViewById<View>(R.id.btnSaveBatch).isEnabled = enabled
        findViewById<View>(R.id.btnForwardBatch).isEnabled = enabled
        findViewById<View>(R.id.btnCopyBatch).isEnabled = enabled && hasText
        findViewById<View>(R.id.btnDeleteBatch).isEnabled = enabled
    }

    private fun selectedMessages(): List<Message> =
        SessionState.messages.filter { selection.selectedIds.contains(it.id) }

    /** 批量转发降级（F-03 AC4）：全图一次多发；含文件逐个分享（提示）；全文字合并一段 */
    private fun batchShare() {
        val msgs = selectedMessages()
        val texts = msgs.filter { it.type == com.lanbridge.model.MsgType.TEXT }.map { it.text }
        val images = msgs.mapNotNull { it.fileRef }.filter { it.kind == "image" }
            .mapNotNull { it.localPath }.map { java.io.File(it) }.filter { it.exists() }
        val hasFile = msgs.any { it.fileRef != null && it.fileRef.kind != "image" }
        when {
            msgs.all { it.type == com.lanbridge.model.MsgType.TEXT } && texts.isNotEmpty() ->
                OutboundShare.shareText(this, texts.joinToString("\n"))
            images.isNotEmpty() && !hasFile -> OutboundShare.shareImages(this, images)
            else -> {
                Toast.makeText(this, getString(R.string.share_sequential, msgs.size), Toast.LENGTH_SHORT).show()
                msgs.firstOrNull { it.fileRef != null }?.fileRef?.localPath?.let { p ->
                    val f = java.io.File(p)
                    if (f.exists()) OutboundShare.shareFile(this, f, "application/octet-stream")
                }
            }
        }
    }

    private fun batchSave() {
        val msgs = selectedMessages()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var ok = 0
            msgs.forEach { m ->
                m.fileRef?.localPath?.let { p ->
                    val f = java.io.File(p)
                    if (f.exists() && SaveToDownloads.save(this@ChatActivity, f, m.fileRef!!.mime, m.fileRef!!.name)) ok++
                }
            }
            runOnUiThread { Toast.makeText(this@ChatActivity, getString(R.string.save_ok) + " ($ok)", Toast.LENGTH_SHORT).show() }
        }
    }

    /** 批量复制：仅复制文字消息，按换行拼接 */
    private fun batchCopy() {
        val texts = selectedMessages()
            .filter { it.type == com.lanbridge.model.MsgType.TEXT }
            .map { it.text }
        if (texts.isEmpty()) {
            Toast.makeText(this, "没有可复制的文字", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("lanbridge_batch", texts.joinToString("\n")))
        Toast.makeText(this, "已复制 ${texts.size} 条文字", Toast.LENGTH_SHORT).show()
    }

    private fun batchDelete() {
        val ids = selection.selectedIds.toSet()
        synchronized(SessionState.messages) {
            SessionState.messages.removeAll { ids.contains(it.id) }
        }
        selection.exit()
        SessionState.notifyChanged()
    }

    // ---- ChatAdapter.Callbacks / MessageMenu.Callbacks ----
    override fun onCopy(msg: Message) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("msg", msg.text))
        Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onCopyUrl() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", SessionState.selfUrl))
        Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show() // Toast 纯告知（T10E）
    }

    override fun onShare(msg: Message) {
        val ref = msg.fileRef
        when {
            msg.type == com.lanbridge.model.MsgType.TEXT -> OutboundShare.shareText(this, msg.text)
            ref?.localPath != null && java.io.File(ref.localPath!!).exists() ->
                OutboundShare.shareFile(this, java.io.File(ref.localPath!!), ref.mime)
        }
    }

    override fun onShareWeChat(msg: Message) {
        // 「分享到微信」菜单项（本软件核心价值）：文本直发，文件经 FileProvider
        when {
            msg.type == com.lanbridge.model.MsgType.TEXT -> OutboundShare.shareText(this, msg.text)
            msg.fileRef?.localPath != null && java.io.File(msg.fileRef!!.localPath!!).exists() ->
                OutboundShare.shareFile(this, java.io.File(msg.fileRef!!.localPath!!), msg.fileRef!!.mime)
        }
    }

    override fun onSave(msg: Message) {
        val ref = msg.fileRef ?: return
        val path = ref.localPath ?: return
        val f = java.io.File(path)
        if (!f.exists()) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val ok = SaveToDownloads.save(this@ChatActivity, f, ref.mime, ref.name)
            runOnUiThread {
                Toast.makeText(this@ChatActivity,
                    if (ok) R.string.save_ok else R.string.send_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPreviewImage(msg: Message) {
        // 点图片看大图（v1.1.5）：接收的文件在 cacheDir/lanbridge_tmp 下，存在即可查看
        val p = msg.fileRef?.localPath ?: return
        if (!java.io.File(p).exists()) return
        PhotoViewActivity.current = msg
        startActivity(Intent(this, PhotoViewActivity::class.java)
            .putExtra(PhotoViewActivity.EXTRA_PATH, p)
            .putExtra(PhotoViewActivity.EXTRA_NAME, msg.fileRef?.name)
            .putExtra(PhotoViewActivity.EXTRA_MIME, msg.fileRef?.mime))
    }

    override fun onOpenWith(msg: Message) {
        val ref = msg.fileRef ?: return
        val path = ref.localPath ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.lanbridge.fileprovider", java.io.File(path))
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ref.mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(i) }
    }

    override fun onMultiSelect(msg: Message) { selection.enter(); selection.toggle(msg) }

    override fun onDelete(msg: Message) {
        synchronized(SessionState.messages) { SessionState.messages.remove(msg) }
        SessionState.notifyChanged()
    }

    override fun onLongPress(v: View, msg: Message) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) // 长按反馈（T10D）
        MessageMenu.show(v, msg, this)
    }

    override fun onResend(msg: Message) {
        // 手动重发不限次数（F-19 AC1）：文本重发；文件重新发布
        if (msg.type == com.lanbridge.model.MsgType.TEXT) {
            TransferEngine.sendText(msg.text)
        } else {
            msg.fileRef?.let { ref ->
                ref.localPath?.let { p ->
                    val f = java.io.File(p)
                    if (f.exists()) TransferEngine.publishFile(f, ref.name, ref.mime, ref.kind, ref.isOriginal)
                }
            }
        }
    }

    override fun onBackPressed() {
        if (selection.exit()) return // 返回键退出多选（F-16 AC4）
        super.onBackPressed()
    }
}
