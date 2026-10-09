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
import kotlinx.coroutines.launch

/**
 * 聊天页（T10）：根页面无返回箭头、锁竖屏、edge-to-edge 三处 bottom inset、
 * 工具条五项（发文件/发照片/截屏/设置/清空，清空末位标红 + 二次确认）。
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
            pendingSendText?.let { TransferEngine.sendText(it); pendingSendText = null; etInput.text = "" }
            paths.forEach { p -> sendImageFile(java.io.File(p), original) }
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

        // edge-to-edge：标题栏 top + 工具条/操作栏/输入区 三处 bottom（T10D，缺一不可）
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            titleBar.updatePadding(top = bars.top + 8)
            listOf(toolBar, selectionBar, selectionActionBar, composer).forEach {
                it.updatePadding(bottom = bars.bottom + 8)
            }
            WindowInsetsCompat.CONSUMED
        }

        selection = SelectionController()
        selection.onChanged = { syncSelectionUi() }
        adapter = ChatAdapter(selection, this)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter
        recycler.isVerticalScrollBarEnabled = false // 安卓无滚动条（F-18 AC1）

        adapter.submit(SessionState.messages.toList())
        SessionState.onMessagesChanged { list ->
            runOnUiThread {
                adapter.submit(list)
                recycler.scrollToPosition((list.size - 1).coerceAtLeast(0))
                syncSelectionUi()
                syncHeader()
            }
        }
        syncHeader()

        // 工具条（五项）
        findViewById<View>(R.id.btnFile).setOnClickListener {
            pendingSendText = etInput.text?.toString()?.takeIf { it.isNotBlank() }
            pickFiles.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.btnPhoto).setOnClickListener { checkMediaThenGallery() }
        findViewById<View>(R.id.btnShot).setOnClickListener {
            Toast.makeText(this, "App 内截屏为后续版本能力，请用系统截图后从相册发送", Toast.LENGTH_SHORT).show() // F-12 P2
        }
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
        findViewById<View>(R.id.btnShareBatch).setOnClickListener { batchShare() }
        findViewById<View>(R.id.btnSaveBatch).setOnClickListener { batchSave() }
        findViewById<View>(R.id.btnDeleteBatch).setOnClickListener { batchDelete() }

        BridgeForegroundService.start(this)

        // 崩溃诊断：上次异常退出/记录的堆栈弹窗展示，支持一键复制回传
        CrashLogger.latest(this)?.let { showCrashReport(it) }
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

    /** 相册图片发送：非原图按设置压缩（F-06 AC4），视频/原图直传（F-20 AC1） */
    private fun sendImageFile(src: java.io.File, original: Boolean) {
        val settings = com.lanbridge.settings.SettingsRepository.get(this)
        if (original || settings.compressMaxLongSide <= 0) {
            TransferEngine.publishFile(src, src.name, "image/jpeg", "image", true)
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val out = com.lanbridge.media.ImageCompressor.compress(
                src, settings.compressMaxLongSide, settings.compressQuality,
                java.io.File(cacheDir, "lanbridge_out"))
            val f = out ?: src
            TransferEngine.publishFile(f, f.name, "image/jpeg", "image", false)
        }
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
        findViewById<View>(R.id.btnShareBatch).isEnabled = enabled
        findViewById<View>(R.id.btnSaveBatch).isEnabled = enabled
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
                    if (f.exists() && SaveToDownloads.save(this@ChatActivity, f, m.fileRef!!.mime)) ok++
                }
            }
            runOnUiThread { Toast.makeText(this@ChatActivity, getString(R.string.save_ok) + " ($ok)", Toast.LENGTH_SHORT).show() }
        }
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
            val ok = SaveToDownloads.save(this@ChatActivity, f, ref.mime)
            runOnUiThread {
                Toast.makeText(this@ChatActivity,
                    if (ok) R.string.save_ok else R.string.send_failed, Toast.LENGTH_SHORT).show()
            }
        }
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
