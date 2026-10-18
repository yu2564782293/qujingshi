package com.qujingshi.aicamera

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AI 调色引擎（Image-Adaptive 3D LUT，Zeng et al. TPAMI 2020 的预训练模型）：
 * 小 CNN 看一眼画面缩图 → 输出 3 个混合权重 → 混出这一幕专属的 33³ 3D LUT。
 * 模型在 FiveK（摄影师修图前后对照）上训练，学的是「专业修图师遇到这种画面会怎么调」。
 * 转换脚本：tools/convert_lut3d.py。
 */
class LutColorEngine(context: Context) {

    private val interpreter: Interpreter

    /** 3 个基底 LUT，layout [lut][c][b][g][r]，dim=33（见转换脚本说明） */
    private val basis: FloatArray
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(3) }
    private val pixels = IntArray(SIZE * SIZE)

    init {
        val modelBytes = context.assets.open("lut_classifier.tflite").readBytes()
        val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        model.put(modelBytes)
        model.rewind()
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))

        val lutBytes = context.assets.open("luts_basis.bin").readBytes()
        basis = FloatArray(lutBytes.size / 4)
        ByteBuffer.wrap(lutBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(basis)
        require(basis.size == 3 * 3 * VOL) { "unexpected luts_basis.bin size ${basis.size}" }
    }

    /** 预测这一幕的 LUT 混合权重。跟 AestheticScorer 共用同一条背景执行绪。 */
    fun predictWeights(frame: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(frame, SIZE, SIZE, true)
        resized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        // 前处理 = ToTensor：RGB 0..1
        for (c in pixels) {
            input.putFloat((c shr 16 and 0xFF) / 255f)
            input.putFloat((c shr 8 and 0xFF) / 255f)
            input.putFloat((c and 0xFF) / 255f)
        }
        input.rewind()
        interpreter.run(input, output)
        return output[0].copyOf()
    }

    /** 混合基底 LUT，输出 Media3 SingleColorLut 要的 cube[R][G][B]（ARGB_8888） */
    fun buildCube(w: FloatArray): Array<Array<IntArray>> {
        val cube = Array(DIM) { Array(DIM) { IntArray(DIM) } }
        for (b in 0 until DIM) {
            for (g in 0 until DIM) {
                for (r in 0 until DIM) {
                    val i = b * DIM * DIM + g * DIM + r
                    val rr = blend(0, i, w)
                    val gg = blend(1, i, w)
                    val bb = blend(2, i, w)
                    cube[r][g][b] = -0x1000000 or
                        (to255(rr) shl 16) or (to255(gg) shl 8) or to255(bb)
                }
            }
        }
        return cube
    }

    private fun blend(c: Int, i: Int, w: FloatArray): Float =
        w[0] * basis[c * VOL + i] +
            w[1] * basis[(3 + c) * VOL + i] +
            w[2] * basis[(6 + c) * VOL + i]

    private fun to255(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    fun close() = interpreter.close()

    private companion object {
        const val SIZE = 256
        const val DIM = 33
        const val VOL = DIM * DIM * DIM
    }
}
