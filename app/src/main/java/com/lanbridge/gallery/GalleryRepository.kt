package com.lanbridge.gallery

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * MediaStore 查询（T14）：按 BUCKET 分组 + 内存分页切片。
 * 全部图文视频走 MediaStore.Files 单查询（media_type IN (1,3)），图文混排顺序稳定。
 *
 * v1.1.9g（需求：图库顺序与 v1.1.9f 显示的完全相反）：
 *  - 排序方向整体反转：由「从新到旧」改为「从旧到新」（sortKey 升序，同刻 id 降序，
 *    与 v1.1.9f 的排列互为镜像）。查询/分页/缓存逻辑不变，仅 sortDesc → sortAsc。
 *
 * v1.1.9f（需求：图库从新到旧）：
 *  - 排序键改为「拍摄时间优先」：DATE_TAKEN（毫秒）> 0 用拍摄时间，否则回退
 *    DATE_ADDED（秒）×1000。此前按 DATE_ADDED（入库时间）排序，wx_camera/mmexport
 *    等微信老照片今天才入库/重扫，会被排到最前面，用户看到的是"不是从新到旧"。
 *  - DATE_TAKEN 由扫描时从 EXIF 提取，微信拷入的图若被剥离 EXIF 则无此列值，
 *    自动回退 DATE_ADDED，行为与旧版一致（尽力而为）。
 *  - 分桶路径统一改为整桶载入内存排序（此前主卷桶走 MediaStore 端 LIMIT/OFFSET
 *    Bundle 分页，无法参与拍摄时间排序；「全部」桶本就整量入内存，单桶量级更小，
 *    内存开销可接受，且顺带规避了个别 ROM 对 Bundle 分页参数支持不全的问题）。
 *
 * v1.1.9：
 *  - SD 卡等辅助存储卷支持（需求⑥）：API 29+ 枚举 getExternalVolumeNames 逐卷查询；
 *    API 28 的 "external" 合并卷本身含 SD 卡，行为不变。
 *  - MediaItem 增加 relPath（RELATIVE_PATH / DATA 推导，预览页显示目录用）。
 *  - id 跨卷唯一化：辅助卷 id = mediaId + 卷序号 × 1e12，避免与主卷 mediaId 撞车
 *    （SelectionStore / itemById 都以 Long id 为键）。
 */
class GalleryRepository(private val ctx: Context) {

    data class MediaItem(
        val id: Long, val uri: Uri, val isVideo: Boolean, val bucketId: Long,
        val mime: String, val name: String,
        val volume: String = VOLUME_MAIN, val relPath: String = "",
        val dateAdded: Long = 0,
        val dateTaken: Long = 0, // 拍摄时间（毫秒；无 EXIF 时为 0）
    ) {
        /** 排序键：拍摄时间优先，无则回退入库时间（DATE_ADDED 为秒，统一到毫秒） */
        val sortKey: Long = if (dateTaken > 0) dateTaken else dateAdded * 1000
    }
    data class Bucket(val id: Long, val name: String, val count: Int, val volume: String = VOLUME_MAIN)

    companion object {
        const val ALL_BUCKET_ID = -1L
        /** 第一卷（主卷；API<29 为合并卷 external） */
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
     *  全部：各卷全量按拍摄时间归并排序后切片；单个桶：整桶载入内存排序后切片。 */
    fun queryPage(bucket: Bucket, offset: Int, limit: Int = 200): List<MediaItem> = when {
        bucket.id == ALL_BUCKET_ID -> {
            if (!allLoaded) {
                allCache.clear()
                volumes.forEach { vol -> collectQuery(filesUri(vol), vol, null, null, allCache) }
                sortAsc(allCache)
                allLoaded = true
            }
            allCache.drop(offset).take(limit)
        }
        else -> {
            val key = "${bucket.volume}:${bucket.id}"
            val list = bucketCache.getOrPut(key) {
                val out = mutableListOf<MediaItem>()
                collectQuery(filesUri(bucket.volume), bucket.volume,
                    "media_type IN (?,?) AND bucket_id",
                    arrayOf("${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}",
                        "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}", "${bucket.id}"), out)
                sortAsc(out)
                out
            }
            list.drop(offset).take(limit)
        }
    }

    /** v1.1.9g：从旧到新（与 v1.1.9f 的从新到旧互为镜像）：sortKey 升序，同刻按 id 降序 */
    private fun sortAsc(list: MutableList<MediaItem>) {
        list.sortWith(compareBy<MediaItem> { it.sortKey }.thenByDescending { it.id })
    }

    /** 整卷（可选 bucket 过滤）全量收集。
     *  个别 ROM 的 Files 表不认识 date_taken 列：查询失败时去掉该列重试（仅牺牲拍摄时间排序）。 */
    private fun collectQuery(uri: Uri, volume: String, sel: String?, args: Array<String>?,
                             out: MutableList<MediaItem>) {
        runCatching {
            ctx.contentResolver.query(uri, projection(withTaken = true), sel, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c -> collect(c, volume, out, withTaken = true) }
        }.recoverCatching {
            ctx.contentResolver.query(uri, projection(withTaken = false), sel, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c -> collect(c, volume, out, withTaken = false) }
        }
    }

    private fun projection(withTaken: Boolean) = buildList {
        add(MediaStore.MediaColumns._ID)
        add("bucket_id")
        add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        add(MediaStore.MediaColumns.DISPLAY_NAME)
        add(MediaStore.MediaColumns.DATE_ADDED)
        if (withTaken && !isLegacy) add(MediaStore.MediaColumns.DATE_TAKEN) // API 29+；毫秒
        if (!isLegacy) add(MediaStore.MediaColumns.RELATIVE_PATH) // API 29+，目录显示用
        add(MediaStore.MediaColumns.DATA) // 兜底：legacy 由完整路径推导目录
    }.toTypedArray()

    private fun collect(c: android.database.Cursor, volume: String, out: MutableList<MediaItem>,
                        withTaken: Boolean) {
        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val iBucket = c.getColumnIndexOrThrow("bucket_id")
        val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        // getColumnIndex（非 OrThrow）：列缺失返回 -1，走 DATE_ADDED 回退，绝不让单列缺失带崩整页
        val iTaken = if (withTaken && !isLegacy) c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN) else -1
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
                volume = volume, relPath = rel, dateAdded = c.getLong(iDate),
                dateTaken = if (iTaken >= 0) c.getLong(iTaken) else 0L))
        }
    }
}
