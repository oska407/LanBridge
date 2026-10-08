package com.lanbridge.wechat

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 微信入站 content:// 同步拷贝（T18/F-07）：
 * 临时授权窗口内立即拷贝到内部存储（cacheDir/lanbridge_tmp），不跨生命周期异步读原 URI（Q3）。
 */
object InboundCopier {

    fun copyUriToLocal(ctx: Context, uri: Uri, displayName: String): File? = runCatching {
        val dir = File(ctx.cacheDir, "lanbridge_tmp").apply { mkdirs() }
        val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "shared_file" }
        val out = File(dir, "${System.currentTimeMillis()}_$safe")
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { ins.copyTo(it) }
        } ?: return null
        out
    }.getOrNull()

    fun queryDisplayName(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "shared_file"
}
