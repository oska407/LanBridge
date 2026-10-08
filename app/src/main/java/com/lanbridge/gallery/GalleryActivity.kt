package com.lanbridge.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
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
        const val RESULT_ORIGINAL = "result_original"
    }

    private lateinit var repo: GalleryRepository
    private lateinit var store: SelectionStore
    private lateinit var adapter: GalleryAdapter
    private lateinit var dragSelect: DragSelectHelper
    private lateinit var lm: LockableGridLM
    private lateinit var tvTotal: TextView
    private lateinit var btnSend: TextView
    private lateinit var titleBar: View
    private val buckets = mutableListOf<GalleryRepository.Bucket>()
    private var bucketIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        setContentView(R.layout.activity_gallery)

        titleBar = findViewById(R.id.titleBar)
        tvTotal = findViewById(R.id.tvTotal)
        btnSend = findViewById(R.id.btnSend)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.titleBar)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            titleBar.updatePadding(top = bars.top + 8)
            WindowInsetsCompat.CONSUMED
        }

        store = SelectionStore(100)
        store.onLimitReached = { Toast.makeText(this, R.string.gallery_limit_reached, Toast.LENGTH_SHORT).show() } // AC6.4
        repo = GalleryRepository(this)
        adapter = GalleryAdapter(store)
        adapter.onSelectionChanged = { n ->
            btnSend.text = getString(R.string.gallery_send_n, n)
            btnSend.isEnabled = n > 0
        }
        adapter.onSingleClick = { pos -> toggleSingle(pos) } // 单击切换（AC6.3）

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        lm = LockableGridLM(this, 4)
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

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<CheckBox>(R.id.cbOriginal).isChecked = SettingsRepository.get(this).sendOriginal
        btnSend.setOnClickListener { send() }
        findViewById<View>(R.id.tvHint).setOnLongClickListener {
            showBucketPicker(); true // 相册下拉：长按提示条切换（简化入口）
        }
        tvTotal.setOnClickListener { showBucketPicker() }

        if (!ensurePermission()) return
        loadBuckets()
    }

    private fun ensurePermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        return if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            requestPermissions(arrayOf(perm), 1); false
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadBuckets()
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

    private fun send() {
        if (store.size() == 0) return
        val files = ArrayList<String>()
        // 发送顺序 = 勾选顺序（orderedIds）；映射回本地路径
        val byId = HashMap<Long, GalleryRepository.MediaItem>()
        for (i in 0 until adapter.itemCount) {
            val it0 = adapter.itemAt(i); byId[it0.id] = it0
        }
        store.orderedIds.forEach { id ->
            byId[id]?.let { item ->
                runCatching {
                    // 拷贝到应用缓存（零原图解码，流式拷贝）
                    val out = java.io.File(cacheDir, "lanbridge_out/${System.currentTimeMillis()}_${id}")
                    out.parentFile?.mkdirs()
                    contentResolver.openInputStream(item.uri)?.use { ins ->
                        out.outputStream().use { ins.copyTo(it) }
                    }
                    files.add(out.absolutePath)
                }
            }
        }
        if (files.isEmpty()) return
        intent.putExtra(RESULT_FILES, files)
        intent.putExtra(RESULT_ORIGINAL, findViewById<CheckBox>(R.id.cbOriginal).isChecked)
        setResult(RESULT_OK, intent)
        finish()
    }
}
