package com.lanbridge.media

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

    /**
     * 保存到本地（T12/T12B）：图片 → Pictures/LanBridge（相册可见）；其它 → Download/LanBridge。
     * displayName 缺省时用本地缓存文件名；同名自动加后缀 (1)(2)…，不覆盖（F-20 AC7）。
     * 传输名（原文件名）优先透传，保存时不改名（F-20 用户要求）。
     */
    object SaveToDownloads {

        fun save(context: Context, src: File, mime: String, displayName: String? = null): Boolean {
            val isImage = mime.startsWith("image/")
            val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                             else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val dir = if (isImage) Environment.DIRECTORY_PICTURES + "/LanBridge"
                      else Environment.DIRECTORY_DOWNLOADS + "/LanBridge"
            // 优先用传输原文件名；剔除非法字符，空则回退本地缓存名
            val raw = (displayName ?: src.name)
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifEmpty { src.name }
            val baseName = raw.substringBeforeLast('.', raw)
            val ext = raw.substringAfterLast('.', "")

            val resolver = context.contentResolver
            // 同名后缀处理（F-20 AC7）
            var candidate = raw
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
