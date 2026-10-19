package com.qujingshi.aicamera

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AI 重构图：相册选一张照片 → 同一套 AI 构图引擎（场景路由＋密集候选＋
 * NIMA/几何融合）找出最好看的部分 → 框出来 → 一键裁剪存新图。
 */
class ReframeActivity : ComponentActivity() {

    private lateinit var reframeView: ReframeView
    private lateinit var txtStatus: TextView
    private lateinit var btnSave: TextView

    private var srcUri: Uri? = null
    private var bestBox: RectF? = null
    private var worker: ExecutorService? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                loadAndAnalyze(uri)
            } else if (srcUri == null) {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reframe)

        reframeView = findViewById(R.id.reframeView)
        txtStatus = findViewById(R.id.txtStatus)
        btnSave = findViewById(R.id.btnSave)

        findViewById<TextView>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnPick).setOnClickListener { launchPicker() }
        btnSave.setOnClickListener { saveCrop() }
        btnSave.isEnabled = false

        worker = Executors.newSingleThreadExecutor()

        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>("uri")
        if (uri != null) {
            loadAndAnalyze(uri)
        } else {
            launchPicker()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker?.shutdown()
    }

    private fun launchPicker() {
        pickImage.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun loadAndAnalyze(uri: Uri) {
        srcUri = uri
        bestBox = null
        btnSave.isEnabled = false
        txtStatus.setText(R.string.reframe_analyzing)
        reframeView.setPhoto(null, null)

        worker?.execute {
            try {
                // 分析用图：转正＋降采样到 1280（NIMA 只要 224，候选框 1280 精度足够）
                val bmp = decodeUpright(uri, 1280) ?: throw IllegalStateException("decode")
                val persons = detectPose(bmp)
                val scorer = AestheticScorer(this)
                val result = try {
                    FramingAdvisor(scorer).analyze(bmp, persons, singleShot = true)
                } finally {
                    scorer.close()
                }
                runOnUiThread {
                    if (result != null && OverlayView.isSignificantBox(result.bestBox)) {
                        bestBox = result.bestBox
                        reframeView.setPhoto(bmp, result.bestBox)
                        txtStatus.text =
                            getString(R.string.reframe_score_fmt).format(result.bestScore)
                        btnSave.isEnabled = true
                    } else {
                        // 已经很好或评不出来：原样展示
                        reframeView.setPhoto(bmp, null)
                        txtStatus.setText(R.string.reframe_good_already)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "reframe analyze failed", e)
                runOnUiThread { txtStatus.setText(R.string.reframe_failed) }
            }
        }
    }

    /** 原图转正＋降采样（只做展示和分析用，不动原文件） */
    private fun decodeUpright(uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            ExifInterface(pfd.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (deg == 0f) {
            bmp
        } else {
            val m = Matrix()
            m.postRotate(deg)
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                .also { bmp.recycle() }
        }
    }

    /** 静图姿态检测（IMAGE 模式，一次性）；失败回空，走风景/显著性分支 */
    private fun detectPose(bmp: Bitmap): List<List<FloatArray>> {
        return try {
            val base = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_lite.task")
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(4)
                .build()
            val landmarker = PoseLandmarker.createFromOptions(this, options)
            try {
                landmarker.detect(BitmapImageBuilder(bmp).build())
                    .landmarks()
                    .map { person ->
                        person.map { floatArrayOf(it.x(), it.y(), it.visibility().orElse(1f)) }
                    }
            } finally {
                landmarker.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "pose detect failed", e)
            emptyList()
        }
    }

    private fun saveCrop() {
        val uri = srcUri ?: return
        val box = bestBox ?: return
        btnSave.isEnabled = false
        txtStatus.setText(R.string.reframe_saving)
        worker?.execute {
            // 原图全分辨率裁剪（和拍照智能裁剪同一套）
            val cropped = ImageCropper.cropUpright(contentResolver, uri, box)
            if (cropped == null) {
                runOnUiThread {
                    Toast.makeText(this, R.string.reframe_failed, Toast.LENGTH_SHORT).show()
                    btnSave.isEnabled = true
                }
                return@execute
            }
            try {
                val name = "取景师_重构图_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(System.currentTimeMillis())
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/取景师")
                }
                val dst = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                )
                if (dst != null) {
                    contentResolver.openOutputStream(dst)?.use { out ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, R.string.reframe_saved, Toast.LENGTH_SHORT).show()
                    txtStatus.setText(R.string.reframe_saved)
                    btnSave.isEnabled = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "reframe save failed", e)
                runOnUiThread {
                    Toast.makeText(this, R.string.reframe_failed, Toast.LENGTH_SHORT).show()
                    btnSave.isEnabled = true
                }
            } finally {
                cropped.recycle()
            }
        }
    }

    private companion object {
        const val TAG = "Qujingshi"
    }
}
