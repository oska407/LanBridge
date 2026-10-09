package com.lanbridge.util

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志采集（诊断版）：
 * - Application 启动时 install()，未捕获异常先落盘再交还系统默认处理器（系统崩溃弹窗照常出现）；
 * - 被 runCatching 吞掉的非致命异常也可用 logThrowable() 主动记录，便于下次启动回看；
 * - 日志写入 getExternalFilesDir(null)/lanbridge_crash.log（无需任何存储权限），
 *   文件管理器可直接取走：Android/data/com.lanbridge/files/lanbridge_crash.log。
 */
object CrashLogger {

    private const val SEP = "=====LANBRIDGE_CRASH====="
    private const val MAX_FILE_BYTES = 256 * 1024 // 超限截断保留尾部

    @Volatile private var appCtx: Context? = null

    fun install(app: Application) {
        appCtx = app.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { append(stackOf(e) + "\n[thread] ${t.name}") }
            prev?.uncaughtException(t, e)
        }
    }

    /** 记录被捕获但值得回看的异常（协程回调、静默失败路径等） */
    fun logThrowable(e: Throwable, tag: String = "caught") {
        runCatching { append("[$tag]\n${stackOf(e)}") }
    }

    /**
     * 诊断日志（独立文件 lanbridge_diag.log，不与崩溃日志混用，
     * 避免启动弹窗把诊断信息当"异常"误报）。超 256KB 自动清空重写。
     */
    fun log(tag: String, message: String) {
        runCatching {
            val ctx = appCtx ?: return
            val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "lanbridge_diag.log")
            if (f.length() > 256 * 1024) f.delete()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$ts [$tag] $message\n")
        }
    }

    /** 读诊断日志尾部（设置页「查看诊断日志」用，免拔手机取文件） */
    fun diagText(ctx: Context, maxBytes: Int = 8000): String {
        val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "lanbridge_diag.log")
        if (!f.exists()) return "（暂无诊断日志：还没有任何 WS 活动记录）"
        val text = runCatching { f.readText() }.getOrDefault("")
        return if (text.length <= maxBytes) text else "…（仅显示末尾）\n" + text.substring(text.length - maxBytes)
    }

    fun clearDiag(ctx: Context) {
        runCatching { File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "lanbridge_diag.log").delete() }
    }

    /** 读取最近一次记录（无则返回 null） */
    fun latest(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val idx = text.lastIndexOf(SEP)
        if (idx < 0) return null
        return text.substring(idx + SEP.length).trim().takeIf { it.isNotEmpty() }
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    @Synchronized
    private fun append(text: String) {
        val ctx = appCtx ?: return
        val f = file(ctx)
        var existing = if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
        if (existing.length > MAX_FILE_BYTES) {
            existing = existing.substring(existing.length - MAX_FILE_BYTES / 2)
        }
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        f.writeText(existing + SEP + "\n" + ts + "\n" + text + "\n")
    }

    private fun file(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, "lanbridge_crash.log")
    }

    private fun stackOf(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
