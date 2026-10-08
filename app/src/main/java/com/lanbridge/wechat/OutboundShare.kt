package com.lanbridge.wechat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 出站分享到微信（T20/F-03）：FileProvider 暴露 content://（仅 lanbridge_out 白名单），
 * ACTION_SEND + EXTRA_STREAM + createChooser。多文件降级由调用方按 F-03 AC4 逐个调起。
 */
object OutboundShare {

    fun shareFile(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "com.lanbridge.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }

    fun shareText(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 多选全为图片：ACTION_SEND_MULTIPLE 一次多发（F-03 AC4） */
    fun shareImages(ctx: Context, files: List<File>, mime: String = "image/*") {
        val uris = ArrayList(files.map {
            FileProvider.getUriForFile(ctx, "com.lanbridge.fileprovider", it)
        })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
