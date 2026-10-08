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

    val saveDirImage: String get() = "Pictures/LanBridge"
    val saveDirFile: String get() = "Download/LanBridge"

    companion object {
        private const val KEY_PORT = "serverPort"
        private const val KEY_LONG_SIDE = "compressMaxLongSide"
        private const val KEY_QUALITY = "compressQuality"
        private const val KEY_ORIGINAL = "sendOriginal"
        private const val KEY_AUTO_STOP = "autoStopAfterTransfer"
        private const val KEY_SHOW_URL = "showUrlInChat"
        private const val KEY_NOTIFY = "notifyEnabled"

        @Volatile private var instance: SettingsRepository? = null
        fun get(ctx: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(ctx.applicationContext).also { instance = it }
            }
    }
}
