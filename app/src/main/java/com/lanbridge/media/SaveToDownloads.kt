package com.lanbridge.media

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 保存到本地（T12/T12B）：图片 → Pictures/LanBridge（相册可见）；其它 → Download/LanBridge。
 * 同名自动加后缀 (1)(2)…，不覆盖（F-20 AC7）；批量由调用方走后台协程 + 进度防 ANR。
 */
object SaveToDownloads {

    fun save(context: Context, src: File, mime: String): Boolean {
        val isImage = mime.startsWith("image/")
        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                         else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val dir = if (isImage) Environment.DIRECTORY_PICTURES + "/LanBridge"
                  else Environment.DIRECTORY_DOWNLOADS + "/LanBridge"
        val baseName = src.name.substringBeforeLast('.', src.name)
        val ext = src.name.substringAfterLast('.', "")

        val resolver = context.contentResolver
        // 同名后缀处理（F-20 AC7）
        var candidate = src.name
        var idx = 1
        while (exists(resolver, collection, dir, candidate)) {
            candidate = "$baseName ($idx)" + if (ext.isNotEmpty()) ".$ext" else ""
            idx++
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, candidate)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        }
        val uri = resolver.insert(collection, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { os -> src.inputStream().use { it.copyTo(os) } } != null
        }.getOrDefault(false)
    }

    private fun exists(resolver: android.content.ContentResolver, collection: android.net.Uri,
                       dir: String, name: String): Boolean {
        val proj = arrayOf(MediaStore.MediaColumns._ID)
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        resolver.query(collection, proj, sel, arrayOf("$dir/", name), null)?.use {
            return it.count > 0
        }
        return false
    }
}
