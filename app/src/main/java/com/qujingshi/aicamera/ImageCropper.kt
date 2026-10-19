package com.qujingshi.aicamera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * 按 AI 框裁剪照片的公共工具（拍照智能裁剪 / 相册 AI 重构图共用）。
 *
 * 流程：读 EXIF 方向 → 把正向正规化坐标映射到存储像素 → 只解码裁剪区
 * （BitmapRegionDecoder，省内存）→ 按 EXIF 转正像素 → 回传转正 Bitmap。
 */
object ImageCropper {

    private const val TAG = "Qujingshi"

    /**
     * @param box 正向画面下的正规化裁剪框（0~1）
     * @return 转正后的裁剪 Bitmap；失败返回 null（调用方保留原图）
     */
    fun cropUpright(resolver: ContentResolver, srcUri: Uri, box: RectF): Bitmap? {
        return try {
            resolver.openFileDescriptor(srcUri, "r")?.use { pfd ->
                cropUpright(pfd, box)
            }
        } catch (e: Exception) {
            Log.w(TAG, "crop failed", e)
            null
        }
    }

    private fun cropUpright(pfd: ParcelFileDescriptor, box: RectF): Bitmap? {
        val orientation = ExifInterface(pfd.fileDescriptor).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val decoder = BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
            ?: return null
        try {
            val rect = mapBoxToStored(box, decoder.width, decoder.height, orientation)
            if (rect.width() < 16 || rect.height() < 16) return null
            val cropped = decoder.decodeRegion(rect, BitmapFactory.Options())
            // 存储像素按 EXIF 转正，否则存出的图是横/倒的
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            return if (deg == 0f) {
                cropped
            } else {
                val m = Matrix()
                m.postRotate(deg)
                Bitmap.createBitmap(
                    cropped, 0, 0, cropped.width, cropped.height, m, true,
                ).also { cropped.recycle() }
            }
        } finally {
            decoder.recycle()
        }
    }

    /** 正向正规化坐标 → 存储像素坐标（含 EXIF 旋转映射） */
    fun mapBoxToStored(box: RectF, sw: Int, sh: Int, orientation: Int): Rect {
        val swap = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        val uw = if (swap) sh else sw
        val uh = if (swap) sw else sh
        val l = (box.left * uw).toInt().coerceIn(0, uw)
        val t = (box.top * uh).toInt().coerceIn(0, uh)
        val r = (box.right * uw).toInt().coerceIn(0, uw)
        val b = (box.bottom * uh).toInt().coerceIn(0, uh)
        val rect = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_180 -> Rect(sw - r, sh - b, sw - l, sh - t)
            ExifInterface.ORIENTATION_ROTATE_90 -> Rect(t, sh - r, b, sh - l)
            ExifInterface.ORIENTATION_ROTATE_270 -> Rect(sw - b, l, sw - t, r)
            else -> Rect(l, t, r, b)
        }
        rect.intersect(0, 0, sw, sh)
        return rect
    }
}
