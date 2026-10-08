package com.lanbridge.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 图片压缩（T17）：平台 API 默认（inSampleSize 降采样 → 缩到长边 → JPEG compress）。
 * 设置项：compressMaxLongSide（1080/1920/原图）、compressQuality（60/80/90）。
 * NDK 后门：接口保持不变，压测瓶颈时替换实现（§2.1.3）。
 */
object ImageCompressor {

    fun compress(src: File, maxLongSide: Int, quality: Int, outDir: File): File? {
        if (maxLongSide <= 0) return src // 原图直传
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, opts)
        if (opts.outWidth <= 0) return null

        var sample = 1
        var w = opts.outWidth; var h = opts.outHeight
        while (maxOf(w, h) / 2 >= maxLongSide) {
            sample *= 2; w /= 2; h /= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, decodeOpts) ?: return null

        val longSide = maxOf(bmp.width, bmp.height)
        val scaled = if (longSide > maxLongSide) {
            val ratio = maxLongSide.toFloat() / longSide
            Bitmap.createScaledBitmap(bmp,
                (bmp.width * ratio).toInt().coerceAtLeast(1),
                (bmp.height * ratio).toInt().coerceAtLeast(1), true)
        } else bmp

        outDir.mkdirs()
        val out = File(outDir, "c_${System.currentTimeMillis()}_${src.name}")
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out.outputStream())
        if (scaled !== bmp) bmp.recycle()
        return if (ok) out else null
    }
}
