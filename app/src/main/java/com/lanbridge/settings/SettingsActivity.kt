package com.lanbridge.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
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
        val ip = HotspotIp.displayIp()
        val url = "http://$ip:${repo.serverPort}"
        // 没有可用局域网地址时不要把 0.0.0.0 当地址展示，给出可操作提示
        val ipNote = if (ip == HotspotIp.BIND_ALL) "未检测到局域网地址，请先开启手机热点或连接 Wi-Fi" else url

        rows.add(Row.Group(getString(R.string.settings_group_connection)))
        rows.add(Row.Item(getString(R.string.settings_group_connection), getString(R.string.settings_title), ipNote,
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
        rows.add(Row.Item(getString(R.string.settings_group_transfer), "后台运行保活",
            "把本应用加入电池优化白名单：离开 App（在其它界面/切到后台）网页端也能下载聊天文件",
            Row.Kind.ACTION, onClick = { requestBatteryExemption() }))
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
        // 保存位置可在设置里改（v1.1.5 之前是硬编码只读，摆着误导）
        rows.add(Row.Item(getString(R.string.settings_group_chat), getString(R.string.settings_save_dir_image),
            "${repo.saveDirImage} · 点击选择", Row.Kind.ACTION, onClick = { editSaveDir(true) }))
        rows.add(Row.Item(getString(R.string.settings_group_chat), getString(R.string.settings_save_dir_file),
            "${repo.saveDirFile} · 点击选择", Row.Kind.ACTION, onClick = { editSaveDir(false) }))
        // 缓存水位：超出自动清最旧，默认 200MB
        val usage = "%.1f".format(com.lanbridge.util.CacheGuard.usageBytes(this) / 1048576f)
        rows.add(Row.Item(getString(R.string.settings_group_chat), getString(R.string.settings_cache_limit),
            "当前 ${usage}MB · 上限 ${repo.cacheLimitLabel()} · 超出自动清最旧",
            Row.Kind.CHOICE, listOf("100MB", "200MB", "500MB", "1GB", "不限制"),
            onClick = { pickCacheLimit() }))
        rows.add(Row.Item(getString(R.string.settings_group_chat), getString(R.string.settings_clear_cache),
            "清空传输临时文件 · 立即生效", Row.Kind.DANGER, onClick = { clearCache() }))

        rows.add(Row.Group(getString(R.string.settings_group_about)))
        // 版本号必须真实：之前硬编码 1.0.0，导致无法判断手机上装的是哪个构建
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrDefault("未知")
        rows.add(Row.Item(getString(R.string.settings_group_about), getString(R.string.settings_version),
            "$ver · by 407"))
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
                        val ip = HotspotIp.displayIp()
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

    /**
     * 后台运行保活（需求③）：引导把本应用加入电池优化白名单。
     * 前台服务在 App 切到后台后可能被系统（尤其小米/华为/OPPO/VIVO 等）杀掉，
     * 导致网页端无法下载聊天文件。加入白名单后，只要 App 没被主动关闭，后台也能下载。
     */
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) {
            Toast.makeText(this, "此系统无需设置", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在电池优化白名单，后台下载不受限", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // 直接弹出本应用的电池优化豁免对话框（需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
            Toast.makeText(this, "请在弹窗中选择「允许」，以保证后台下载", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // 部分系统无该深度接口，回退到电池优化总设置页，由用户手动找到本应用
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                Toast.makeText(this, "请在列表中找到 LanBridge 并设为「未优化」", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开电池设置，请手动设置", Toast.LENGTH_SHORT).show()
            }
        }
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

    /**
     * 保存位置（v1.1.9）：目录树浏览窗口（需求⑦）。
     *
     * 为什么不能直接用系统 SAF 文件选择器：本项存的是 MediaStore 的 RELATIVE_PATH
     * （相对公共存储根目录），不是绝对文件路径，ACTION_OPEN_DOCUMENT_TREE 返回的
     * content:// 树 URI 与之不可互换，接进来会导致保存失败。
     * 而 RELATIVE_PATH 的顶级目录受系统限制（Android 10+）：
     *   图片 → Pictures / DCIM；其它文件 → Download / Documents。
     * 所以浏览窗口从合法顶级目录起步、在其内逐级下钻；子目录列表 = File API 与
     * MediaStore RELATIVE_PATH 枚举的并集（设置页不强依赖媒体权限：无权限或目录为空时
     * 仍可通过「手动输入…」填写，手动输入分支做同样的顶级目录校验）。
     */
    private fun editSaveDir(isImage: Boolean) {
        browseSaveDir(isImage, "")
    }

    private fun browseSaveDir(isImage: Boolean, rel: String) {
        val tops = if (isImage) listOf("Pictures", "DCIM") else listOf("Download", "Documents")
        val entries: List<String> = if (rel.isEmpty()) tops else listSubDirs(isImage, rel)

        val pathLabel = TextView(this).apply {
            text = if (rel.isEmpty()) "请选择顶级目录（系统限制）" else "/$rel"
            setPadding(52, 36, 52, 12)
            textSize = 14f
            setTextColor(getColor(R.color.n_text_secondary))
        }
        val rows = mutableListOf<String>()
        if (rel.isNotEmpty()) rows.add("↩ 返回上一级")
        if (rel.isNotEmpty() && entries.isEmpty()) rows.add("（没有子文件夹）")
        rows.addAll(entries)
        val list = android.widget.ListView(this).apply {
            adapter = android.widget.ArrayAdapter(this@SettingsActivity,
                android.R.layout.simple_list_item_1, rows)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(pathLabel)
            addView(list)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isImage) R.string.settings_save_dir_image else R.string.settings_save_dir_file)
            .setView(container)
            .setPositiveButton("选择此目录") { _, _ ->
                if (isImage) repo.saveDirImage = rel else repo.saveDirFile = rel
                Toast.makeText(this, "已保存：$rel", Toast.LENGTH_SHORT).show()
                buildRows()
            }
            .setNegativeButton("手动输入…") { _, _ -> editSaveDirManual(isImage) }
            .setNeutralButton(android.R.string.cancel, null)
            .create()
        // 根层级不能直接「选择此目录」（尚未落在任何合法顶级目录内）
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = rel.isNotEmpty()
        }
        dialog.show()
        list.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            when {
                rel.isNotEmpty() && pos == 0 -> browseSaveDir(isImage, rel.substringBeforeLast('/', ""))
                rel.isNotEmpty() && rows[pos].startsWith("（") -> Unit // 空目录占位行
                else -> browseSaveDir(isImage, "$rel/${rows[pos]}")
            }
        }
    }

    /** 枚举 rel 下的子目录：File API ∪ MediaStore RELATIVE_PATH（任一失败不影响另一方） */
    private fun listSubDirs(isImage: Boolean, rel: String): List<String> {
        val dirs = sortedSetOf<String>()
        runCatching {
            val base = java.io.File(Environment.getExternalStorageDirectory(), rel)
            base.listFiles { f -> f.isDirectory }?.forEach { dirs.add(it.name) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val uri = if (isImage) android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                          else android.provider.MediaStore.Files.getContentUri("external")
                val prefix = "$rel/"
                contentResolver.query(uri,
                    arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH),
                    null, null, null)?.use { c ->
                    val i = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val p = c.getString(i) ?: continue
                        if (!p.startsWith(prefix)) continue
                        val next = p.removePrefix(prefix).substringBefore('/').trim()
                        if (next.isNotBlank()) dirs.add(next)
                    }
                }
            }
        }
        return dirs.toList()
    }

    private fun saveDirPresets(isImage: Boolean) = if (isImage)
        listOf("Pictures/LanBridge", "Pictures", "DCIM/LanBridge", "DCIM")
    else
        listOf("Download/LanBridge", "Download", "Documents/LanBridge", "Documents")

    /** 手动输入分支：顶级目录校验 + 绝对路径 / .. 拦截，校验不通过给出具体原因 */
    private fun editSaveDirManual(isImage: Boolean) {
        val cur = if (isImage) repo.saveDirImage else repo.saveDirFile
        val input = android.widget.EditText(this).apply {
            setText(cur); setSingleLine(true); setSelection(cur.length)
        }
        val tops = if (isImage) listOf("Pictures", "DCIM") else listOf("Download", "Documents")
        AlertDialog.Builder(this)
            .setTitle(if (isImage) R.string.settings_save_dir_image else R.string.settings_save_dir_file)
            .setMessage("相对存储根目录，如 ${saveDirPresets(isImage).first()}；顶级目录只能是 ${tops.joinToString(" 或 ")}")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text.toString().trim().trim('/')
                val top = v.substringBefore('/')
                val bad = when {
                    v.isEmpty() -> "路径不能为空"
                    v.startsWith("/") -> "请填相对路径，不要以 / 开头"
                    v.contains("..") -> "路径不能包含 .."
                    top !in tops -> "顶级目录只能是 ${tops.joinToString(" 或 ")}（当前：$top）"
                    else -> null
                }
                if (bad != null) {
                    Toast.makeText(this, bad, Toast.LENGTH_SHORT).show()
                } else {
                    if (isImage) repo.saveDirImage = v else repo.saveDirFile = v
                    Toast.makeText(this, "已保存：$v", Toast.LENGTH_SHORT).show()
                    buildRows()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 缓存水位：默认 200MB（发大文件时按最旧优先清理） */
    private fun pickCacheLimit() {
        val opts = listOf(100, 200, 500, 1024, 0)
        val labels = listOf("100MB", "200MB（默认）", "500MB", "1GB", "不限制")
        AlertDialog.Builder(this).setTitle(R.string.settings_cache_limit)
            .setItems(labels.toTypedArray()) { _, which ->
                repo.cacheLimitMb = opts[which]
                com.lanbridge.util.CacheGuard.trim(this, repo.cacheLimitMb)
                buildRows()
            }.show()
    }

    private fun clearCache() {
        val size = com.lanbridge.util.CacheGuard.usageBytes(this)
        AlertDialog.Builder(this).setTitle(R.string.settings_clear_cache)
            .setMessage("将清空约 ${"%.1f".format(size / 1048576f)} MB 临时文件")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                com.lanbridge.util.CacheGuard.clearAll(this) // 两处：lanbridge_tmp + lanbridge_out
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                buildRows()
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
