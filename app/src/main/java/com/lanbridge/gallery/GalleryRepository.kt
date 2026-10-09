package com.lanbridge.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/**
 * MediaStore 查询（T14）：按 BUCKET 分组 + 游标分页。
 * 分页统一走 MediaStore.Files 单查询（media_type IN (1,3)），图文全局按时间排序，
 * 翻页偏移不会因图片/视频分开计数而错位。
 * API 29+ 用 Bundle QUERY_ARG_LIMIT/OFFSET 标准分页；
 * API 28 旧 MediaProvider 不识别 Bundle 分页键（会忽略并全量返回），
 * 故回退为 sortOrder 直接拼 LIMIT（旧版解析安全）。
 * 绝不能在 API 29+ 把 LIMIT 写进 sortOrder——新版 MediaStore 会抛 "Invalid token LIMIT"。
 */
class GalleryRepository(private val ctx: Context) {

    data class MediaItem(val id: Long, val uri: Uri, val isVideo: Boolean, val bucketId: Long, val mime: String, val name: String)
    data class Bucket(val id: Long, val name: String, val count: Int)

    private val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    fun queryBuckets(): List<Bucket> {
        val map = LinkedHashMap<Long, Pair<String, Int>>()
        listOf(imageUri to false, videoUri to true).forEach { (uri, _) ->
            ctx.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "相册"
                    val cur = map[id]
                    map[id] = if (cur == null) name to 1 else cur.first to cur.second + 1
                }
            }
        }
        val all = map.values.sumOf { it.second }
        return listOf(Bucket(-1, "图片和视频", all)) +
            map.map { Bucket(it.key, it.value.first, it.value.second) }
    }

    /** 分页查询（bucketId = -1 表示全部图文视频）。
     *  单查询（Files 表，media_type IN (1,3)）保证图文混排顺序与偏移一致：
     *  API 29+ 用 Bundle QUERY_ARG_LIMIT/OFFSET；API 28 用 sortOrder 拼 LIMIT（旧版安全）。 */
    fun queryPage(bucketId: Long, offset: Int, limit: Int = 200): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            "bucket_id", // Files 表在 API 28 即有此列；常量 MediaColumns.BUCKET_ID 是 API 29+
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        val sel: String
        val selArgs: Array<String>
        if (bucketId != -1L) {
            sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?) AND bucket_id=?"
            selArgs = arrayOf(
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}",
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}", "$bucketId")
        } else {
            sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)"
            selArgs = arrayOf(
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}",
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}")
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selArgs)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            ctx.contentResolver.query(uri, projection, args, null)?.use { c -> collect(c, out) }
        } else {
            ctx.contentResolver.query(uri, projection, sel, selArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $offset,$limit"
            )?.use { c -> collect(c, out) }
        }
        return out
    }

    private fun collect(c: android.database.Cursor, out: MutableList<MediaItem>) {
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val bucket = c.getLong(1)
            val type = c.getInt(3)
            val isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val mime = c.getString(2) ?: if (isVideo) "video/mp4" else "image/jpeg"
            val name = c.getString(4) ?: ""
            out.add(MediaItem(id, ContentUris.withAppendedId(
                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), isVideo, bucket, mime, name))
        }
    }
}
