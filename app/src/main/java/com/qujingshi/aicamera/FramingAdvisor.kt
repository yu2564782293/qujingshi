package com.qujingshi.aicamera

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import androidx.annotation.StringRes
import kotlin.math.abs

/**
 * AI 取景：场景路由 → 密集候选框 → 几何构图分全量打分 → Top5 →
 * NIMA 美学分 → 双路归一化融合（各 50%）选最优。
 *
 * 取自 GAIC 论文思想：最终取景质量 = 美学 × 几何构图 × 内容保留。
 * 几何分不再是"海选过滤器"，而是和 NIMA 平起平坐共同决赛——
 * 纯 NIMA 容易选出几何别扭（地平线居中、主体贴边）的高饱和裁剪。
 * 风景场景有地平线检测（亮度梯度最大行），无人时几何分由地平线三分主导。
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

    data class Result(
        val currentScore: Float, // NIMA 当前全帧分（1~10，展示用）
        val bestScore: Float,    // NIMA 最佳框分（1~10，展示用）
        val direction: Direction,
        val bestBox: RectF,      // 最佳候选框（正规化坐标，相对整帧）
        val blendGain: Float,    // 融合分提升（0~1，自动变焦/裁剪的决策依据）
    )

    private val router = SceneRouter()
    private var lastLandscapeMs = 0L

    /**
     * 在背景线程调用。
     * @param subjectsNorm 每人 33 点 [x, y, visibility]（相对整帧的正规化坐标，与 frame 对应）
     * @return null = 本轮跳过（风景场景节流）或无法评估
     */
    fun analyze(frame: Bitmap, subjectsNorm: List<List<FloatArray>>): Result? {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return null

        val persons = subjectsNorm.filter { it.size >= 33 }
        val subjectBoxes = persons
            .map { bboxNorm(it) }
            .filter { it.height() >= MIN_SUBJECT_H }

        val scene = router.update(subjectBoxes.size)
        if (scene == SceneType.LANDSCAPE) {
            val now = SystemClock.uptimeMillis()
            if (now - lastLandscapeMs < LANDSCAPE_INTERVAL_MS) return null
            lastLandscapeMs = now
        }

        val boxes = CandidateGenerator.generate(scene, subjectBoxes)
        val baseline = boxes[0]
        val horizonY = if (scene == SceneType.LANDSCAPE) detectHorizon(frame) else null
        val saliency = if (scene == SceneType.LANDSCAPE) saliencyCentroid(frame) else null

        // 几何分全量打分（微秒级），取 Top5 进决赛
        val geoAll = boxes.map { BoxScorer.score(it, persons, scene, horizonY, saliency) }
        val ranked = boxes.drop(1)
            .mapIndexed { i, b -> b to geoAll[i + 1] }
            .sortedByDescending { it.second }
            .take(SHORTLIST)

        // NIMA：baseline + Top5（风景从 11 次降到 6 次，人像从 4 次升到 6 次）
        val nimaBase = scorer.score(frame)
        val nimaRank = ranked.map { (b, _) -> scorer.score(cropNorm(frame, b)) }

        // 双路归一化后融合：NIMA 1~10 → 0~1 绝对尺度，几何分本来就是 0~1。
        // 不用 min-max——它会把噪声级差异放大成满权重；绝对尺度下，
        // 区分度小的信号自动让位给区分度大的信号。
        val baseFinal = 0.5f * geoAll[0] + 0.5f * nimaNorm(nimaBase)
        var bestIdx = -1
        var bestFinal = baseFinal
        for (i in ranked.indices) {
            val f = 0.5f * ranked[i].second + 0.5f * nimaNorm(nimaRank[i])
            if (f > bestFinal) {
                bestFinal = f
                bestIdx = i
            }
        }

        val bestBox = if (bestIdx >= 0) ranked[bestIdx].first else baseline
        val bestNima = if (bestIdx >= 0) nimaRank[bestIdx] else nimaBase
        val gain = bestFinal - baseFinal
        val dir = if (gain >= MIN_GAIN) directionFor(bestBox) else Direction.STAY
        return Result(nimaBase, bestNima, dir, bestBox, gain)
    }

    /**
     * 地平线检测：24x24 采样算每行平均亮度，取 25%~75% 行之间亮度梯度最大处。
     * 梯度太弱（室内/纯纹理）返回 null。
     */
    private fun detectHorizon(frame: Bitmap): Float? {
        val w = frame.width
        val h = frame.height
        val rows = 24
        val cols = 24
        val luma = FloatArray(rows)
        for (r in 0 until rows) {
            val y = ((r + 0.5f) / rows * h).toInt().coerceIn(0, h - 1)
            var sum = 0f
            for (c in 0 until cols) {
                val x = ((c + 0.5f) / cols * w).toInt().coerceIn(0, w - 1)
                val p = frame.getPixel(x, y)
                sum += (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)
            }
            luma[r] = sum / cols / (3f * 255f)
        }
        var best = -1
        var bestG = 0f
        for (r in 6 until rows - 6) {
            val g = abs(luma[r + 1] - luma[r - 1])
            if (g > bestG) {
                bestG = g
                best = r
            }
        }
        if (best < 0 || bestG < 0.05f) return null
        return (best + 0.5f) / rows
    }

    /**
     * 显著性重心：24x24 采样，每格按"色度 × (1-亮度)"加权求重心。
     * 天空（亮、灰）权重低，房子/树（暗、彩）权重高——风景没有人物时，
     * 用它当"视觉主体"做三分构图。场景太平返回 null。
     */
    private fun saliencyCentroid(frame: Bitmap): FloatArray? {
        val w = frame.width
        val h = frame.height
        val rows = 24
        val cols = 24
        var sumW = 0f
        var sumX = 0f
        var sumY = 0f
        for (r in 0 until rows) {
            val y = ((r + 0.5f) / rows * h).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val x = ((c + 0.5f) / cols * w).toInt().coerceIn(0, w - 1)
                val p = frame.getPixel(x, y)
                val rC = p shr 16 and 0xFF
                val gC = p shr 8 and 0xFF
                val bC = p and 0xFF
                val chroma = (maxOf(rC, gC, bC) - minOf(rC, gC, bC)) / 255f
                val luma = (rC + gC + bC) / (3f * 255f)
                val wt = chroma * (1f - luma)
                sumW += wt
                sumX += wt * (c + 0.5f) / cols
                sumY += wt * (r + 0.5f) / rows
            }
        }
        if (sumW < 1e-6f) return null
        return floatArrayOf(sumX / sumW, sumY / sumW)
    }

    /** NIMA 1~10 → 0~1 绝对尺度 */
    private fun nimaNorm(s: Float) = ((s - 1f) / 9f).coerceIn(0f, 1f)

    private fun directionFor(box: RectF): Direction {
        val dx = box.centerX() - 0.5f
        val dy = box.centerY() - 0.5f
        return when {
            dx < -DIR_T -> Direction.LEFT
            dx > DIR_T -> Direction.RIGHT
            dy < -DIR_T -> Direction.UP
            dy > DIR_T -> Direction.DOWN
            box.width() < 0.78f -> Direction.CLOSER
            else -> Direction.STAY
        }
    }

    private fun cropNorm(frame: Bitmap, box: RectF): Bitmap {
        val x = (box.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val y = (box.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val cw = ((box.right - box.left) * frame.width).toInt().coerceIn(1, frame.width - x)
        val ch = ((box.bottom - box.top) * frame.height).toInt().coerceIn(1, frame.height - y)
        return Bitmap.createBitmap(frame, x, y, cw, ch)
    }

    private fun bboxNorm(pts: List<FloatArray>): RectF {
        var l = 1f
        var t = 1f
        var r = 0f
        var b = 0f
        for (p in pts) {
            l = minOf(l, p[0])
            t = minOf(t, p[1])
            r = maxOf(r, p[0])
            b = maxOf(b, p[1])
        }
        return RectF(l, t, r, b)
    }

    private companion object {
        const val MIN_GAIN = 0.08f // 融合分（0~1）要赢过现状才动手
        const val SHORTLIST = 5 // 几何 Top5 进 NIMA 决赛
        const val DIR_T = 0.04f
        const val MIN_SUBJECT_H = 0.08f // 与 SubjectTracker 的远景主体门槛一致
        const val LANDSCAPE_INTERVAL_MS = 3000L
    }
}
