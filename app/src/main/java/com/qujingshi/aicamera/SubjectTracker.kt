package com.qujingshi.aicamera

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 跨帧追踪 + 主体判定：分辨「要拍的人」跟「路过的路人」。
 *
 * 单看大小会误判（拉远景时主体很小、路人可能离镜头近），所以用综合分数：
 * 大小 40% + 停留稳定度 30%（站定摆姿势 vs 走动路过）+ 面向镜头 20% + 靠近画面中心 10%。
 * 使用者也可以点击画面直接锁定主体，锁定期间其他人一律当路人。
 */
class SubjectTracker {

    class Track(
        val id: Int,
        var pts: List<FloatArray>,
        var bbox: FloatArray,
    ) {
        var cx = (bbox[0] + bbox[2]) / 2f
        var cy = (bbox[1] + bbox[3]) / 2f
        var height = bbox[3] - bbox[1]
        var age = 1          // 连续出现的侦测帧数
        var missed = 0
        var motionEma = 0f   // 每帧移动量（以自身身高正规化）的指数平均
        var score = 0f
    }

    private var nextId = 1
    private val tracks = mutableListOf<Track>()

    /** 锁定的主体 id；null = 自动判定 */
    var lockedId: Int? = null
        private set

    /** 喂入本帧所有侦测到的人（view 座标），回传判定为主体的 track。 */
    fun update(persons: List<List<FloatArray>>, w: Float, h: Float): List<Track> {
        val dets = persons.filter { it.size >= 33 }
        val detBoxes = dets.map { bboxOf(it) }
        val used = BooleanArray(dets.size)

        // 贪婪配对：每条既有 track 找最近的侦测
        for (t in tracks) {
            var best = -1
            var bestDist = Float.MAX_VALUE
            for (i in dets.indices) {
                if (used[i]) continue
                val dcx = (detBoxes[i][0] + detBoxes[i][2]) / 2f
                val dcy = (detBoxes[i][1] + detBoxes[i][3]) / 2f
                val dist = hypot((dcx - t.cx).toDouble(), (dcy - t.cy).toDouble()).toFloat()
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            // 允许的帧间位移：半个身高
            if (best >= 0 && bestDist < t.height.coerceAtLeast(h * 0.05f) * 0.5f) {
                used[best] = true
                val box = detBoxes[best]
                val dcx = (box[0] + box[2]) / 2f
                val dcy = (box[1] + box[3]) / 2f
                val move = hypot((dcx - t.cx).toDouble(), (dcy - t.cy).toDouble()).toFloat() /
                    t.height.coerceAtLeast(1f)
                t.motionEma += 0.2f * (move - t.motionEma)
                t.pts = dets[best]
                t.bbox = box
                t.cx = dcx
                t.cy = dcy
                t.height = box[3] - box[1]
                t.age++
                t.missed = 0
            } else {
                t.missed++
            }
        }
        tracks.removeAll { it.missed > MAX_MISSED }
        for (i in dets.indices) {
            if (!used[i]) tracks.add(Track(nextId++, dets[i], detBoxes[i]))
        }
        // 锁定的人离开画面太久就自动解锁
        if (lockedId != null && tracks.none { it.id == lockedId }) lockedId = null

        return selectSubjects(w, h)
    }

    private fun selectSubjects(w: Float, h: Float): List<Track> {
        val visible = tracks.filter { it.missed == 0 }
        if (visible.isEmpty()) return emptyList()

        lockedId?.let { id ->
            return visible.filter { it.id == id }
        }

        val tallest = visible.maxOf { it.height }.coerceAtLeast(1f)
        for (t in visible) {
            val size = t.height / tallest
            // 站得够久（约 1 秒起跳满分）且不太会动 → 稳定
            val stability = min(t.age / 20f, 1f) * (1f - min(t.motionEma / 0.05f, 1f))
            val facing = if (isFacingCamera(t.pts)) 1f else 0.3f
            val centerProx = 1f - min(abs(t.cx - w / 2f) / (w / 2f), 1f)
            t.score = 0.4f * size + 0.3f * stability + 0.2f * facing + 0.1f * centerProx
        }
        val best = visible.maxOf { it.score }
        // 分数要接近最高者，且不能小到只是杂讯（门槛放低到 8%，远景主体才留得住）
        return visible.filter { it.score >= best * 0.65f && it.height >= h * 0.08f }
    }

    /** 鼻子落在双耳之间 → 大致面向镜头；侧脸或背影时鼻子会偏出去 */
    private fun isFacingCamera(pts: List<FloatArray>): Boolean {
        val noseX = pts[0][0]
        val lo = min(pts[7][0], pts[8][0])
        val hi = max(pts[7][0], pts[8][0])
        return noseX in lo..hi
    }

    /**
     * 点击处理：点到人 → 锁定（再点同一人解除）；点空白处 → 解除锁定。
     * @return true=锁定了某人、false=解除了锁定、null=没点到任何东西也没锁定可解
     */
    fun toggleLockAt(x: Float, y: Float): Boolean? {
        val hit = tracks.filter { it.missed == 0 }.minByOrNull { t ->
            if (containsWithMargin(t.bbox, x, y)) {
                hypot((x - t.cx).toDouble(), (y - t.cy).toDouble()).toFloat()
            } else {
                Float.MAX_VALUE
            }
        }?.takeIf { containsWithMargin(it.bbox, x, y) }

        return when {
            hit != null && lockedId == hit.id -> {
                lockedId = null
                false
            }
            hit != null -> {
                lockedId = hit.id
                true
            }
            lockedId != null -> {
                lockedId = null
                false
            }
            else -> null
        }
    }

    fun lockedTrack(): Track? = tracks.find { it.id == lockedId && it.missed == 0 }

    /** 切换镜头时呼叫：画面内容完全换掉，旧 track 全部作废 */
    fun reset() {
        tracks.clear()
        lockedId = null
    }

    private fun containsWithMargin(b: FloatArray, x: Float, y: Float): Boolean {
        val margin = (b[3] - b[1]) * 0.15f
        return x in (b[0] - margin)..(b[2] + margin) && y in (b[1] - margin)..(b[3] + margin)
    }

    private fun bboxOf(pts: List<FloatArray>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        for (i in BBOX_POINTS) {
            minX = min(minX, pts[i][0]); maxX = max(maxX, pts[i][0])
            minY = min(minY, pts[i][1]); maxY = max(maxY, pts[i][1])
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private companion object {
        const val MAX_MISSED = 8
        val BBOX_POINTS = listOf(0, 11, 12, 15, 16, 23, 24, 27, 28, 29, 30)
    }
}
