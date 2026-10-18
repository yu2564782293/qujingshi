package com.qujingshi.aicamera

import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment

/**
 * 色彩风格：GPU 效果（Media3）同时套在预览与拍出的照片上。
 * AUTO 不是一种风格，而是依场景（人像／风景／夜景）自动挑一种。
 */
enum class ColorMode(@StringRes val nameRes: Int) {
    AUTO(R.string.color_auto),
    AI(R.string.color_ai),
    PORTRAIT(R.string.color_portrait),
    LANDSCAPE(R.string.color_landscape),
    FOOD(R.string.color_food),
    NIGHT(R.string.color_night),
    MONO(R.string.color_mono),
    OFF(R.string.color_off),
}

/** 一帧画面的色彩统计（值皆 0..1，warmth 为 -1..1，正 = 偏暖） */
data class FrameStats(
    val luma: Float,
    val saturation: Float,
    val contrast: Float,
    val warmth: Float,
)

object ColorLooks {

    /** 各风格的基准参数；自适应是在这些基准上调整「强度」 */
    private class Params(
        var sat: Float = 0f,        // HslAdjustment 饱和度增减（百分比）
        var contrast: Float = 0f,   // Contrast（-1..1）
        var red: Float = 1f,
        var green: Float = 1f,
        var blue: Float = 1f,
        var brightness: Float = 0f, // Brightness（-1..1）
    )

    private fun base(mode: ColorMode): Params = when (mode) {
        ColorMode.PORTRAIT -> Params(sat = 6f, contrast = 0.05f, red = 1.05f, blue = 0.95f)
        ColorMode.LANDSCAPE -> Params(sat = 18f, contrast = 0.10f)
        ColorMode.FOOD -> Params(sat = 15f, contrast = 0.06f, red = 1.08f, green = 1.02f, blue = 0.90f)
        ColorMode.NIGHT -> Params(contrast = 0.12f, red = 0.97f, blue = 1.05f, brightness = 0.06f)
        ColorMode.MONO -> Params(sat = -100f, contrast = 0.12f)
        ColorMode.AUTO, ColorMode.AI, ColorMode.OFF -> Params()
    }

    /**
     * 统计自适应：看这一帧本来的样子调整强度——
     * 本来就鲜艳就少加饱和、平淡就多加；已经偏暖就收敛暖色偏移；夜景提亮看实际暗度。
     */
    private fun adapt(p: Params, s: FrameStats) {
        if (p.sat > 0f) {
            val f = (1f + (0.35f - s.saturation) / 0.35f * 0.8f).coerceIn(0.3f, 1.6f)
            p.sat *= f
        }
        if (p.contrast > 0f) {
            val f = (1f + (0.18f - s.contrast) / 0.18f * 0.8f).coerceIn(0.3f, 1.6f)
            p.contrast = (p.contrast * f).coerceAtMost(0.25f)
        }
        val warmShift = p.red - 1f
        if (warmShift > 0f) {
            val f = when {
                s.warmth > 0.06f -> 0.4f   // 画面已经暖了，收敛
                s.warmth < -0.06f -> 1.3f  // 偏冷，加强
                else -> 1f
            }
            p.red = 1f + warmShift * f
            p.blue = 1f - (1f - p.blue) * f
        }
        if (p.brightness > 0f) {
            val f = ((0.25f - s.luma) / 0.25f).coerceIn(0.5f, 1.5f)
            p.brightness *= f
        }
    }

    @OptIn(UnstableApi::class)
    fun effectsFor(mode: ColorMode, stats: FrameStats? = null): List<Effect> {
        val p = base(mode)
        // AI 调色由 LutColorEngine 走另一条路（SingleColorLut），不在这里处理
        if (mode == ColorMode.AUTO || mode == ColorMode.AI || mode == ColorMode.OFF) {
            return emptyList()
        }
        if (stats != null) adapt(p, stats)

        val fx = mutableListOf<Effect>()
        if (p.red != 1f || p.green != 1f || p.blue != 1f) {
            fx += RgbAdjustment.Builder()
                .setRedScale(p.red).setGreenScale(p.green).setBlueScale(p.blue).build()
        }
        if (p.sat > 0.5f || p.sat < -0.5f) {
            fx += HslAdjustment.Builder().adjustSaturation(p.sat.coerceIn(-100f, 100f)).build()
        }
        if (p.contrast != 0f) fx += Contrast(p.contrast)
        if (p.brightness != 0f) fx += Brightness(p.brightness)
        return fx
    }
}
