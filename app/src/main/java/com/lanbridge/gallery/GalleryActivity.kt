package com.lanbridge.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.lanbridge.R
import com.lanbridge.settings.SettingsRepository

/**
 * 图片选择器（T14~T16A）：首屏网格、划选连续多选（≤100）、相册下拉、原图切换、发送(N) 联动。
 * 直发通道：选完即发（setResult 交回 ChatActivity 走压缩/直传），不进输入区（F-04 AC5）。
 */
class GalleryActivity : AppCompatActivity() {

    companion object {
        const val RESULT_FILES = "result_files"
        const val RESULT_NAMES = "result_names"
        const val RESULT_ORIGINAL = "result_original"
        const val RESULT_PACKAGE = "result_package"
    }

    private lateinit var repo: GalleryRepository
    private lateinit var store: SelectionStore
    private lateinit var adapter: GalleryAdapter
    private lateinit var dragSelect: DragSelectHelper
    private lateinit var lm: LockableGridLM
    private lateinit var tvTotal: TextView
    private lateinit var btnSend: TextView
    private lateinit var btnPreview: TextView
    private lateinit var titleBar: View
    private lateinit var ivOriginal: View
    private lateinit var ivPackage: View
    /** 原图开关（空心圆），持久化到设置 */
    private var originalOn = false
    /** 打包开关（空心圆），与原图相互独立；开则将所选压成单个 zip 发送 */
    private var packageOn = false
    private val buckets = mutableListOf<GalleryRepository.Bucket>()
    private var bucketIndex = 0
    /** 全部已加载过的媒体项（跨相册/跨分页），发送与预览按 id 取 */
    private val itemById = HashMap<Long, GalleryRepository.MediaItem>()

    private val previewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) {
                r.data?.let { d ->
                    setOriginal(d.getBooleanExtra(PreviewActivity.RESULT_ORIGINAL, false))
                    if (d.getBooleanExtra(PreviewActivity.RESULT_SEND, false)) send()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_gallery)

        titleBar = findViewById(R.id.titleBar)
        tvTotal = findViewById(R.id.tvTotal)
        btnSend = findViewById(R.id.btnSend)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            titleBar.updatePadding(top = bars.top + 8)
            bottomBar.updatePadding(bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        store = SelectionStore(100)
        store.onLimitReached = { Toast.makeText(this, R.string.gallery_limit_reached, Toast.LENGTH_SHORT).show() } // AC6.4
        repo = GalleryRepository(this)
        adapter = GalleryAdapter(store)
        adapter.onSelectionChanged = { refreshSendUi() }
        adapter.onSingleClick = { pos -> toggleSingle(pos) } // 单击切换（AC6.3）

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        lm = LockableGridLM(this, 4)
        recycler.setHasFixedSize(true)
        recycler.layoutManager = lm
        recycler.adapter = adapter
        dragSelect = DragSelectHelper(recycler, store)
        recycler.addOnItemTouchListener(dragSelect)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 游标分页：接近底部再加载下一页，绝不全量入内存
                val lm = rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 30) loadMore()
            }
        })

        btnPreview = findViewById(R.id.btnPreview)
        btnPreview.setOnClickListener {
            if (store.size() == 0) return@setOnClickListener
            PreviewActivity.store = store
            PreviewActivity.itemMap = itemById
            PreviewActivity.startOriginal = originalOn
            previewLauncher.launch(Intent(this, PreviewActivity::class.java))
        }

        // 原图 / 打包 空心圆开关（相互独立）
        ivOriginal = findViewById(R.id.ivOriginal)
        ivPackage = findViewById(R.id.ivPackage)
        findViewById<View>(R.id.layOriginal).setOnClickListener { setOriginal(!originalOn) }
        findViewById<View>(R.id.layPackage).setOnClickListener { setPackage(!packageOn) }
        originalOn = SettingsRepository.get(this).sendOriginal
        packageOn = SettingsRepository.get(this).sendPackage
        setOriginal(originalOn)
        setPackage(packageOn)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnSend.setOnClickListener { send() }
        refreshSendUi() // 初始「发送(0)→发送」，避免 XML 里 %1$d 字面量露出
        findViewById<View>(R.id.tvHint).setOnLongClickListener {
            showBucketPicker(); true // 相册下拉：长按提示条切换（简化入口）
        }
        tvTotal.setOnClickListener { showBucketPicker() }

        if (!ensurePermission()) return
        loadBuckets()
    }

    override fun onResume() {
        super.onResume()
        // 从预览页回来：勾选可能被右上角「选择」增删，徽章序号随之变化，局部刷新可见格
        refreshSendUi()
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        for (i in 0 until recycler.childCount) {
            val pos = recycler.getChildAdapterPosition(recycler.getChildAt(i))
            if (pos != RecyclerView.NO_POSITION) {
                adapter.notifyItemChanged(pos, GalleryAdapter.PAYLOAD_SELECTION)
            }
        }
    }

    /** 底栏状态：发送/预览按钮的计数文案与可用态（0 张显示「发送/预览」并半透明禁用） */
    private fun refreshSendUi() {
        val n = if (this::store.isInitialized) store.size() else 0
        btnSend.text = if (n > 0) getString(R.string.gallery_send_n, n) else getString(R.string.send)
        btnSend.isEnabled = n > 0
        btnSend.alpha = if (n > 0) 1f else 0.5f
        if (this::btnPreview.isInitialized) {
            btnPreview.text = getString(R.string.gallery_preview_n, n)
            btnPreview.isEnabled = n > 0
            btnPreview.alpha = if (n > 0) 1f else 0.5f
        }
    }

    private fun ensurePermission(): Boolean {
        // Android 13+ 细分媒体权限：只请求图片会导致视频查询结果为空
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) {
            true
        } else {
            requestPermissions(missing.toTypedArray(), 1); false
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) loadBuckets()
        else finish()
    }

    private fun loadBuckets() {
        buckets.clear()
        buckets.addAll(repo.queryBuckets())
        bucketIndex = 0
        tvTotal.text = "${buckets[0].name} (${buckets[0].count})"
        loadMore(reset = true)
    }

    private var pageOffset = 0
    private fun loadMore(reset: Boolean = false) {
        if (reset) pageOffset = 0
        val b = buckets.getOrNull(bucketIndex) ?: return
        val page = repo.queryPage(b.id, pageOffset, 200)
        if (page.isEmpty()) return
        page.forEach { itemById[it.id] = it } // 跨相册/分页累积，供预览与发送按 id 取
        adapter.submitPage(page, append = pageOffset > 0)
        pageOffset += page.size
    }

    private fun toggleSingle(pos: Int) {
        val id = adapter.itemAt(pos).id
        val target = !store.isSelected(id)
        store.set(id, target)
        adapter.notifyItemChanged(pos, GalleryAdapter.PAYLOAD_SELECTION)
        adapter.onSelectionChanged?.invoke(store.size())
    }

    private fun showBucketPicker() {
        val names = buckets.map { "${it.name} (${it.count})" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("相册")
            .setItems(names) { _, which ->
                bucketIndex = which
                tvTotal.text = names[which]
                loadMore(reset = true)
            }.show()
    }

    /** 由 MIME 推导扩展名：缓存文件名带扩展名，网页下载才不丢后缀（默认 jpg） */
    private fun extFromMime(mime: String): String = when {
        mime.equals("image/png", true) -> "png"
        mime.equals("image/webp", true) -> "webp"
        mime.equals("image/gif", true) -> "gif"
        mime.equals("image/heic", true) || mime.equals("image/heif", true) -> "heic"
        mime.startsWith("video/", true) -> when {
            mime.contains("3gp", true) -> "3gp"
            mime.contains("mkv", true) -> "mkv"
            mime.contains("webm", true) -> "webm"
            mime.contains("mov", true) -> "mov"
            else -> "mp4"
        }
        mime.startsWith("image/", true) -> "jpg"
        else -> "bin"
    }

    /** 原图空心圆状态切换（白描边空心圆 <-> 绿色实心圆） */
    private fun setOriginal(on: Boolean) {
        originalOn = on
        ivOriginal.setBackgroundResource(if (on) R.drawable.bg_toggle_on else R.drawable.bg_toggle_off)
        SettingsRepository.get(this).sendOriginal = on
    }

    /** 打包空心圆状态切换（与原图独立） */
    private fun setPackage(on: Boolean) {
        packageOn = on
        ivPackage.setBackgroundResource(if (on) R.drawable.bg_toggle_on else R.drawable.bg_toggle_off)
        SettingsRepository.get(this).sendPackage = on
    }

    private fun send() {
        if (store.size() == 0) return
        val files = ArrayList<String>()
        val names = ArrayList<String>()
        // 发送顺序 = 勾选顺序（orderedIds）；itemById 覆盖所有加载过的分页（含预览页里新勾的）
        store.orderedIds.forEach { id ->
            itemById[id]?.let { item ->
                runCatching {
                    // 拷贝到应用缓存（零原图解码，流式拷贝）；内部名随机即可，传输/保存一律用原文件名
                    val out = java.io.File(cacheDir, "lanbridge_out/${System.currentTimeMillis()}_${id}.${extFromMime(item.mime)}")
                    out.parentFile?.mkdirs()
                    contentResolver.openInputStream(item.uri)?.use { ins ->
                        out.outputStream().use { ins.copyTo(it) }
                    }
                    files.add(out.absolutePath)
                    // 原文件名（MediaStore DISPLAY_NAME）全程透传，传输与保存都不改名
                    val raw = item.name.ifBlank { "image_$id" }
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    names.add(if (raw.contains('.')) raw else "$raw.${extFromMime(item.mime)}")
                }
            }
        }
        if (files.isEmpty()) return
        intent.putExtra(RESULT_FILES, files)
        intent.putExtra(RESULT_NAMES, names)
        intent.putExtra(RESULT_ORIGINAL, originalOn)
        intent.putExtra(RESULT_PACKAGE, packageOn)
        setResult(RESULT_OK, intent)
        finish()
    }
}
