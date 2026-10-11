package com.lanbridge.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore

/**
 * MediaStore 查询（T14）：按 BUCKET 分组 + 游标分页。
 * 分页统一走 MediaStore.Files 单查询（media_type IN (1,3)），图文全局按时间排序，
 * 翻页偏移不会因图片/视频分开计数而错位。
 * API 29+ 用 Bundle QUERY_ARG_LIMIT/OFFSET 标准分页；
 * API 28 旧 MediaProvider 不识别 Bundle 分页键（会忽略并全量返回），
 * 故回退为 sortOrder 直接拼 LIMIT（旧版解析安全）。
 * 绝不能在 API 29+ 把 LIMIT 写进 sortOrder——新版 MediaStore 会抛 "Invalid token LIMIT"。
 *
 * v1.1.9：
 *  - SD 卡等辅助存储卷支持（需求⑥）：API 29+ 枚举 getExternalVolumeNames 逐卷查询；
 *    API 28 的 "external" 合并卷本身含 SD 卡，行为不变。
 *  - MediaItem 增加 relPath（RELATIVE_PATH / DATA 推导，预览页显示目录用）。
 *  - id 跨卷唯一化：辅助卷 id = mediaId + 卷序号 × 1e12，避免与主卷 mediaId 撞车
 *    （SelectionStore / itemById 都以 Long id 为键）。
 *  - 主卷保持 MediaStore 端分页；辅助卷（条目通常很少）与「全部」桶整卷载入内存后
 *    按日期归并切片，保证跨卷时间序正确。
 */
class GalleryRepository(private val ctx: Context) {

    data class MediaItem(
        val id: Long, val uri: Uri, val isVideo: Boolean, val bucketId: Long,
        val mime: String, val name: String,
        val volume: String = VOLUME_MAIN, val relPath: String = "",
        val dateAdded: Long = 0,
    )
    data class Bucket(val id: Long, val name: String, val count: Int, val volume: String = VOLUME_MAIN)

    companion object {
        const val ALL_BUCKET_ID = -1L
        /** 第一卷（主卷；API<29 为合并卷 external）走 MediaStore 端分页 */
        const val VOLUME_MAIN = "external"
        private const val VOL_OFFSET = 1_000_000_000_000L
    }

    private val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private val isLegacy = Build.VERSION.SDK_INT < 29

    /** 可用卷：API 29+ = 主卷 + 各辅助卷（SD/OTG）；API 28 = 合并卷（本身含 SD） */
    private val volumes: List<String> = if (isLegacy) listOf(VOLUME_MAIN) else {
        (listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) +
            MediaStore.getExternalVolumeNames(ctx).filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY })
    }

    private fun volIndexOf(v: String) = volumes.indexOf(v).coerceAtLeast(0)

    /** 跨卷唯一 id：主卷原样；辅助卷加卷序号偏移（单卷 mediaId 远达不到 1e12） */
    private fun uniqueId(volume: String, mediaId: Long): Long =
        if (volIndexOf(volume) == 0) mediaId else mediaId + volIndexOf(volume) * VOL_OFFSET

    private fun filesUri(volume: String): Uri =
        if (isLegacy) MediaStore.Files.getContentUri(VOLUME_MAIN)
        else MediaStore.Files.getContentUri(volume)

    private fun typedUri(volume: String, isVideo: Boolean): Uri = when {
        isLegacy -> if (isVideo) videoUri else imageUri
        isVideo -> MediaStore.Video.Media.getContentUri(volume)
        else -> MediaStore.Images.Media.getContentUri(volume)
    }

    // ---- 相册（桶）----

    fun queryBuckets(): List<Bucket> {
        val map = LinkedHashMap<String, Pair<String, Int>>() // key "vol:bucketId" -> name to count
        volumes.forEach { vol ->
            listOf(false, true).forEach { isVideo ->
                runCatching {
                    ctx.contentResolver.query(
                        typedUri(vol, isVideo),
                        arrayOf("bucket_id", "bucket_display_name"),
                        null, null, "date_added DESC"
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val name = c.getString(1) ?: "相册"
                            val key = "$vol:$id"
                            val cur = map[key]
                            map[key] = if (cur == null) name to 1 else cur.first to cur.second + 1
                        }
                    }
                }
            }
        }
        val all = map.values.sumOf { it.second }
        val main = if (isLegacy) VOLUME_MAIN else MediaStore.VOLUME_EXTERNAL_PRIMARY
        return listOf(Bucket(ALL_BUCKET_ID, "图片和视频", all, main)) +
            map.map { (k, v) ->
                val vol = k.substringBefore(':')
                Bucket(k.substringAfter(':').toLong(), v.first, v.second, vol)
            }
    }

    // ---- 分页查询 ----

    fun invalidate() { allCache.clear(); allLoaded = false; bucketCache.clear() }

    private val allCache = mutableListOf<MediaItem>()
    private var allLoaded = false
    private val bucketCache = HashMap<String, List<MediaItem>>()

    /** 分页查询（bucket.id = ALL_BUCKET_ID 表示全部图文视频）。
     *  主卷桶：MediaStore 端 Bundle LIMIT/OFFSET 分页（API 28 拼 LIMIT）；
     *  辅助卷桶：整卷载入内存切片；「全部」：各卷全量按日期归并后切片。 */
    fun queryPage(bucket: Bucket, offset: Int, limit: Int = 200): List<MediaItem> = when {
        bucket.id == ALL_BUCKET_ID -> {
            if (!allLoaded) {
                allCache.clear()
                volumes.forEach { vol -> collectQuery(filesUri(vol), vol, null, null, allCache) }
                allCache.sortWith(compareByDescending<MediaItem> { it.dateAdded }.thenBy { it.id })
                allLoaded = true
            }
            allCache.drop(offset).take(limit)
        }
        volIndexOf(bucket.volume) > 0 -> {
            val key = "${bucket.volume}:${bucket.id}"
            val list = bucketCache.getOrPut(key) {
                val out = mutableListOf<MediaItem>()
                collectQuery(filesUri(bucket.volume), bucket.volume,
                    "media_type IN (?,?) AND bucket_id",
                    arrayOf("${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}",
                        "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}", "${bucket.id}"), out)
                out.sortWith(compareByDescending<MediaItem> { it.dateAdded }.thenBy { it.id })
                out
            }
            list.drop(offset).take(limit)
        }
        else -> queryMainPaged(bucket.id, offset, limit)
    }

    /** 主卷分页（原 T14 路径，单查询保证图文混排顺序与偏移一致） */
    private fun queryMainPaged(bucketId: Long, offset: Int, limit: Int): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val mainVol = if (isLegacy) VOLUME_MAIN else MediaStore.VOLUME_EXTERNAL_PRIMARY
        val uri = filesUri(mainVol)
        val sel: String
        val selArgs: Array<String>
        if (bucketId != ALL_BUCKET_ID) {
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
        if (!isLegacy) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selArgs)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            ctx.contentResolver.query(uri, projection(), args, null)?.use { c -> collect(c, mainVol, out) }
        } else {
            ctx.contentResolver.query(uri, projection(), sel, selArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $offset,$limit"
            )?.use { c -> collect(c, mainVol, out) }
        }
        return out
    }

    /** 整卷（可选 bucket 过滤）全量收集，用于辅助卷与「全部」桶 */
    private fun collectQuery(uri: Uri, volume: String, sel: String?, args: Array<String>?,
                             out: MutableList<MediaItem>) {
        runCatching {
            ctx.contentResolver.query(uri, projection(), sel, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c -> collect(c, volume, out) }
        }
    }

    private fun projection() = buildList {
        add(MediaStore.MediaColumns._ID)
        add("bucket_id")
        add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        add(MediaStore.MediaColumns.DISPLAY_NAME)
        add(MediaStore.MediaColumns.DATE_ADDED)
        if (!isLegacy) add(MediaStore.MediaColumns.RELATIVE_PATH) // API 29+，目录显示用
        add(MediaStore.MediaColumns.DATA) // 兜底：legacy 由完整路径推导目录
    }.toTypedArray()

    private fun collect(c: android.database.Cursor, volume: String, out: MutableList<MediaItem>) {
        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val iBucket = c.getColumnIndexOrThrow("bucket_id")
        val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val iRel = if (!isLegacy) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
        val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
        val legacyRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        while (c.moveToNext()) {
            val mediaId = c.getLong(iId)
            val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val mime = c.getString(iMime) ?: if (isVideo) "video/mp4" else "image/jpeg"
            val name = c.getString(iName) ?: ""
            val rel = when {
                iRel >= 0 -> c.getString(iRel).orEmpty()
                else -> c.getString(iData)?.let { p ->
                    val dir = p.substringBeforeLast('/', "")
                    if (dir.startsWith(legacyRoot)) dir.removePrefix(legacyRoot).trimStart('/')
                        .let { if (it.isEmpty()) "" else "$it/" } else ""
                } ?: ""
            }
            out.add(MediaItem(
                id = uniqueId(volume, mediaId),
                uri = ContentUris.withAppendedId(typedUri(volume, isVideo), mediaId),
                isVideo = isVideo, bucketId = c.getLong(iBucket), mime = mime, name = name,
                volume = volume, relPath = rel, dateAdded = c.getLong(iDate)))
        }
    }
}
