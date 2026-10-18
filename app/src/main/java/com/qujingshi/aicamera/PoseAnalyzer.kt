package com.qujingshi.aicamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * 把 CameraX 的影格喂给 MediaPipe Pose Landmarker（LIVE_STREAM 模式），
 * 侦测结果以「正规化座标 + 影像尺寸」回呼给 UI 执行绪。
 */
class PoseAnalyzer(
    context: Context,
    private val onResult: (persons: List<List<FloatArray>>, imageWidth: Int, imageHeight: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    /** 前镜头要镜像，让座标跟预览画面一致；由 MainActivity 依镜头方向设定 */
    @Volatile
    var mirror: Boolean = false

    /** 最近一张转正后的影格，给 AI 取景建议（NIMA）取样用 */
    @Volatile
    var latestFrame: Bitmap? = null
        private set

    private val landmarker: PoseLandmarker

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_lite.task")
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            // 支援多人合照；路人由 OverlayView 用相对大小过滤
            .setNumPoses(4)
            .setResultListener { result: PoseLandmarkerResult, input ->
                // 每点 [x, y, visibility]，构图规则要靠 visibility 判断肢体有没有入镜
                val persons = result.landmarks().map { person ->
                    person.map { floatArrayOf(it.x(), it.y(), it.visibility().orElse(1f)) }
                }
                onResult(persons, input.width, input.height)
            }
            .setErrorListener { e -> Log.e(TAG, "pose landmarker error", e) }
            .build()
        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    override fun analyze(imageProxy: ImageProxy) {
        imageProxy.use { proxy ->
            // OUTPUT_IMAGE_FORMAT_RGBA_8888：单一 plane 直接拷进 Bitmap
            val raw = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(proxy.planes[0].buffer)

            val matrix = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                if (mirror) postScale(-1f, 1f, proxy.width / 2f, proxy.height / 2f)
            }
            val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            latestFrame = upright

            landmarker.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
        }
    }

    fun close() = landmarker.close()

    private companion object {
        const val TAG = "PoseAnalyzer"
    }
}
