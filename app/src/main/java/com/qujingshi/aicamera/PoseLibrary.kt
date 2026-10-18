package com.qujingshi.aicamera

import androidx.annotation.StringRes

/**
 * 内建姿势库：推荐姿势的虚线人形。
 *
 * 每个姿势用 14 个关键点描述，座标是 0..1 的正规化空间（x 向右、y 向下），
 * 叠图时会等比缩放到人物（或画面中央）的位置。
 */
data class GuidePose(
    @StringRes val nameRes: Int,
    /** 14 点：head, neck, lSho, rSho, lElb, rElb, lWri, rWri, lHip, rHip, lKnee, rKnee, lAnk, rAnk */
    val points: List<Pair<Float, Float>>,
)

object PoseLibrary {

    // points 索引
    const val HEAD = 0
    const val NECK = 1
    const val L_SHO = 2
    const val R_SHO = 3
    const val L_ELB = 4
    const val R_ELB = 5
    const val L_WRI = 6
    const val R_WRI = 7
    const val L_HIP = 8
    const val R_HIP = 9
    const val L_KNEE = 10
    const val R_KNEE = 11
    const val L_ANK = 12
    const val R_ANK = 13

    /** 骨架连线（头用圆圈另外画，不在这里） */
    val EDGES = listOf(
        NECK to L_SHO, NECK to R_SHO,
        L_SHO to L_ELB, L_ELB to L_WRI,
        R_SHO to R_ELB, R_ELB to R_WRI,
        L_SHO to L_HIP, R_SHO to R_HIP,
        L_HIP to R_HIP,
        L_HIP to L_KNEE, L_KNEE to L_ANK,
        R_HIP to R_KNEE, R_KNEE to R_ANK,
    )

    val POSES = listOf(
        GuidePose(
            R.string.pose_stand,
            listOf(
                0.50f to 0.06f, 0.50f to 0.17f,
                0.38f to 0.20f, 0.62f to 0.20f,
                0.34f to 0.36f, 0.66f to 0.36f,
                0.32f to 0.51f, 0.68f to 0.51f,
                0.42f to 0.53f, 0.58f to 0.53f,
                0.41f to 0.75f, 0.59f to 0.75f,
                0.40f to 0.97f, 0.60f to 0.97f,
            ),
        ),
        GuidePose(
            R.string.pose_hand_on_hip,
            listOf(
                0.50f to 0.06f, 0.50f to 0.17f,
                0.38f to 0.20f, 0.62f to 0.20f,
                0.34f to 0.36f, 0.74f to 0.34f,   // 右肘外张
                0.32f to 0.51f, 0.60f to 0.52f,   // 右手叉在髋上
                0.42f to 0.53f, 0.58f to 0.53f,
                0.40f to 0.75f, 0.60f to 0.75f,
                0.38f to 0.97f, 0.62f to 0.97f,
            ),
        ),
        GuidePose(
            R.string.pose_arms_up,
            listOf(
                0.50f to 0.10f, 0.50f to 0.21f,
                0.38f to 0.24f, 0.62f to 0.24f,
                0.28f to 0.14f, 0.72f to 0.14f,   // 手肘抬高
                0.22f to 0.02f, 0.78f to 0.02f,   // 手腕举过头，开成 V
                0.42f to 0.55f, 0.58f to 0.55f,
                0.41f to 0.76f, 0.59f to 0.76f,
                0.40f to 0.97f, 0.60f to 0.97f,
            ),
        ),
        GuidePose(
            R.string.pose_lean,
            listOf(
                0.40f to 0.07f, 0.42f to 0.18f,   // 上身往左斜
                0.31f to 0.21f, 0.53f to 0.21f,
                0.28f to 0.37f, 0.60f to 0.35f,
                0.30f to 0.52f, 0.63f to 0.48f,
                0.46f to 0.54f, 0.60f to 0.54f,
                0.47f to 0.76f, 0.62f to 0.75f,
                0.44f to 0.97f, 0.72f to 0.95f,   // 脚交叉斜撑
            ),
        ),
    )
}
