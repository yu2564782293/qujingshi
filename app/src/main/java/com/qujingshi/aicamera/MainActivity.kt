package com.qujingshi.aicamera

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Outline
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.media3.effect.Media3Effect
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var btnGallery: ImageButton
    private lateinit var txtPoseName: TextView
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    /** 目前荧幕方向（0/90/180/270），给水平仪补偿与 targetRotation 用 */
    private var displayRotationDeg = 0

    private lateinit var analysisExecutor: ExecutorService
    private var poseAnalyzer: PoseAnalyzer? = null

    /** -1 = 姿势引导关闭；其余是 PoseLibrary.POSES 的索引 */
    private var poseIndex = -1

    private var latestPhotoUri: Uri? = null

    // ── 色彩风格 ──
    private lateinit var txtColorName: TextView
    private var media3Effect: Media3Effect? = null
    private var colorSupported = true       // 绑定失败会降级成 false，并明确告知使用者
    private var colorMode = ColorMode.AUTO
    private var appliedLook = ColorMode.OFF   // AUTO 模式下实际套用的风格
    private var candidateLook: ColorMode? = null
    private var lookStreak = 0

    @Volatile
    private var lastLuma = 0.5f

    @Volatile
    private var lastStats: FrameStats? = null
    private var appliedStats: FrameStats? = null
    private var adaptCounter = 0

    // ── AI 取景建议（NIMA）＋ AI 调色（adaptive 3D LUT）──
    private lateinit var txtAiScore: TextView
    private var framingAdvisor: FramingAdvisor? = null
    private var lutEngine: LutColorEngine? = null
    private var lastLutWeights: FloatArray? = null
    private var lutBusy = false
    private var aiExecutor: ExecutorService? = null
    private var aiBusy = false
    private val aiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val aiTick = object : Runnable {
        override fun run() {
            scoreCurrentFraming()
            updateAiColor()
            aiHandler.postDelayed(this, 2000)
        }
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // 低通滤波后的重力向量，用来算侧倾角，避免水平仪抖动
    private var gx = 0f
    private var gy = 9.8f

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.msg_need_camera_permission, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        btnGallery = findViewById(R.id.btnGallery)
        txtPoseName = findViewById(R.id.txtPoseName)

        findViewById<ImageButton>(R.id.btnShutter).setOnClickListener { takePhoto() }
        findViewById<ImageButton>(R.id.btnSwitchCamera).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            overlayView.resetTracking()
            startCamera()
        }
        findViewById<ImageButton>(R.id.btnPose).setOnClickListener { cyclePose() }
        findViewById<ImageButton>(R.id.btnGuideMode).setOnClickListener { toggleGuideMode() }

        // 圆形缩图
        btnGallery.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        btnGallery.clipToOutline = true
        btnGallery.setOnClickListener { openLatestPhoto() }

        txtColorName = findViewById(R.id.txtColorName)
        findViewById<ImageButton>(R.id.btnColor).setOnClickListener { cycleColorMode() }
        updateColorLabel()

        analysisExecutor = Executors.newSingleThreadExecutor()
        poseAnalyzer = PoseAnalyzer(
            this,
            onResult = { landmarks, imgW, imgH ->
                runOnUiThread {
                    overlayView.setDetectedPose(landmarks, imgW, imgH)
                    updateAutoLook(landmarks.isNotEmpty())
                    maybeReadapt()
                }
            },
            onStats = {
                lastStats = it
                lastLuma = it.luma
            },
        )

        // AI 取景建议：模型载入失败（例如 assets 缺档）就整组停用，App 照常运作
        txtAiScore = findViewById(R.id.txtAiScore)
        runCatching { AestheticScorer(this) }.onSuccess { scorer ->
            framingAdvisor = FramingAdvisor(scorer)
        }
        lutEngine = runCatching { LutColorEngine(this) }.getOrNull()
        if (framingAdvisor != null || lutEngine != null) {
            aiExecutor = Executors.newSingleThreadExecutor()
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        loadLatestThumbnail()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDisplayRotation()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (framingAdvisor != null || lutEngine != null) aiHandler.postDelayed(aiTick, 2000)
    }

    /** 转向由 configChanges 自己接手：更新各 use case 的 targetRotation 与水平仪补偿 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDisplayRotation()
        overlayView.resetTracking() // view 长宽对调，旧的追踪座标全部失效
    }

    private fun refreshDisplayRotation() {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        displayRotationDeg = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        imageCapture?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        aiHandler.removeCallbacks(aiTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        poseAnalyzer?.close()
        analysisExecutor.shutdown()
        aiExecutor?.shutdown()
        lutEngine?.close()
    }

    /** 每 2 秒抓最新影格，背景算美感分数与取景建议 */
    private fun scoreCurrentFraming() {
        val advisor = framingAdvisor ?: return
        val frame = poseAnalyzer?.latestFrame ?: return
        if (aiBusy) return
        aiBusy = true
        aiExecutor?.execute {
            val result = runCatching { advisor.analyze(frame) }.getOrNull()
            runOnUiThread {
                aiBusy = false
                if (result != null) {
                    txtAiScore.visibility = android.view.View.VISIBLE
                    txtAiScore.text =
                        getString(R.string.ai_score_fmt).format(result.currentScore) +
                        getString(result.direction.textRes)
                }
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            imageAnalysis = analysis
            poseAnalyzer?.let { analyzer ->
                analyzer.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
                analysis.setAnalyzer(analysisExecutor, analyzer)
            }

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val capture = imageCapture!!

            fun bindWithEffect(targets: Int): Boolean {
                val effect = runCatching {
                    Media3Effect(this, targets, ContextCompat.getMainExecutor(this)) {
                        runOnUiThread { onColorPipelineError() }
                    }
                }.getOrNull() ?: return false
                return runCatching {
                    provider.unbindAll()
                    val group = UseCaseGroup.Builder()
                        .addUseCase(preview).addUseCase(capture).addUseCase(analysis)
                        .addEffect(effect)
                        .build()
                    provider.bindToLifecycle(this, selector, group)
                }.onSuccess { media3Effect = effect }.isSuccess
            }

            // 分级降级：预览＋拍照都有色彩 → 只有预览有 → 完全没有（都要让使用者知道）
            var bound = false
            if (colorSupported) {
                bound = bindWithEffect(CameraEffect.PREVIEW or CameraEffect.IMAGE_CAPTURE)
                if (!bound && bindWithEffect(CameraEffect.PREVIEW)) {
                    bound = true
                    Toast.makeText(this, R.string.msg_color_preview_only, Toast.LENGTH_LONG).show()
                }
            }
            if (!bound) {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture, analysis)
                if (colorSupported) disableColor(R.string.msg_color_unsupported)
            } else {
                // 重新绑定后 effect 是新实例，把目前的色彩状态套回去
                when {
                    colorMode == ColorMode.AI -> {
                        lastLutWeights = null
                        updateAiColor()
                    }
                    appliedLook != ColorMode.OFF -> applyLook(appliedLook)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onColorPipelineError() {
        if (colorSupported) disableColor(R.string.msg_color_pipeline_error)
    }

    private fun disableColor(msgRes: Int) {
        colorSupported = false
        media3Effect = null
        findViewById<ImageButton>(R.id.btnColor).visibility = View.GONE
        txtColorName.visibility = View.GONE
        Toast.makeText(this, msgRes, Toast.LENGTH_LONG).show()
    }

    // ── 色彩风格 ──

    private fun cycleColorMode() {
        val modes = ColorMode.entries
        var next = modes[(colorMode.ordinal + 1) % modes.size]
        // 引擎没载成功就跳过 AI 调色
        if (next == ColorMode.AI && lutEngine == null) {
            next = modes[(next.ordinal + 1) % modes.size]
        }
        colorMode = next
        candidateLook = null
        lookStreak = 0
        when (colorMode) {
            ColorMode.AUTO -> appliedLook = ColorMode.OFF // 让下一帧的自动判断立刻重套
            ColorMode.AI -> {
                appliedLook = ColorMode.AI
                lastLutWeights = null // 强制重新预测
                updateAiColor()
            }
            else -> applyLook(colorMode)
        }
        updateColorLabel()
    }

    /** AI 调色：预测这一幕的 LUT 权重，权重明显变了才重建并套用 LUT */
    private fun updateAiColor() {
        if (colorMode != ColorMode.AI) return
        val engine = lutEngine ?: return
        val frame = poseAnalyzer?.latestFrame ?: return
        if (lutBusy) return
        lutBusy = true
        aiExecutor?.execute {
            val built = runCatching {
                val w = engine.predictWeights(frame)
                val old = lastLutWeights
                val changed = old == null ||
                    kotlin.math.abs(w[0] - old[0]) > 0.05f ||
                    kotlin.math.abs(w[1] - old[1]) > 0.05f ||
                    kotlin.math.abs(w[2] - old[2]) > 0.05f
                if (changed) w to engine.buildCube(w) else null
            }.getOrNull()
            runOnUiThread {
                lutBusy = false
                if (built != null && colorMode == ColorMode.AI) {
                    lastLutWeights = built.first
                    media3Effect?.setEffects(
                        listOf(
                            androidx.media3.effect.SingleColorLut.createFromCube(built.second),
                        ),
                    )
                }
            }
        }
    }

    /** AUTO 模式：暗 → 夜景；有人 → 人像；否则风景。连续 30 帧（约 1–2 秒）才切换，避免跳动。 */
    private fun updateAutoLook(personsPresent: Boolean) {
        if (colorMode != ColorMode.AUTO) return
        val target = when {
            lastLuma < 0.22f -> ColorMode.NIGHT
            personsPresent -> ColorMode.PORTRAIT
            else -> ColorMode.LANDSCAPE
        }
        if (target == appliedLook) {
            candidateLook = null
            lookStreak = 0
            return
        }
        if (target == candidateLook) lookStreak++ else { candidateLook = target; lookStreak = 1 }
        if (lookStreak >= 30) {
            applyLook(target)
            updateColorLabel()
        }
    }

    private fun applyLook(look: ColorMode) {
        appliedLook = look
        appliedStats = lastStats
        media3Effect?.setEffects(ColorLooks.effectsFor(look, lastStats))
    }

    /**
     * 统计自适应的重套：画面色彩「本质」变了（换场景、光线变化）才重算强度，
     * 每 60 帧（约 2–4 秒）最多检查一次，微小变化不动作，避免预览闪烁。
     */
    private fun maybeReadapt() {
        if (colorMode == ColorMode.AI) return
        if (appliedLook == ColorMode.OFF || media3Effect == null) return
        if (++adaptCounter < 60) return
        adaptCounter = 0
        val now = lastStats ?: return
        val old = appliedStats
        val changed = old == null ||
            kotlin.math.abs(now.saturation - old.saturation) > 0.06f ||
            kotlin.math.abs(now.contrast - old.contrast) > 0.04f ||
            kotlin.math.abs(now.warmth - old.warmth) > 0.04f ||
            kotlin.math.abs(now.luma - old.luma) > 0.08f
        if (changed) applyLook(appliedLook)
    }

    private fun updateColorLabel() {
        txtColorName.text = if (colorMode == ColorMode.AUTO) {
            getString(R.string.color_auto_prefix) + getString(appliedLook.nameRes)
        } else {
            getString(colorMode.nameRes)
        }
    }

    private fun cyclePose() {
        poseIndex = if (poseIndex >= PoseLibrary.POSES.lastIndex) -1 else poseIndex + 1
        if (poseIndex < 0) {
            overlayView.guidePose = null
            txtPoseName.setText(R.string.pose_off)
        } else {
            val pose = PoseLibrary.POSES[poseIndex]
            overlayView.guidePose = pose
            txtPoseName.setText(pose.nameRes)
        }
        txtPoseName.visibility = View.VISIBLE
        txtPoseName.removeCallbacks(hidePoseName)
        // 「关」的提示 2 秒后淡出；选中的姿势名称留在画面上
        if (poseIndex < 0) txtPoseName.postDelayed(hidePoseName, 2000)
    }

    private val hidePoseName = Runnable { txtPoseName.visibility = View.GONE }

    private fun toggleGuideMode() {
        val newMode = if (overlayView.guideMode == OverlayView.GuideMode.MOVE_CAMERA) {
            OverlayView.GuideMode.MOVE_SUBJECT
        } else {
            OverlayView.GuideMode.MOVE_CAMERA
        }
        overlayView.guideMode = newMode
        // 移人模式没有虚线人形就没东西可对齐：自动开第一个姿势
        if (newMode == OverlayView.GuideMode.MOVE_SUBJECT && poseIndex < 0) {
            poseIndex = 0
            overlayView.guidePose = PoseLibrary.POSES[0]
        }
        txtPoseName.setText(
            if (newMode == OverlayView.GuideMode.MOVE_SUBJECT) {
                R.string.guide_mode_subject
            } else {
                R.string.guide_mode_camera
            },
        )
        txtPoseName.visibility = View.VISIBLE
        txtPoseName.removeCallbacks(hidePoseName)
        txtPoseName.postDelayed(hidePoseName, 2500)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val name = "取景师_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/取景师")
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()

        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, R.string.msg_saved, Toast.LENGTH_SHORT).show()
                    result.savedUri?.let { showThumbnail(it) }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, R.string.msg_save_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            },
        )
    }

    /** 开 App 时捞本 App 资料夹里最新一张照片当缩图（重灌后捞不到就留空） */
    private fun loadLatestThumbnail() {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/取景师%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                showThumbnail(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                )
            }
        }
    }

    private fun showThumbnail(uri: Uri) {
        latestPhotoUri = uri
        runCatching {
            val thumb = contentResolver.loadThumbnail(uri, Size(128, 128), null)
            btnGallery.setImageBitmap(thumb)
        }
    }

    private fun openLatestPhoto() {
        val uri = latestPhotoUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // 低通滤波：只留重力成分
        val alpha = 0.15f
        gx += alpha * (event.values[0] - gx)
        gy += alpha * (event.values[1] - gy)
        // 感测器算出的是「相对手机自然方向」的倾角（直立时 x≈0、y≈g；顺时针为正），
        // 横拿时要加上荧幕方向补偿，水平仪才会以目前的画面方向为准
        var roll = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat() + displayRotationDeg
        if (roll > 180f) roll -= 360f
        if (roll < -180f) roll += 360f
        overlayView.rollDegrees = roll
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
