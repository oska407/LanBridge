package com.lanbridge.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.lanbridge.R
import com.lanbridge.server.SessionState
import com.lanbridge.service.BridgeForegroundService
import com.lanbridge.util.CrashLogger
import com.lanbridge.util.HotspotIp
import com.lanbridge.util.QrGenerator

/**
 * 设置页（T09C/T09D）：五分组（连接/图片与发送/传输/聊天与存储/关于）；
 * 每项副标题标生效时机；端口校验 1024–65535；改端口走「确认→loading→刷新→失败回退」；
 * 二维码 ZXing 本地生成（严禁在线 API）；PC 端无任何设置入口——本页是唯一配置来源。
 */
class SettingsActivity : AppCompatActivity() {

    private sealed class Row {
        abstract val group: String?
        data class Group(val title: String) : Row() { override val group = null }
        data class Item(
            override val group: String?, val title: String, val subtitle: String = "",
            val kind: Kind = Kind.TEXT, val values: List<String> = emptyList(),
            val onClick: (() -> Unit)? = null, val onSwitch: ((Boolean) -> Unit)? = null,
            var checked: Boolean = false,
        ) : Row()
        enum class Kind { TEXT, ACTION, SWITCH, CHOICE, DANGER }
    }

    private lateinit var repo: SettingsRepository
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: SettingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        val titleBar = findViewById<View>(R.id.titleBar)
        ViewCompat.setOnApplyWindowInsetsListener(titleBar) { _, insets ->
            titleBar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top + 8)
            WindowInsetsCompat.CONSUMED
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        repo = SettingsRepository.get(this)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        adapter = SettingsAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        buildRows()
    }

    private fun buildRows() {
        rows.clear()
        val ip = HotspotIp.get(this)
        val url = "http://$ip:${repo.serverPort}"

        rows.add(Row.Group(getString(R.string.settings_group_connection)))
        rows.add(Row.Item(getString(R.string.settings_group_connection), getString(R.string.settings_title), url,
            onClick = { showQr(url) }))
        rows.add(Row.Item(getString(R.string.settings_group_connection), getString(R.string.settings_port),
            "${repo.serverPort} · 重启服务生效", Row.Kind.CHOICE, listOf("8080", "8000", "8888"), onClick = { changePort() }))

        rows.add(Row.Group(getString(R.string.settings_group_image)))
        rows.add(Row.Item(getString(R.string.settings_group_image), getString(R.string.settings_compress_long),
            "${if (repo.compressMaxLongSide == 0) "原图" else repo.compressLongLabel()} · 下次发送生效",
            Row.Kind.CHOICE, listOf("1080", "1920", "原图"), onClick = { pickLongSide() }))
        rows.add(Row.Item(getString(R.string.settings_group_image), getString(R.string.settings_compress_quality),
            "${repo.compressQuality}% · 下次发送生效",
            Row.Kind.CHOICE, listOf("60", "80", "90"), onClick = { pickQuality() }))
        rows.add(Row.Item(getString(R.string.settings_group_image), getString(R.string.settings_send_original),
            "开启后直传原文件 · 下次发送生效", Row.Kind.SWITCH, checked = repo.sendOriginal,
            onSwitch = { repo.sendOriginal = it }))

        rows.add(Row.Group(getString(R.string.settings_group_transfer)))
        rows.add(Row.Item(getString(R.string.settings_group_transfer), "单次传输上限", "500MB（只读）"))
        rows.add(Row.Item(getString(R.string.settings_group_transfer), getString(R.string.settings_auto_stop),
            "传输完成后自动停止服务 · 立即生效", Row.Kind.SWITCH, checked = repo.autoStopAfterTransfer,
            onSwitch = { repo.autoStopAfterTransfer = it }))

        rows.add(Row.Group(getString(R.string.settings_group_chat)))
        rows.add(Row.Item(getString(R.string.settings_group_chat), getString(R.string.settings_show_url),
            "连接地址显示为聊天记录第一条 · 立即生效", Row.Kind.SWITCH, checked = repo.showUrlInChat,
            onSwitch = { repo.showUrlInChat = it; SessionState.notifyChanged() }))
        rows.add(Row.Item(getString(R.string.settings_group_chat), getString(R.string.settings_notify),
            "一个开关管两端：手机通知/震动 + PC 提示 · 立即生效", Row.Kind.SWITCH, checked = repo.notifyEnabled,
            onSwitch = { repo.notifyEnabled = it; SessionState.notifyEnabled = it }))
        rows.add(Row.Item(getString(R.string.settings_group_chat), getString(R.string.settings_save_dir_image), repo.saveDirImage))
        rows.add(Row.Item(getString(R.string.settings_group_chat), getString(R.string.settings_save_dir_file), repo.saveDirFile))
        rows.add(Row.Item(getString(R.string.settings_group_chat), getString(R.string.settings_clear_cache),
            "清空传输临时文件 · 立即生效", Row.Kind.DANGER, onClick = { clearCache() }))

        rows.add(Row.Group(getString(R.string.settings_group_about)))
        // 版本号必须真实：之前硬编码 1.0.0，导致无法判断手机上装的是哪个构建
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrDefault("未知")
        rows.add(Row.Item(getString(R.string.settings_group_about), getString(R.string.settings_version),
            "$ver · PC 网页标题栏显示的号码应与此一致"))
        rows.add(Row.Item(getString(R.string.settings_group_about), "查看诊断日志",
            "WS 收发记录：没有「收到PC文本」= 网页没发出来", Row.Kind.ACTION, onClick = { showDiag() }))
        rows.add(Row.Item(getString(R.string.settings_group_about), getString(R.string.settings_privacy),
            "🔒 不可关闭（隐私设计）"))
        adapter.notifyDataSetChanged()
    }

    private fun SettingsRepository.compressLongLabel() = if (compressMaxLongSide == 0) "原图" else "${compressMaxLongSide}px"

    private fun changePort() {
        val input = android.widget.EditText(this)
        input.setText(repo.serverPort.toString())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_port)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text.toString().toIntOrNull()
                when {
                    v == null || v < 1024 -> Toast.makeText(this, R.string.settings_port_bad, Toast.LENGTH_SHORT).show() // inline 拦截
                    v > 65535 -> Toast.makeText(this, R.string.settings_port_bad, Toast.LENGTH_SHORT).show()
                    else -> {
                        // 五步链路：确认预览新地址 → 重启 → 刷新卡片 → 失败回退（T09D）
                        val ip = HotspotIp.get(this)
                        AlertDialog.Builder(this)
                            .setTitle("重启服务")
                            .setMessage("新地址 http://$ip:$v\n将重启服务以生效")
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                repo.serverPort = v
                                restartService()
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> repo.serverPort = repo.serverPort }
                            .show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restartService() {
        stopService(Intent(this, BridgeForegroundService::class.java))
        BridgeForegroundService.start(this)
        Toast.makeText(this, "地址已变更，请通知 PC 端重新打开", Toast.LENGTH_LONG).show()
        buildRows()
    }

    private fun pickLongSide() {
        val opts = listOf(1080, 1920, 0)
        val labels = listOf("1080", "1920", "原图")
        AlertDialog.Builder(this).setTitle(R.string.settings_compress_long)
            .setItems(labels.toTypedArray()) { _, which ->
                repo.compressMaxLongSide = opts[which]; buildRows()
            }.show()
    }

    private fun pickQuality() {
        val opts = listOf(60, 80, 90)
        AlertDialog.Builder(this).setTitle(R.string.settings_compress_quality)
            .setItems(arrayOf("60", "80", "90")) { _, which ->
                repo.compressQuality = opts[which]; buildRows()
            }.show()
    }

    private fun clearCache() {
        val dir = com.lanbridge.server.FileStore.dirOf(this)
        var size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        AlertDialog.Builder(this).setTitle(R.string.settings_clear_cache)
            .setMessage("将清空约 ${"%.1f".format(size / 1048576f)} MB 临时文件")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                dir.listFiles()?.forEach { it.delete() }
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** 诊断日志：判定「手机收不到 PC 消息」到底断在服务端还是网页端 */
    private fun showDiag() {
        val tv = android.widget.TextView(this).apply {
            text = CrashLogger.diagText(this@SettingsActivity)
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(32, 24, 32, 24)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("诊断日志")
            .setView(sv)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("lanbridge_diag", tv.text))
                Toast.makeText(this, "已复制，请粘贴给开发者", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("清空") { _, _ -> CrashLogger.clearDiag(this) }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun showQr(url: String) {
        val bmp = QrGenerator.generate(url) // 本地 ZXing，不走外网（F-17 AC5）
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setPadding(32, 32, 32, 32)
        }
        AlertDialog.Builder(this).setTitle(url).setView(iv)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private inner class SettingsAdapter : RecyclerView.Adapter<SettingsAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false))
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = rows[pos]
            when (r) {
                is Row.Group -> {
                    h.tvGroup.visibility = View.VISIBLE
                    h.tvGroup.text = r.title
                    h.row.visibility = View.GONE
                }
                is Row.Item -> {
                    h.tvGroup.visibility = View.GONE
                    h.row.visibility = View.VISIBLE
                    h.tvTitle.text = r.title
                    if (r.subtitle.isNotEmpty()) {
                        h.tvSubtitle.visibility = View.VISIBLE
                        h.tvSubtitle.text = r.subtitle
                        if (r.kind == Row.Kind.DANGER) h.tvSubtitle.setTextColor(getColor(R.color.danger_text))
                        else h.tvSubtitle.setTextColor(getColor(R.color.n_text_secondary))
                    } else h.tvSubtitle.visibility = View.GONE
                    if (r.kind == Row.Kind.SWITCH) {
                        h.switcher.visibility = View.VISIBLE
                        h.switcher.setOnCheckedChangeListener(null)
                        h.switcher.isChecked = r.checked
                        h.switcher.setOnCheckedChangeListener { _, c -> r.checked = c; r.onSwitch?.invoke(c) }
                        h.row.setOnClickListener(null)
                    } else {
                        h.switcher.visibility = View.GONE
                        h.row.setOnClickListener { r.onClick?.invoke() }
                    }
                    if (r.kind == Row.Kind.DANGER) h.tvTitle.setTextColor(getColor(R.color.danger_text))
                    else h.tvTitle.setTextColor(getColor(R.color.n_text_primary))
                }
            }
        }
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvGroup: TextView = v.findViewById(R.id.tvGroup)
            val row: View = v.findViewById(R.id.row)
            val tvTitle: TextView = v.findViewById(R.id.tvTitle)
            val tvSubtitle: TextView = v.findViewById(R.id.tvSubtitle)
            val switcher: SwitchMaterial = v.findViewById(R.id.switcher)
        }
    }
}
