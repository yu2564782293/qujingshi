package com.qujingshi.aicamera

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import androidx.annotation.StringRes
import kotlin.math.abs

/**
 * L2 AI 取景：场景路由 → 密集候选框 → 几何快筛 Top3 → NIMA 重排。
 *
 * 相对 v0.5 的 6 个固定框：人像/合影用主体锚定的 ~19 个候选（主体落在九宫格各点位），
 * 风景用 3x3 网格。NIMA 推理预算不变（人像 4 次/轮，风景 11 次/3 秒一轮）。
 * 规则引擎（CompositionAdvisor）继续负责每帧的即时硬伤纠错，这里只回答
 * "附近有没有明显更好的取景"。
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
        val currentScore: Float,
        val bestScore: Float,
        val direction: Direction,
        val bestBox: android.graphics.RectF, // 最佳候选框（正规化坐标，相对整帧）
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
        // 快筛：风景无几何可用，直接全量 NIMA；人像/合影先几何取 Top3
        val shortlist = if (scene == SceneType.LANDSCAPE) {
            boxes.drop(1)
        } else {
            boxes.drop(1).sortedByDescending { BoxScorer.score(it, persons, scene) }.take(3)
        }

        val currentScore = scorer.score(frame)
        var bestBox: RectF = baseline
        var bestScore = currentScore
        for (b in shortlist) {
            val s = scorer.score(cropNorm(frame, b))
            if (s > bestScore) {
                bestScore = s
                bestBox = b
            }
        }
        // 要赢过现状一个看得出来的差距才建议，避免噪声来回跳
        val dir = if (bestScore - currentScore >= MIN_GAIN) directionFor(bestBox)
        else Direction.STAY
        return Result(currentScore, bestScore, dir, bestBox)
    }

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
        const val MIN_GAIN = 0.12f
        const val DIR_T = 0.04f
        const val MIN_SUBJECT_H = 0.08f // 与 SubjectTracker 的远景主体门槛一致
        const val LANDSCAPE_INTERVAL_MS = 3000L
    }
}
