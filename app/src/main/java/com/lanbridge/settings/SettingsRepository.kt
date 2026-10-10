package com.lanbridge.settings

import android.content.Context

/**
 * 设置仓库（T09C）：SharedPreferences 落地，PC 端无设置入口——本页是唯一配置来源（v1.9）。
 * 无配对码相关项（F-11 已取消）。
 */
class SettingsRepository(context: Context) {

    private val sp = context.getSharedPreferences("lanbridge_settings", Context.MODE_PRIVATE)

    var serverPort: Int
        get() = sp.getInt(KEY_PORT, 8080)
        set(v) { sp.edit().putInt(KEY_PORT, v).apply() }

    // 压缩长边：1080 / 1920 / 0(原图)，默认 1920
    var compressMaxLongSide: Int
        get() = sp.getInt(KEY_LONG_SIDE, 1920)
        set(v) { sp.edit().putInt(KEY_LONG_SIDE, v).apply() }

    // 压缩质量：60 / 80 / 90，默认 80
    var compressQuality: Int
        get() = sp.getInt(KEY_QUALITY, 80)
        set(v) { sp.edit().putInt(KEY_QUALITY, v).apply() }

    // 发送原图：默认关 —— 不选原图即按压缩率发送（v1.6）
    var sendOriginal: Boolean
        get() = sp.getBoolean(KEY_ORIGINAL, false)
        set(v) { sp.edit().putBoolean(KEY_ORIGINAL, v).apply() }

    // 打包发送：默认关 —— 开则所选照片压成单个 zip 发送，与原图开关相互独立（v1.1.6）
    var sendPackage: Boolean
        get() = sp.getBoolean(KEY_PACKAGE, false)
        set(v) { sp.edit().putBoolean(KEY_PACKAGE, v).apply() }

    var autoStopAfterTransfer: Boolean
        get() = sp.getBoolean(KEY_AUTO_STOP, false)
        set(v) { sp.edit().putBoolean(KEY_AUTO_STOP, v).apply() }

    var showUrlInChat: Boolean
        get() = sp.getBoolean(KEY_SHOW_URL, true)
        set(v) { sp.edit().putBoolean(KEY_SHOW_URL, v).apply() }

    // 新消息提醒：一个开关管两端（F-19 AC2），默认开
    var notifyEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, true)
        set(v) { sp.edit().putBoolean(KEY_NOTIFY, v).apply() }

    // 保存位置（可编辑，v1.1.5）：MediaStore RELATIVE_PATH，形如 Pictures/LanBridge
    var saveDirImage: String
        get() = sp.getString(KEY_SAVE_DIR_IMAGE, DEF_SAVE_IMAGE) ?: DEF_SAVE_IMAGE
        set(v) { sp.edit().putString(KEY_SAVE_DIR_IMAGE, normalizeDir(v, DEF_SAVE_IMAGE)).apply() }

    var saveDirFile: String
        get() = sp.getString(KEY_SAVE_DIR_FILE, DEF_SAVE_FILE) ?: DEF_SAVE_FILE
        set(v) { sp.edit().putString(KEY_SAVE_DIR_FILE, normalizeDir(v, DEF_SAVE_FILE)).apply() }

    /** 缓存水位（MB）：0=不限制，默认 200。超出按最旧优先清理 lanbridge_tmp + lanbridge_out */
    var cacheLimitMb: Int
        get() = sp.getInt(KEY_CACHE_LIMIT, 200)
        set(v) { sp.edit().putInt(KEY_CACHE_LIMIT, v).apply() }

    fun cacheLimitLabel(): String = if (cacheLimitMb <= 0) "不限制" else "${cacheLimitMb}MB"

    private fun normalizeDir(v: String, def: String): String {
        val s = v.trim().replace("\\", "/").trim('/').replace(Regex("/+"), "/")
        if (s.isEmpty() || s.contains("..")) return def // 防越权/空值
        return s
    }

    companion object {
        private const val KEY_PORT = "serverPort"
        private const val KEY_LONG_SIDE = "compressMaxLongSide"
        private const val KEY_QUALITY = "compressQuality"
        private const val KEY_ORIGINAL = "sendOriginal"
        private const val KEY_PACKAGE = "sendPackage"
        private const val KEY_AUTO_STOP = "autoStopAfterTransfer"
        private const val KEY_SHOW_URL = "showUrlInChat"
        private const val KEY_NOTIFY = "notifyEnabled"
        private const val KEY_SAVE_DIR_IMAGE = "saveDirImage"
        private const val KEY_SAVE_DIR_FILE = "saveDirFile"
        private const val KEY_CACHE_LIMIT = "cacheLimitMb"
        private const val DEF_SAVE_IMAGE = "Pictures/LanBridge"
        private const val DEF_SAVE_FILE = "Download/LanBridge"

        @Volatile private var instance: SettingsRepository? = null
        fun get(ctx: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(ctx.applicationContext).also { instance = it }
            }
    }
}
