package com.qujingshi.aicamera

import android.graphics.PointF
import androidx.annotation.StringRes
import kotlin.math.abs
import kotlin.math.min

/**
 * 构图规则引擎：吃「view 座标系的主体们（路人已被过滤）」，一次只给一个最重要的提示。
 *
 * 规则优先序：切到头 > 切在小腿 > 群体中心对三分线 > 头上空白太多 > 眼睛对上三分线（仅单人）。
 * 内建防抖：新提示要连续成立 [STABLE_FRAMES] 帧才会显示，避免画面一直闪。
 */
class CompositionAdvisor {

    enum class Hint(@StringRes val textRes: Int) {
        HEAD_CUT(R.string.advice_head_cut),
        LEG_CUT(R.string.advice_leg_cut),
        MOVE_TO_THIRD(R.string.advice_move_to_third),
        HEADROOM(R.string.advice_headroom),
        EYE_LINE(R.string.advice_eye_line),
        GOOD(R.string.advice_good),
    }

    /** arrowFrom/arrowTo 为 null 时只显示文字 */
    data class Advice(val hint: Hint, val arrowFrom: PointF?, val arrowTo: PointF?)

    private var current: Advice? = null
    private var candidateHint: Hint? = null
    private var streak = 0

    /** @param persons 每位主体 MediaPipe 33 点，已映射到 view 座标；每点 [x, y, visibility] */
    fun update(persons: List<List<FloatArray>>?, w: Float, h: Float): Advice? {
        val valid = persons?.filter { it.size >= 33 }
        val raw = if (!valid.isNullOrEmpty() && w > 0 && h > 0) {
            evaluate(valid, w, h)
        } else {
            null
        }

        val hint = raw?.hint
        if (hint == current?.hint) {
            // 同一个提示：直接更新箭头位置（跟著人动），不重新计时
            candidateHint = null
            streak = 0
            if (raw != null) current = raw
            return current
        }
        if (hint == candidateHint) {
            streak++
        } else {
            candidateHint = hint
            streak = 1
        }
        if (streak >= STABLE_FRAMES) {
            current = raw
            candidateHint = null
            streak = 0
        }
        return current
    }

    /** 单一主体的构图相关量测 */
    private class Subject(pts: List<FloatArray>, w: Float, h: Float) {
        val headTop: Float
        val eyeX = (pts[2][0] + pts[5][0]) / 2f
        val eyeY = (pts[2][1] + pts[5][1]) / 2f
        val centerX = (pts[11][0] + pts[12][0] + pts[23][0] + pts[24][0]) / 4f
        val chestY = (pts[11][1] + pts[12][1] + pts[23][1] + pts[24][1]) / 4f
        val bottom: Float
        val height: Float
        val kneeIn: Boolean
        val ankleOut: Boolean

        init {
            fun inFrame(i: Int) =
                pts[i][2] > 0.5f && pts[i][1] in 0f..h && pts[i][0] in 0f..w

            // 头顶估计：脸部点的最高处再往上抓半个头宽
            val headWidth = abs(pts[7][0] - pts[8][0]).coerceAtLeast(h * 0.02f)
            var faceTop = Float.MAX_VALUE
            for (i in 0..10) faceTop = min(faceTop, pts[i][1])
            headTop = faceTop - headWidth * 0.6f

            var b = (pts[23][1] + pts[24][1]) / 2f
            for (i in intArrayOf(25, 26, 27, 28)) if (inFrame(i)) b = maxOf(b, pts[i][1])
            bottom = b
            height = bottom - headTop

            kneeIn = inFrame(25) || inFrame(26)
            ankleOut = !inFrame(27) && !inFrame(28)
        }
    }

    private fun evaluate(persons: List<List<FloatArray>>, w: Float, h: Float): Advice {
        val subjects = persons.map { Subject(it, w, h) }

        // 1. 有任何主体切到头
        subjects.firstOrNull { it.headTop < h * 0.015f }?.let {
            return Advice(Hint.HEAD_CUT, PointF(it.eyeX, h * 0.06f), PointF(it.eyeX, h * 0.16f))
        }

        // 2. 有任何主体膝盖在画面内、脚踝不在 → 切在小腿
        if (subjects.any { it.kneeIn && it.ankleOut }) {
            return Advice(Hint.LEG_CUT, null, null)
        }

        // 3. 群体中心对三分线（也接受正中央——居中构图也是合法选择）
        val groupCenterX = subjects.map { it.centerX }.average().toFloat()
        val groupChestY = subjects.map { it.chestY }.average().toFloat()
        val anchors = floatArrayOf(w / 3f, w / 2f, w * 2f / 3f)
        var nearest = anchors[0]
        for (a in anchors) if (abs(groupCenterX - a) < abs(groupCenterX - nearest)) nearest = a
        if (abs(groupCenterX - nearest) > w * 0.08f) {
            return Advice(
                Hint.MOVE_TO_THIRD,
                PointF(groupCenterX, groupChestY),
                PointF(nearest, groupChestY),
            )
        }

        // 4. 头上空白太多（以最高的头为准；群体都偏小偏低才成立）
        val groupHeadTop = subjects.minOf { it.headTop }
        val groupBottom = subjects.maxOf { it.bottom }
        if (groupHeadTop > h * 0.30f && groupBottom - groupHeadTop < h * 0.65f) {
            val ref = subjects.minBy { it.headTop }
            return Advice(
                Hint.HEADROOM,
                PointF(ref.eyeX, groupHeadTop - h * 0.02f),
                PointF(ref.eyeX, h * 0.10f),
            )
        }

        // 5. 单人且人够大时，眼睛对上三分线（多人眼睛高度不一，不适用）
        if (subjects.size == 1) {
            val s = subjects[0]
            if (s.height > h * 0.5f && abs(s.eyeY - h / 3f) > h * 0.12f) {
                return Advice(Hint.EYE_LINE, PointF(s.eyeX, s.eyeY), PointF(s.eyeX, h / 3f))
            }
        }

        return Advice(Hint.GOOD, null, null)
    }

    private companion object {
        // 侦测约 15–30fps，8 帧 ≈ 0.3–0.5 秒
        const val STABLE_FRAMES = 8
    }
}
