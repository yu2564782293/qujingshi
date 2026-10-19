package com.qujingshi.aicamera

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 构图几何打分（0~1），微秒级。
 *
 * 设计取自 GAIC 论文思想：最终取景 = 美学分 × 几何构图分 × 内容保留，
 * 几何分不再只是"海选过滤器"，而是和 NIMA 做归一化融合后共同决赛。
 *
 * 人像/合影：三分（群体重心）0.30 ＋ 头顶留白 0.15 ＋ 主体大小 0.15 ＋
 *   眼睛线 0.15 ＋ 脸部三分点 0.15 ＋ 边缘余量 0.10；
 *   切脸/切头否决，切肩/切小腿打折。
 * 风景（无主体）：地平线三分 placement；无明显地平线时中性 0.5，交给 NIMA。
 *
 * @param box 候选框（正规化坐标）
 * @param subjects 每人 33 点 [x, y, visibility]（相对整帧的正规化坐标）
 * @param horizonY 风景地平线（相对整帧的正规化 y），null = 测不到/不适用
 */
object BoxScorer {

    fun score(
        box: RectF,
        subjects: List<List<FloatArray>>,
        scene: SceneType,
        horizonY: Float? = null,
        saliency: FloatArray? = null,
    ): Float {
        if (subjects.isEmpty()) return landscapeScore(box, horizonY, saliency)

        val bw = box.width().coerceAtLeast(1e-6f)
        val bh = box.height().coerceAtLeast(1e-6f)
        val local = subjects.map { person ->
            person.map { floatArrayOf((it[0] - box.left) / bw, (it[1] - box.top) / bh, it[2]) }
        }

        // 内容保留：切脸/切头直接否决
        if (local.any { noseOut(it) }) return 0.02f
        if (local.any { headTop(it) < 0.015f }) return 0.02f

        var score = 0f
        // 三分：群体重心（单人即本人）到最近 x 锚点的距离
        val gcx = local.map { centerX(it) }.average().toFloat()
        val dThirds = floatArrayOf(1f / 3f, 0.5f, 2f / 3f).minOf { abs(gcx - it) }
        score += 0.30f * (1f - min(dThirds / 0.25f, 1f))
        // 头顶留白：最高头顶超过 30% 画面高开始扣分
        val top = local.minOf { headTop(it) }
        score += 0.15f * (1f - min(maxOf(0f, top - 0.30f) / 0.20f, 1f))
        // 主体大小：框内身高 0.45~0.75 最佳
        val h = local.map { subjectHeight(it) }.average().toFloat()
        score += 0.15f * (1f - min(distToInterval(h, 0.45f, 0.75f) / 0.30f, 1f))
        // 眼睛线（仅单人且主体够大时）：眼睛贴上三分线；否则中性分
        val eyeOk = if (local.size == 1 && h > 0.5f) {
            1f - min(abs(eyeY(local[0]) - 1f / 3f) / 0.12f, 1f)
        } else {
            0.5f
        }
        score += 0.15f * eyeOk
        // 脸部三分点：鼻尖到最近的上三分点交点的距离（合影取脸群重心）
        val fx = local.map { it[0][0] }.average().toFloat()
        val fy = local.map { it[0][1] }.average().toFloat()
        val dFace = min(
            hypot(fx - 1f / 3f, fy - 1f / 3f),
            hypot(fx - 2f / 3f, fy - 1f / 3f),
        )
        score += 0.15f * (1f - min(dFace / 0.28f, 1f))
        // 边缘余量：主体离框边太近显局促，理想余量 0.05~0.20
        score += 0.10f * (1f - min(distToInterval(edgeMargin(local), 0.05f, 0.20f) / 0.15f, 1f))

        // 切肩（斩首感）：打五折；切小腿：打七折
        if (local.any { shoulderOut(it) }) score *= 0.5f
        if (local.any { kneeIn(it) && ankleOut(it) }) score *= 0.7f
        return score.coerceIn(0f, 1f)
    }

    /**
     * 风景：地平线三分 ＋ 视觉主体（显著性重心）三分，各占一半。
     * 框里没地平线（纯细节）→ 该路中性；主体被裁出框 → 重罚（内容保留）。
     */
    private fun landscapeScore(box: RectF, horizonY: Float?, saliency: FloatArray?): Float {
        val hScore = if (horizonY == null) {
            0.5f
        } else {
            val bh = box.height().coerceAtLeast(1e-6f)
            val lh = (horizonY - box.top) / bh
            if (lh < 0.02f || lh > 0.98f) {
                0.45f
            } else {
                val d = min(abs(lh - 1f / 3f), abs(lh - 2f / 3f))
                (1f - min(d / 0.22f, 1f)).coerceIn(0f, 1f)
            }
        }
        val sScore = if (saliency == null) {
            0.5f
        } else {
            val bw = box.width().coerceAtLeast(1e-6f)
            val bh = box.height().coerceAtLeast(1e-6f)
            val lx = (saliency[0] - box.left) / bw
            val ly = (saliency[1] - box.top) / bh
            if (lx < 0f || lx > 1f || ly < 0f || ly > 1f) {
                0.25f
            } else {
                val d = minOf(
                    hypot(lx - 1f / 3f, ly - 1f / 3f),
                    hypot(lx - 2f / 3f, ly - 1f / 3f),
                    hypot(lx - 1f / 3f, ly - 2f / 3f),
                    hypot(lx - 2f / 3f, ly - 2f / 3f),
                )
                (1f - min(d / 0.35f, 1f)).coerceIn(0f, 1f)
            }
        }
        return 0.5f * hScore + 0.5f * sScore
    }

    // ── 以下几何量测（框局部坐标，框高=1）──

    private fun headTop(pts: List<FloatArray>): Float {
        val headWidth = abs(pts[7][0] - pts[8][0]).coerceAtLeast(0.02f)
        var faceTop = Float.MAX_VALUE
        for (i in 0..10) faceTop = minOf(faceTop, pts[i][1])
        return faceTop - headWidth * 0.6f
    }

    private fun inBox(p: FloatArray) = p[2] > 0.5f && p[0] in 0f..1f && p[1] in 0f..1f

    /** 鼻尖不在框内 = 切脸，否决 */
    private fun noseOut(pts: List<FloatArray>) = !inBox(pts[0])

    /** 肩膀出框 = 斩首式裁剪，重罚 */
    private fun shoulderOut(pts: List<FloatArray>) = !inBox(pts[11]) || !inBox(pts[12])

    private fun kneeIn(pts: List<FloatArray>) = inBox(pts[25]) || inBox(pts[26])

    private fun ankleOut(pts: List<FloatArray>) = !inBox(pts[27]) && !inBox(pts[28])

    /** 主体整体外框到候选框边的最小距离（局部坐标） */
    private fun edgeMargin(local: List<List<FloatArray>>): Float {
        var l = 1f
        var t = 1f
        var r = 0f
        var b = 0f
        for (pts in local) {
            for (p in pts) {
                if (p[2] <= 0.5f) continue
                l = minOf(l, p[0]); t = minOf(t, p[1])
                r = maxOf(r, p[0]); b = maxOf(b, p[1])
            }
        }
        if (r <= l) return 0f
        return maxOf(0f, min(min(l, t), min(1f - r, 1f - b)))
    }

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
