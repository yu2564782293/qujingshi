package com.qujingshi.aicamera

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.min

/**
 * 几何快筛：把主体关键点映射到候选框局部坐标，用构图几何特征打分（0~1），微秒级。
 * 用于从十几个候选中挑出 Top3，再交给 NIMA 重排——NIMA 推理预算与原来持平。
 *
 * 几何量测与 CompositionAdvisor 同源（阈值均为相对值）：切头否决、三分距离、
 * 头顶留白、主体大小、眼睛线、切小腿惩罚。
 */
object BoxScorer {

    /**
     * @param box 候选框（正规化坐标）
     * @param subjects 每人 33 点 [x, y, visibility]（相对整帧的正规化坐标）
     */
    fun score(box: RectF, subjects: List<List<FloatArray>>, scene: SceneType): Float {
        if (subjects.isEmpty()) return 0.5f
        val bw = box.width().coerceAtLeast(1e-6f)
        val bh = box.height().coerceAtLeast(1e-6f)
        val local = subjects.map { person ->
            person.map { floatArrayOf((it[0] - box.left) / bw, (it[1] - box.top) / bh, it[2]) }
        }

        // 任一主体切头 → 直接否决（不要切头的取景）
        if (local.any { headTop(it) < 0.015f }) return 0.02f

        var score = 0f
        // 三分：群体重心（单人即本人）到最近锚点的距离
        val gcx = local.map { centerX(it) }.average().toFloat()
        val dThirds = floatArrayOf(1f / 3f, 0.5f, 2f / 3f).minOf { abs(gcx - it) }
        score += 0.40f * (1f - min(dThirds / 0.25f, 1f))
        // 头顶留白：最高头顶超过 30% 画面高开始扣分
        val top = local.minOf { headTop(it) }
        score += 0.20f * (1f - min(maxOf(0f, top - 0.30f) / 0.20f, 1f))
        // 主体大小：框内身高 0.45~0.75 最佳
        val h = local.map { subjectHeight(it) }.average().toFloat()
        score += 0.20f * (1f - min(distToInterval(h, 0.45f, 0.75f) / 0.30f, 1f))
        // 眼睛线（仅单人且主体够大时）：眼睛贴上三分线；否则中性分
        // （与 CompositionAdvisor 一致：小主体不苛求眼睛线）
        val eyeOk = if (local.size == 1 && h > 0.5f) {
            1f - min(abs(eyeY(local[0]) - 1f / 3f) / 0.12f, 1f)
        } else {
            0.5f
        }
        score += 0.20f * eyeOk
        // 切在小腿：打七折
        if (local.any { kneeIn(it) && ankleOut(it) }) score *= 0.7f
        return score.coerceIn(0f, 1f)
    }

    // ── 以下几何量测与 CompositionAdvisor.Subject 同逻辑（框局部坐标，框高=1）──

    private fun headTop(pts: List<FloatArray>): Float {
        val headWidth = abs(pts[7][0] - pts[8][0]).coerceAtLeast(0.02f)
        var faceTop = Float.MAX_VALUE
        for (i in 0..10) faceTop = minOf(faceTop, pts[i][1])
        return faceTop - headWidth * 0.6f
    }

    private fun inBox(p: FloatArray) = p[2] > 0.5f && p[0] in 0f..1f && p[1] in 0f..1f

    private fun kneeIn(pts: List<FloatArray>) = inBox(pts[25]) || inBox(pts[26])

    private fun ankleOut(pts: List<FloatArray>) = !inBox(pts[27]) && !inBox(pts[28])

    private fun centerX(pts: List<FloatArray>) =
        (pts[11][0] + pts[12][0] + pts[23][0] + pts[24][0]) / 4f

    private fun eyeY(pts: List<FloatArray>) = (pts[2][1] + pts[5][1]) / 2f

    private fun subjectHeight(pts: List<FloatArray>): Float {
        var b = (pts[23][1] + pts[24][1]) / 2f
        for (i in intArrayOf(25, 26, 27, 28)) if (inBox(pts[i])) b = maxOf(b, pts[i][1])
        return b - headTop(pts)
    }

    private fun distToInterval(v: Float, lo: Float, hi: Float): Float = when {
        v < lo -> lo - v
        v > hi -> v - hi
        else -> 0f
    }
}
