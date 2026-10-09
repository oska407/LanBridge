package com.lanbridge.chat

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bumptech.glide.Glide
import com.lanbridge.R
import com.lanbridge.media.SaveToDownloads
import com.lanbridge.model.Message
import com.lanbridge.wechat.OutboundShare
import java.io.File

/**
 * 聊天图片大图查看（v1.1.5）：点气泡里的照片进这里，黑底 fitCenter 完整显示；
 * 点画面隐藏/显示顶栏底栏，底栏可直接分享到微信 / 保存到本地。
 * 只做查看，不改任何文件。
 */
class PhotoViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        /** 当前查看的消息（分享/保存用），与 path 一起由 ChatActivity 传入 */
        var current: Message? = null
    }

    private lateinit var titleBar: View
    private lateinit var bottomBar: View
    private var msg: Message? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_photo_view)

        titleBar = findViewById(R.id.titleBar)
        bottomBar = findViewById(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            titleBar.updatePadding(top = bars.top + 8)
            bottomBar.updatePadding(bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: File(path).name
        msg = current

        val iv = findViewById<android.widget.ImageView>(R.id.ivBig)
        val m = resources.displayMetrics
        Glide.with(iv).load(path).fitCenter().override(m.widthPixels, m.heightPixels).into(iv)
        findViewById<TextView>(R.id.tvName).text = name

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        iv.setOnClickListener { toggleBars() }

        findViewById<View>(R.id.btnSave).setOnClickListener {
            val m2 = msg
            if (m2?.fileRef == null) { Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val ok = SaveToDownloads.save(this@PhotoViewActivity, File(path), m2.fileRef!!.mime, m2.fileRef!!.name)
                runOnUiThread {
                    Toast.makeText(this@PhotoViewActivity,
                        if (ok) R.string.save_ok else R.string.send_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<View>(R.id.btnShare).setOnClickListener {
            val m3 = msg
            if (m3?.fileRef != null && File(path).exists()) {
                OutboundShare.shareFile(this, File(path), m3.fileRef!!.mime)
            } else {
                Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleBars() {
        val vis = if (titleBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        titleBar.visibility = vis
        bottomBar.visibility = vis
    }

    override fun onDestroy() {
        if (isFinishing) current = null
        super.onDestroy()
    }
}
