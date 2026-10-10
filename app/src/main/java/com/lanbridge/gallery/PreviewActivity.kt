package com.lanbridge.gallery

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.lanbridge.R

/**
 * 已选照片预览（微信样式，v1.1.5）：
 * 大图横向翻页（RecyclerView+PagerSnapHelper）、顶部 i/N、右上角「选择」切换当前照片勾选态、
 * 底部缩略图条（点选跳转，当前项绿框）、原图勾选、发送(N)。
 * 与 GalleryActivity 通过 companion 静态共享 SelectionStore / itemById（同进程内部页，
 * 避免 ≤100 项的 Parcelable 大列表塞 Intent）；GalleryActivity 在 onResume 里刷新徽章。
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        const val RESULT_SEND = "preview_send"
        const val RESULT_ORIGINAL = "preview_original"
        var store: SelectionStore? = null
        var itemMap: Map<Long, GalleryRepository.MediaItem>? = null
        var startOriginal: Boolean = false
    }

    private lateinit var titleBar: View
    private lateinit var bottomBar: View
    private lateinit var pager: RecyclerView
    private lateinit var thumbs: RecyclerView
    private lateinit var tvIndex: TextView
    private lateinit var btnToggle: TextView
    private lateinit var btnSend: TextView
    private lateinit var ivOriginal: View
    private var originalOn = false
    private lateinit var pagerAdapter: PagerAdapter
    private lateinit var thumbAdapter: ThumbAdapter
    private val items = mutableListOf<GalleryRepository.MediaItem>()
    private val snap = PagerSnapHelper()
    private var currentPos = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_preview)

        titleBar = findViewById(R.id.titleBar)
        bottomBar = findViewById(R.id.bottomBar)
        pager = findViewById(R.id.pager)
        thumbs = findViewById(R.id.thumbs)
        tvIndex = findViewById(R.id.tvIndex)
        btnToggle = findViewById(R.id.btnToggle)
        btnSend = findViewById(R.id.btnSend)
        ivOriginal = findViewById(R.id.ivOriginal)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            titleBar.updatePadding(top = bars.top + 8)
            bottomBar.updatePadding(bottom = bars.bottom) // 导航栏弹出时栏体延伸到其下方
            WindowInsetsCompat.CONSUMED
        }

        val st = store
        val map = itemMap
        if (st == null || map == null || st.size() == 0) { finish(); return }
        items.addAll(st.orderedIds.mapNotNull { map[it] })
        if (items.isEmpty()) { finish(); return }
        setOriginal(startOriginal)

        pagerAdapter = PagerAdapter()
        pager.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        pager.adapter = pagerAdapter
        snap.attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) syncCurrent(snappedPos())
            }
        })

        thumbAdapter = ThumbAdapter()
        thumbs.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        thumbs.adapter = thumbAdapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnToggle.setOnClickListener { toggleCurrent() }
        findViewById<View>(R.id.layOriginal).setOnClickListener { setOriginal(!originalOn) }
        btnSend.setOnClickListener {
            setResult(RESULT_OK, Intent()
                .putExtra(RESULT_SEND, true)
                .putExtra(RESULT_ORIGINAL, originalOn))
            finish()
        }
        syncCurrent(0)
    }

    private fun snappedPos(): Int {
        val lm = pager.layoutManager ?: return currentPos
        val v = snap.findSnapView(lm) ?: return currentPos
        return pager.getChildAdapterPosition(v).coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    /** 同步当前页 UI：i/N、右上角选择态、缩略图高亮、发送计数 */
    private fun syncCurrent(pos: Int) {
        if (items.isEmpty()) return
        currentPos = pos.coerceIn(0, items.size - 1)
        tvIndex.text = getString(R.string.preview_index, currentPos + 1, items.size)
        val sel = store?.isSelected(items[currentPos].id) ?: false
        if (sel) {
            btnToggle.text = "✓ ${getString(R.string.preview_select)}"
            btnToggle.setTextColor(getColor(R.color.green_500))
        } else {
            btnToggle.text = getString(R.string.preview_select)
            btnToggle.setTextColor(getColor(R.color.n_white))
        }
        val n = store?.size() ?: 0
        btnSend.text = getString(R.string.gallery_send_n, n)
        btnSend.isEnabled = n > 0
        thumbAdapter.notifyDataSetChanged() // ≤100 项小列表，直接全刷
        (thumbs.layoutManager as? LinearLayoutManager)?.scrollToPosition(currentPos)
    }

    /** 原图空心圆状态切换（深色底：白描边空心圆 <-> 绿色实心圆） */
    private fun setOriginal(on: Boolean) {
        originalOn = on
        ivOriginal.setBackgroundResource(if (on) R.drawable.bg_circle_checked else R.drawable.bg_circle_unchecked)
    }

    private fun toggleCurrent() {
        val st = store ?: return
        if (items.isEmpty()) return
        val item = items[currentPos]
        if (st.isSelected(item.id)) {
            st.set(item.id, false)
            items.removeAt(currentPos)
            pagerAdapter.notifyItemRemoved(currentPos)
            thumbAdapter.notifyItemRemoved(currentPos)
            if (items.isEmpty()) { finish(); return }
            val newPos = currentPos.coerceAtMost(items.size - 1)
            pager.scrollToPosition(newPos)
            syncCurrent(newPos)
        } else {
            if (st.set(item.id, true)) {
                items.add(item) // 与 store.orderedIds 同序：追加到末尾
                pagerAdapter.notifyItemInserted(items.size - 1)
                thumbAdapter.notifyItemInserted(items.size - 1)
                syncCurrent(currentPos)
            } else {
                Toast.makeText(this, R.string.gallery_limit_reached, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) { store = null; itemMap = null } // 防静态引用泄漏
        super.onDestroy()
    }

    // ---- 大图翻页 ----
    inner class PagerAdapter : RecyclerView.Adapter<PagerVH>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagerVH =
            PagerVH(LayoutInflater.from(parent.context).inflate(R.layout.item_preview_pager, parent, false))
        override fun onBindViewHolder(h: PagerVH, position: Int) {
            val item = items[position]
            // 按屏幕尺寸解码，fitCenter 完整显示，避免超大原图 OOM
            val dm = h.iv.resources.displayMetrics
            Glide.with(h.iv).load(item.uri).fitCenter().override(dm.widthPixels, dm.heightPixels).into(h.iv)
            h.iv.setOnClickListener { toggleBarVisibility() }
        }
        /** 点大图显隐顶栏/底栏（微信同款，看图更沉浸） */
        private fun toggleBarVisibility() {
            val vis = if (titleBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            titleBar.visibility = vis
            bottomBar.visibility = vis
            thumbs.visibility = vis
        }
    }

    class PagerVH(v: View) : RecyclerView.ViewHolder(v) { val iv: ImageView = v.findViewById(R.id.ivPager) }

    // ---- 底部缩略图条 ----
    inner class ThumbAdapter : RecyclerView.Adapter<ThumbVH>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbVH =
            ThumbVH(LayoutInflater.from(parent.context).inflate(R.layout.item_preview_thumb, parent, false))
        override fun onBindViewHolder(h: ThumbVH, position: Int) {
            val item = items[position]
            ThumbLoader.load(h.iv, item.uri, item.isVideo)
            h.root.setBackgroundResource(
                if (position == currentPos) R.drawable.bg_thumb_border else android.R.color.transparent)
            h.itemView.setOnClickListener { pager.smoothScrollToPosition(position) }
        }
    }

    class ThumbVH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivThumb)
        val root: View = v.findViewById(R.id.thumbRoot)
    }
}
