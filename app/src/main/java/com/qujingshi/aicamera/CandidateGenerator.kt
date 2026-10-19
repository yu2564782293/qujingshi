package com.qujingshi.aicamera

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot

/**
 * L2 密集候选框生成器。所有坐标都是相对整帧的正规化 [0,1]（与分辨率无关）。
 *
 * 人像/合影：以主体（群）为锚——对 3x3 九宫格锚点 x 2 档缩放，生成"主体落在各构图点位"
 * 的候选框。框被钳制在画面内，去重避免钳制撞车。注意：大框可移动范围小是物理现实，
 * 候选框代表"移动相机可达的取景"，箭头会逐轮引导用户走过去。
 *
 * 风景：3x3 网格 + 中央拉近，共 10 个候选。
 */
object CandidateGenerator {

    private val ANCHORS = floatArrayOf(1f / 3f, 0.5f, 2f / 3f)

    /**
     * 四档缩放：0.86/0.72 微调，0.60/0.50 拉近看细节——
     * AI 自动取景要"把最好看的地方放大"，候选里必须有够紧的框。
     * 候选变多了，但几何快筛只取 Top3，NIMA 推理量不变。
     */
    private val SCALES = floatArrayOf(0.86f, 0.72f, 0.60f, 0.50f)

    /**
     * @param subjects 主体 bbox（正规化坐标），已按高度过滤。
     * @return 第一个元素恒为全帧 baseline。
     */
    fun generate(scene: SceneType, subjects: List<RectF>): List<RectF> {
        val out = mutableListOf(RectF(0f, 0f, 1f, 1f))
        when (scene) {
            SceneType.LANDSCAPE -> {
                for (cx in ANCHORS) {
                    for (cy in ANCHORS) {
                        addBox(out, cx, cy, 0.86f)
                    }
                }
                addBox(out, 0.5f, 0.5f, 0.72f) // closer
                addBox(out, 0.5f, 0.5f, 0.58f) // 拉近看细节
            }
            SceneType.PORTRAIT, SceneType.GROUP -> {
                if (subjects.isEmpty()) return out
                val g = union(subjects)
                val gcx = g.centerX()
                val gcy = g.centerY()
                for (s in SCALES) {
                    for (ax in ANCHORS) {
                        for (ay in ANCHORS) {
                            // 让主体中心落到锚点 (ax, ay)：框中心 = 主体中心 - (锚点-画面中心)*框边长
                            val bcx = gcx - (ax - 0.5f) * s
                            val bcy = gcy - (ay - 0.5f) * s
                            addBox(out, bcx, bcy, s)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun addBox(out: MutableList<RectF>, bcx0: Float, bcy0: Float, s: Float) {
        val bcx = bcx0.coerceIn(s / 2f, 1f - s / 2f)
        val bcy = bcy0.coerceIn(s / 2f, 1f - s / 2f)
        // 去重：中心和尺寸都接近才算重复（baseline 全帧不参与去重）
        val dup = out.any {
            hypot(it.centerX() - bcx, it.centerY() - bcy) < DEDUP_DIST &&
                abs(it.width() - s) < 0.05f
        }
        if (!dup) out.add(RectF(bcx - s / 2f, bcy - s / 2f, bcx + s / 2f, bcy + s / 2f))
    }

    private fun union(boxes: List<RectF>): RectF {
        var l = 1f
        var t = 1f
        var r = 0f
        var b = 0f
        for (bx in boxes) {
            l = minOf(l, bx.left)
            t = minOf(t, bx.top)
            r = maxOf(r, bx.right)
            b = maxOf(b, bx.bottom)
        }
        return RectF(l, t, r, b)
    }

    private const val DEDUP_DIST = 0.02f
}
