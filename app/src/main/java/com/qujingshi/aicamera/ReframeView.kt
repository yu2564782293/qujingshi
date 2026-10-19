package com.qujingshi.aicamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * AI 重构图：照片 fitCenter 显示，AI 推荐框外压暗、框内高亮，
 * 一眼看出"这张照片里哪块最好看"。
 */
class ReframeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null

    /** 正规化裁剪框（相对 bitmap），null = 不画框 */
    private var box: RectF? = null

    private val dstRect = RectF()

    private val dimPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    fun setPhoto(bmp: Bitmap?, boxNorm: RectF?) {
        bitmap = bmp
        box = boxNorm
        computeDst()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeDst()
    }

    private fun computeDst() {
        val bmp = bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0 || bmp.width <= 0 || bmp.height <= 0) return
        val scale = min(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        dstRect.set((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (dstRect.isEmpty) computeDst()
        canvas.drawBitmap(bmp, null, dstRect, null)

        val b = box ?: return
        val r = RectF(
            dstRect.left + b.left * dstRect.width(),
            dstRect.top + b.top * dstRect.height(),
            dstRect.left + b.right * dstRect.width(),
            dstRect.top + b.bottom * dstRect.height(),
        )
        // 框外压暗
        canvas.drawRect(dstRect.left, dstRect.top, dstRect.right, r.top, dimPaint)
        canvas.drawRect(dstRect.left, r.bottom, dstRect.right, dstRect.bottom, dimPaint)
        canvas.drawRect(dstRect.left, r.top, r.left, r.bottom, dimPaint)
        canvas.drawRect(r.right, r.top, dstRect.right, r.bottom, dimPaint)
        canvas.drawRoundRect(r, 14f, 14f, boxPaint)
    }
}
