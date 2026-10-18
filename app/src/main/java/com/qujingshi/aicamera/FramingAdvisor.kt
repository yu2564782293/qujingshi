package com.qujingshi.aicamera

import android.graphics.Bitmap
import androidx.annotation.StringRes

/**
 * AI 取景建议：这就是「打分模型没办法变成引导、取景推荐模型才可以」的落地——
 * 对目前取景与往各方向偏移的候选取景框分别打美感分数，
 * 哪个候选明显更高分，就建议往那个方向调整。纯风景（没有人）也有效。
 */
class FramingAdvisor(private val scorer: AestheticScorer) {

    enum class Direction(@StringRes val textRes: Int) {
        LEFT(R.string.ai_dir_left),
        RIGHT(R.string.ai_dir_right),
        UP(R.string.ai_dir_up),
        DOWN(R.string.ai_dir_down),
        CLOSER(R.string.ai_dir_closer),
        STAY(R.string.ai_dir_stay),
    }

    data class Result(val currentScore: Float, val bestScore: Float, val direction: Direction)

    /** 在背景执行绪呼叫；一轮约 6 次推论。 */
    fun analyze(frame: Bitmap): Result {
        val w = frame.width
        val h = frame.height
        // 基准取景：置中 86% 视窗（留边让候选框有偏移空间）
        val cw = (w * 0.86f).toInt()
        val ch = (h * 0.86f).toInt()
        val cx = (w - cw) / 2
        val cy = (h - ch) / 2
        val off = (w * 0.07f).toInt()
        val offY = (h * 0.07f).toInt()

        fun crop(x: Int, y: Int, cwidth: Int = cw, cheight: Int = ch): Bitmap =
            Bitmap.createBitmap(
                frame,
                x.coerceIn(0, w - cwidth),
                y.coerceIn(0, h - cheight),
                cwidth,
                cheight,
            )

        val current = scorer.score(crop(cx, cy))
        var best = current
        var bestDir = Direction.STAY
        val candidates = listOf(
            Direction.LEFT to crop(cx - off, cy),
            Direction.RIGHT to crop(cx + off, cy),
            Direction.UP to crop(cx, cy - offY),
            Direction.DOWN to crop(cx, cy + offY),
            Direction.CLOSER to crop(
                (w * 0.14f).toInt(), (h * 0.14f).toInt(),
                (w * 0.72f).toInt(), (h * 0.72f).toInt(),
            ),
        )
        for ((dir, bmp) in candidates) {
            val s = scorer.score(bmp)
            if (s > best) {
                best = s
                bestDir = dir
            }
        }
        // 要赢过现况一个看得出来的差距才建议，避免杂讯来回跳
        return Result(current, best, if (best - current >= MIN_GAIN) bestDir else Direction.STAY)
    }

    private companion object {
        const val MIN_GAIN = 0.12f
    }
}
