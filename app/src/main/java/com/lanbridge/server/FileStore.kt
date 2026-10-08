package com.lanbridge.server

import java.io.File
import java.io.RandomAccessFile

/**
 * 临时文件存储（T07）：分块写入 / 组装 / 流式读取 / Range 断点续传 / 清理。
 * 全部落 cacheDir/lanbridge_tmp，服务销毁时 clearAll()，磁盘 0 残留（F-20 / T28）。
 */
class FileStore(private val baseDir: File) {

    init { baseDir.mkdirs() }

    private fun fileOf(id: String) = File(baseDir, "$id.part")

    val dir: File get() = baseDir

    fun createTemp(id: String, totalSize: Long) {
        fileOf(id).apply { if (!exists()) RandomAccessFile(this, "rw").use { it.setLength(totalSize) } }
    }

    /** 写入指定块（1MB/块，偏移 = index * chunkSize） */
    fun writeChunk(id: String, index: Int, chunkSize: Int, data: ByteArray, offsetInChunk: Int = 0) {
        RandomAccessFile(fileOf(id), "rw").use { raf ->
            raf.seek(index.toLong() * chunkSize + offsetInChunk)
            raf.write(data, 0, data.size)
        }
    }

    fun assemble(id: String): File = fileOf(id).apply { if (!exists()) error("file not found: $id") }

    fun sizeOf(id: String): Long = fileOf(id).takeIf { it.exists() }?.length() ?: 0L

    /** 已收字节数（断点续传 offset，F-19 AC1；临时分块保留 5 分钟由上层管理） */
    fun receivedBytes(id: String): Long = sizeOf(id)

    fun openStream(id: String, start: Long = 0L): RandomAccessFile =
        RandomAccessFile(fileOf(id), "r").apply { seek(start) }

    fun deleteTemp(id: String) { fileOf(id).delete() }

    fun clearAll() { baseDir.listFiles()?.forEach { it.delete() } }

    fun dirSizeBytes(): Long = baseDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        fun dirOf(ctx: android.content.Context) =
            File(ctx.cacheDir, "lanbridge_tmp").apply { mkdirs() }
    }
}
