package com.lanbridge.util

import android.content.Context
import com.lanbridge.server.FileStore
import java.io.File

/**
 * 缓存水位守卫（v1.1.5）：lanbridge_tmp（接收分块）+ lanbridge_out（发送前拷贝）两处
 * 合计超过设定水位时，按「最旧优先」删除，避免连发大文件把磁盘撑爆。
 * 默认 200MB（SettingsRepository.cacheLimitMb），0 = 不限制。
 */
object CacheGuard {

    fun dirs(ctx: Context): List<File> =
        listOf(FileStore.dirOf(ctx), File(ctx.cacheDir, "lanbridge_out"))

    fun usageBytes(ctx: Context): Long =
        dirs(ctx).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** protected = 正在传输中的文件绝对路径，清理时跳过（防止把自己的大文件删了） */
    fun trim(ctx: Context, limitMb: Int, protected: Set<String> = emptySet()) {
        if (limitMb <= 0) return
        val limit = limitMb * 1024L * 1024L
        val files = dirs(ctx).flatMap { d -> d.listFiles()?.filter { it.isFile } ?: emptyList() }
            .filter { it.absolutePath !in protected }
        var total = usageBytes(ctx)
        if (total <= limit) return
        val before = total
        for (f in files.sortedBy { it.lastModified() }) { // 最旧优先
            if (total <= limit) break
            val len = f.length()
            if (f.delete()) total -= len
        }
        CrashLogger.log("cache",
            "缓存 ${before / 1048576}MB 超水位 ${limitMb}MB，已清理至 ${total / 1048576}MB")
    }

    fun clearAll(ctx: Context) {
        dirs(ctx).forEach { d -> d.listFiles()?.forEach { it.delete() } }
    }
}
