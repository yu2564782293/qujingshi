package com.qujingshi.aicamera

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NIMA 美学评分（on-device）：MobileNet 在 AVA 资料集（25 万张有人工美学评分的照片）
 * 上训练的模型，输出 1–10 分的分布，取期望值当分数。
 * 模型来源：idealo/image-quality-assessment 的预训练权重，转成 float16 TFLite。
 */
class AestheticScorer(context: Context) {

    private val interpreter: Interpreter
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(10) }
    private val pixels = IntArray(SIZE * SIZE)

    init {
        val bytes = context.assets.open("nima_aesthetic.tflite").readBytes()
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    /** 回传 1–10 的美感分数（10 档分布的期望值）。执行绪不安全，固定在同一条背景执行绪呼叫。 */
    fun score(bitmap: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        resized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        // MobileNet 前处理：x / 127.5 - 1
        for (c in pixels) {
            input.putFloat((c shr 16 and 0xFF) / 127.5f - 1f)
            input.putFloat((c shr 8 and 0xFF) / 127.5f - 1f)
            input.putFloat((c and 0xFF) / 127.5f - 1f)
        }
        input.rewind()
        interpreter.run(input, output)
        var s = 0f
        for (i in 0 until 10) s += (i + 1) * output[0][i]
        return s
    }

    fun close() = interpreter.close()

    private companion object {
        const val SIZE = 224
    }
}
