package com.lanbridge.util

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 轻量打zip工具：把若干「本地文件 + 目标名」压成单个 zip。
 * 用于「打包发送」——所选压缩/原图照片合并为一个 zip 传输（需求④）。
 */
object ZipUtil {

    /** @return 成功且产物非空时 true。entries: Pair<本地文件, zip内条目名> */
    fun zip(entries: List<Pair<File, String>>, out: File): Boolean {
        if (entries.isEmpty()) return false
        runCatching {
            out.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(out)).use { zos ->
                val used = LinkedHashSet<String>()
                for ((file, rawName) in entries) {
                    if (!file.exists() || !file.isFile) continue
                    val entryName = uniqueName(rawName.ifBlank { file.name }, used)
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }.onFailure { return false }
        return out.exists() && out.length() > 0
    }

    /** 同名条目自动加 (1)/(2) 后缀，避免互相覆盖 */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        var candidate: String
        do { candidate = "$base(${i++})$ext" } while (!used.add(candidate))
        return candidate
    }
}
