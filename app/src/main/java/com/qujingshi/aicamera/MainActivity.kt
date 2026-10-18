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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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

    /** 最近一张取景帧对应的人体关键点（正规化坐标），给 AI 取景（L2 密集候选）用 */
    @Volatile
    var latestPersonsNorm: List<List<FloatArray>> = emptyList()
        private set

    private lateinit var analysisExecutor: ExecutorService
    private var poseAnalyzer: PoseAnalyzer? = null

    /** -1 = 姿势引导关闭；其余是 PoseLibrary.POSES 的索引 */
    private var poseIndex = -1

    private var latestPhotoUri: Uri? = null

    // ── AI 取景建议（L2）＋ 语音指导 ──
    private lateinit var txtAiScore: TextView
    private var framingAdvisor: FramingAdvisor? = null
    private var voiceGuide: VoiceGuide? = null
    private var lastDirection: FramingAdvisor.Direction? = null
    private var aiExecutor: ExecutorService? = null
    private var aiBusy = false
    private val aiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val aiTick = object : Runnable {
        override fun run() {
            scoreCurrentFraming()
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

        analysisExecutor = Executors.newSingleThreadExecutor()
        poseAnalyzer = PoseAnalyzer(
            this,
            onResult = { landmarks, imgW, imgH ->
                runOnUiThread {
                    latestPersonsNorm = landmarks
                    overlayView.setDetectedPose(landmarks, imgW, imgH)
                }
            },
        )

        // AI 取景建议：模型载入失败（例如 assets 缺档）就停用，App 照常运作
        txtAiScore = findViewById(R.id.txtAiScore)
        runCatching { AestheticScorer(this) }.onSuccess { scorer ->
            framingAdvisor = FramingAdvisor(scorer)
        }
        if (framingAdvisor != null) {
            aiExecutor = Executors.newSingleThreadExecutor()
        }
        voiceGuide = VoiceGuide(this)

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
        if (framingAdvisor != null) aiHandler.postDelayed(aiTick, 2000)
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
        voiceGuide?.shutdown()
    }

    /** 每 2 秒抓最新影格，背景算美感分数与取景建议（L2：场景路由+密集候选+NIMA 重排） */
    private fun scoreCurrentFraming() {
        val advisor = framingAdvisor ?: return
        val frame = poseAnalyzer?.latestFrame ?: return
        if (aiBusy) return
        aiBusy = true
        aiExecutor?.execute {
            val result = runCatching { advisor.analyze(frame, latestPersonsNorm) }.getOrNull()
            runOnUiThread {
                aiBusy = false
                if (result != null) {
                    txtAiScore.visibility = android.view.View.VISIBLE
                    txtAiScore.text =
                        getString(R.string.ai_score_fmt).format(result.bestScore) +
                        getString(result.direction.textRes)
                    // AI 推荐取景框画出来：需要动时加亮＋箭头，稍好时淡淡地提示
                    overlayView.setAiFramingBox(
                        result.bestBox,
                        result.direction != FramingAdvisor.Direction.STAY,
                        frame.width,
                        frame.height,
                    )
                    speakDirection(result.direction)
                }
            }
        }
    }

    /** 方向变了才"说"，同一个方向不重复播报（VoiceGuide 内还有 2.5 秒间隔保护） */
    private fun speakDirection(direction: FramingAdvisor.Direction) {
        if (direction == lastDirection) return
        lastDirection = direction
        when (direction) {
            FramingAdvisor.Direction.LEFT -> voiceGuide?.speak("往左移一点")
            FramingAdvisor.Direction.RIGHT -> voiceGuide?.speak("往右移一点")
            FramingAdvisor.Direction.UP -> voiceGuide?.speak("把镜头抬高一点")
            FramingAdvisor.Direction.DOWN -> voiceGuide?.speak("把镜头放低一点")
            FramingAdvisor.Direction.CLOSER -> voiceGuide?.speak("靠近一点")
            FramingAdvisor.Direction.STAY -> voiceGuide?.speak("可以拍了")
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

            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, capture, analysis)
        }, ContextCompat.getMainExecutor(this))
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
