package com.lanbridge.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * MediaStore 查询（T14）：按 BUCKET 分组 + 游标分页（Bundle QUERY_ARG_LIMIT/OFFSET，API 26+），
 * 绝不在内存持有 25k 全量。投影含 _ID/BUCKET/DATE/MIME/SIZE，DATE_ADDED DESC。
 */
class GalleryRepository(private val ctx: Context) {

    data class MediaItem(val id: Long, val uri: Uri, val isVideo: Boolean, val bucketId: Long, val mime: String)
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
     *  分页必须用 Bundle 的 QUERY_ARG_LIMIT/OFFSET（API 26+），
     *  绝不能把 LIMIT 写进 sortOrder——新版 MediaStore 会抛 "Invalid token LIMIT"。 */
    fun queryPage(bucketId: Long, offset: Int, limit: Int = 200): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.MIME_TYPE
        )
        listOf(imageUri to false, videoUri to true).forEach { (uri, isVideo) ->
            val args = Bundle().apply {
                if (bucketId != -1L) {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.MediaColumns.BUCKET_ID}=?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf("$bucketId"))
                }
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            ctx.contentResolver.query(uri, projection, args, null)?.use { c ->
                collect(c, out, isVideo)
            }
        }
        return out.sortedByDescending { it.id }
    }

    private fun collect(c: android.database.Cursor, out: MutableList<MediaItem>, isVideo: Boolean) {
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val bucket = c.getLong(1)
            val mime = c.getString(2) ?: if (isVideo) "video/mp4" else "image/jpeg"
            out.add(MediaItem(id, ContentUris.withAppendedId(
                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), isVideo, bucket, mime))
        }
    }
}
