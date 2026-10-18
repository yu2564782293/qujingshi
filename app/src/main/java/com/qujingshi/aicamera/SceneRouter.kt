package com.qujingshi.aicamera

/**
 * 取景场景路由：决定候选框策略与评估频率。
 * PORTRAIT（单人）/ GROUP（合影）：主体锚定候选框，每 2 秒评估。
 * LANDSCAPE（风景）：3x3 网格候选，每 3 秒评估（NIMA 推理量较大）。
 */
enum class SceneType { PORTRAIT, GROUP, LANDSCAPE }

class SceneRouter {

    var current: SceneType = SceneType.LANDSCAPE
        private set

    private var pending: SceneType? = null
    private var streak = 0

    /** 连续 2 次评估同场景才切换，避免人物进出画面时来回跳 */
    fun update(subjectCount: Int): SceneType {
        val target = when {
            subjectCount >= 2 -> SceneType.GROUP
            subjectCount == 1 -> SceneType.PORTRAIT
            else -> SceneType.LANDSCAPE
        }
        if (target == current) {
            pending = null
            streak = 0
            return current
        }
        if (target == pending) {
            streak++
        } else {
            pending = target
            streak = 1
        }
        if (streak >= STABLE_ROUNDS) {
            current = target
            pending = null
            streak = 0
        }
        return current
    }

    private companion object {
        const val STABLE_ROUNDS = 2
    }
}
