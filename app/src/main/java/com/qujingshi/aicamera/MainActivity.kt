package com.qujingshi.aicamera

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var btnGallery: ImageButton
    private lateinit var txtPoseName: TextView
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var camera: Camera? = null

    // ── 变焦 ──
    private lateinit var txtZoomRatio: TextView
    private lateinit var zoom1x: TextView
    private lateinit var zoom2x: TextView
    private lateinit var zoom3x: TextView
    private var minZoom = 1f
    private var maxZoom = 1f
    private val zoomStateObserver = Observer<ZoomState> { zs ->
        minZoom = zs.minZoomRatio
        maxZoom = zs.maxZoomRatio
        txtZoomRatio.text = "%.1f×".format(zs.zoomRatio)
        // 超出设备最大倍率的快捷按钮直接隐藏
        zoom2x.visibility = if (maxZoom >= 1.9f) View.VISIBLE else View.GONE
        zoom3x.visibility = if (maxZoom >= 2.9f) View.VISIBLE else View.GONE
        highlightZoomPreset(zs.zoomRatio)
    }

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

    // ── AI 自动取景：自己变焦＋拍照自动裁成最好构图 ──
    private var autoFraming = true
    private lateinit var btnAiFraming: TextView
    private var lastBestBox: RectF? = null
    private var lastManualZoomMs = 0L
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

        // AI 自动取景开关（左上）：开 = AI 自己变焦＋拍照自动裁成最好构图
        btnAiFraming = findViewById(R.id.btnAiFraming)
        btnAiFraming.setOnClickListener {
            autoFraming = !autoFraming
            updateAiFramingLabel()
        }
        updateAiFramingLabel()

        // 变焦：快捷倍率＋捏合缩放（OverlayView 把双指捏合的系数交过来）
        txtZoomRatio = findViewById(R.id.txtZoomRatio)
        zoom1x = findViewById(R.id.zoom1x)
        zoom2x = findViewById(R.id.zoom2x)
        zoom3x = findViewById(R.id.zoom3x)
        zoom1x.setOnClickListener { setZoom(1f) }
        zoom2x.setOnClickListener { setZoom(2f) }
        zoom3x.setOnClickListener { setZoom(3f) }
        overlayView.onPinchZoom = { factor ->
            lastManualZoomMs = SystemClock.uptimeMillis()
            val cam = camera
            val zs = cam?.cameraInfo?.zoomState?.value
            if (cam != null && zs != null) {
                cam.cameraControl.setZoomRatio(
                    (zs.zoomRatio * factor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio),
                )
            }
        }

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
                    lastBestBox = result.bestBox
                    // 自动取景开：推荐框有意义就一直高亮（它会真的动手）；关：只在需要动时高亮
                    val significant = OverlayView.isSignificantBox(result.bestBox)
                    overlayView.setAiFramingBox(
                        result.bestBox,
                        strong = if (autoFraming) significant
                        else result.direction != FramingAdvisor.Direction.STAY,
                        frame.width,
                        frame.height,
                    )
                    if (autoFraming) maybeAutoZoom(result)
                    speakDirection(result.direction)
                }
            }
        }
    }

    /**
     * 自动变焦：推荐框在画面中央附近、且明显小于当前取景时，自己拉近。
     * 每次只走 45% 的路程，平滑趋近；用户刚手动捏合过 4 秒内不动手。
     */
    private fun maybeAutoZoom(result: FramingAdvisor.Result) {
        if (SystemClock.uptimeMillis() - lastManualZoomMs < MANUAL_ZOOM_HOLD_MS) return
        if (result.bestScore - result.currentScore < AUTO_ZOOM_MIN_GAIN) return
        val box = result.bestBox
        val scale = max(box.width(), box.height())
        if (scale >= 0.88f) return
        if (abs(box.centerX() - 0.5f) > 0.15f || abs(box.centerY() - 0.5f) > 0.15f) return
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        val target = (zs.zoomRatio / scale).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
        if (target - zs.zoomRatio < 0.05f) return
        cam.cameraControl.setZoomRatio(zs.zoomRatio + (target - zs.zoomRatio) * 0.45f)
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
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
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
            camera?.cameraInfo?.zoomState?.removeObserver(zoomStateObserver)
            val cam = provider.bindToLifecycle(this, selector, preview, capture, analysis)
            camera = cam
            // 换镜头后倍率归 1，重新订阅这颗镜头的变焦范围
            cam.cameraControl.setZoomRatio(1f)
            cam.cameraInfo.zoomState.observe(this, zoomStateObserver)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setZoom(ratio: Float) {
        lastManualZoomMs = SystemClock.uptimeMillis()
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(minZoom, maxZoom))
    }

    private fun updateAiFramingLabel() {
        btnAiFraming.setText(
            if (autoFraming) R.string.ai_framing_on else R.string.ai_framing_off,
        )
        btnAiFraming.setTextColor(
            if (autoFraming) android.graphics.Color.rgb(255, 193, 7)
            else android.graphics.Color.rgb(160, 160, 160),
        )
    }

    /** 高亮当前最接近的快捷倍率 */
    private fun highlightZoomPreset(current: Float) {
        val presets = listOf(zoom1x to 1f, zoom2x to 2f, zoom3x to 3f)
        for ((view, ratio) in presets) {
            val active = kotlin.math.abs(current - ratio) < 0.05f
            view.setTextColor(
                if (active) android.graphics.Color.rgb(255, 193, 7)
                else android.graphics.Color.WHITE,
            )
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
                    val uri = result.savedUri
                    val box = lastBestBox
                    // AI 自动取景开：直接把照片裁成 AI 选的最好构图
                    if (autoFraming && uri != null && box != null &&
                        OverlayView.isSignificantBox(box)
                    ) {
                        applySmartCrop(uri, box)
                    } else {
                        Toast.makeText(this@MainActivity, R.string.msg_saved, Toast.LENGTH_SHORT)
                            .show()
                        uri?.let { showThumbnail(it) }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, R.string.msg_save_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            },
        )
    }

    /**
     * 智能裁剪：只解码 AI 框那块区域（BitmapRegionDecoder，不整图解码省内存），
     * 按 EXIF 方向把正规化坐标映射到存储像素，裁完直接写回原文件。
     * 分析帧和拍照都统一 16:9，同一变焦下视野一致，坐标可直接缩放映射。
     */
    private fun applySmartCrop(uri: Uri, box: RectF) {
        aiExecutor?.execute {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val orientation = ExifInterface(pfd.fileDescriptor)
                        .getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                    val decoder =
                        BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
                            ?: return@execute
                    val rect = mapBoxToStored(
                        box, decoder.width, decoder.height, orientation,
                    )
                    if (rect.width() < 16 || rect.height() < 16) {
                        decoder.recycle()
                        return@execute
                    }
                    val cropped = decoder.decodeRegion(
                        rect, BitmapFactory.Options(),
                    )
                    decoder.recycle()
                    contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    cropped.recycle()
                    // 裁后像素已是正向，EXIF 被清空即方向正常
                    runOnUiThread {
                        Toast.makeText(
                            this, R.string.msg_ai_cropped, Toast.LENGTH_SHORT,
                        ).show()
                        showThumbnail(uri)
                    }
                } ?: runOnUiThread {
                    Toast.makeText(this, R.string.msg_saved, Toast.LENGTH_SHORT).show()
                    showThumbnail(uri)
                }
            } catch (e: Exception) {
                Log.w(TAG, "smart crop failed, keep original", e)
                runOnUiThread {
                    Toast.makeText(this, R.string.msg_saved, Toast.LENGTH_SHORT).show()
                    showThumbnail(uri)
                }
            }
        }
    }

    /** 正规化坐标（正向画面）→ 存储像素坐标（含 EXIF 旋转映射） */
    private fun mapBoxToStored(box: RectF, sw: Int, sh: Int, orientation: Int): Rect {
        val swap = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        val uw = if (swap) sh else sw
        val uh = if (swap) sw else sh
        val l = (box.left * uw).toInt().coerceIn(0, uw)
        val t = (box.top * uh).toInt().coerceIn(0, uh)
        val r = (box.right * uw).toInt().coerceIn(0, uw)
        val b = (box.bottom * uh).toInt().coerceIn(0, uh)
        val rect = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_180 -> Rect(sw - r, sh - b, sw - l, sh - t)
            ExifInterface.ORIENTATION_ROTATE_90 -> Rect(t, sh - r, b, sh - l)
            ExifInterface.ORIENTATION_ROTATE_270 -> Rect(sw - b, l, sw - t, r)
            else -> Rect(l, t, r, b)
        }
        rect.intersect(0, 0, sw, sh)
        return rect
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

    private companion object {
        const val TAG = "Qujingshi"
        /** 用户手动变焦后这么久内，自动变焦不动手 */
        const val MANUAL_ZOOM_HOLD_MS = 4000L
        /** 自动变焦也要"赢得明显"才动手 */
        const val AUTO_ZOOM_MIN_GAIN = 0.08f
    }
}
