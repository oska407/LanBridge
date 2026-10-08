package com.lanbridge.model

/** 文件元数据（WS file_meta 与 HTTP 端点共用，ARCHITECTURE.md §4.1） */
data class FileMeta(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String? = null,
    val chunkSize: Int = 1_048_576,
    val totalChunks: Int,
    val isOriginal: Boolean = false,
    val kind: String = "file",   // image | file | video
    var localPath: String? = null // 手机端本地缓存路径（自动落盘后可分享/保存）
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id); put("name", name); put("mime", mime)
        put("size", size); put("chunkSize", chunkSize); put("totalChunks", totalChunks)
        put("isOriginal", isOriginal); put("kind", kind)
        sha256?.let { put("sha256", it) }
    }

    companion object {
        fun fromJson(o: org.json.JSONObject): FileMeta = FileMeta(
            id = o.getString("id"),
            name = o.getString("name"),
            mime = o.optString("mime", "application/octet-stream"),
            size = o.optLong("size", 0L),
            sha256 = o.optString("sha256", null),
            chunkSize = o.optInt("chunkSize", 1_048_576),
            totalChunks = o.optInt("totalChunks", 1),
            isOriginal = o.optBoolean("isOriginal", false),
            kind = o.optString("kind", "file")
        )
    }
}
