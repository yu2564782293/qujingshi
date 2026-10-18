package com.qujingshi.aicamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 取景辅助层：三分构图虚线、水平仪、侦测到的人体骨架、推荐姿势虚线人形、构图引导提示。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 手机侧倾角（度）。0 = 水平；由 MainActivity 的感测器喂进来。 */
    var rollDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** 目前选择的推荐姿势；null = 姿势引导关闭 */
    var guidePose: GuidePose? = null
        set(value) {
            field = value
            invalidate()
        }

    enum class GuideMode {
        /** 移镜头：人不动，虚线人形贴著人画，构图箭头引导拿相机的人 */
        MOVE_CAMERA,

        /** 移人：取景不动，虚线人形画在画面最佳位置，请被拍的人走进去 */
        MOVE_SUBJECT,
    }

    var guideMode: GuideMode = GuideMode.MOVE_CAMERA
        set(value) {
            field = value
            subjectAnchorLeft = null
            invalidate()
        }

    /** 移人模式选定的三分线（左/右），带迟滞避免人跨过中线时目标跳来跳去 */
    private var subjectAnchorLeft: Boolean? = null

    // 跨帧追踪＋主体判定（路人过滤、点击锁定）
    private val tracker = SubjectTracker()
    private var subjects: List<SubjectTracker.Track> = emptyList()
    private val advisor = CompositionAdvisor()
    private var advice: CompositionAdvisor.Advice? = null

    /** AI 推荐取景框（view 坐标）；null = 不画 */
    private var aiBoxView: RectF? = null
    private var aiBoxStrong: Boolean = false

    /**
     * AI 取景建议的框（正规化坐标，相对整帧）。与全帧几乎重合时不画，避免无信息量。
     * strong=true 时加亮并从画面中心画箭头指向推荐框。
     */
    fun setAiFramingBox(boxNorm: RectF?, strong: Boolean, imgW: Int, imgH: Int) {
        aiBoxView = if (boxNorm == null || imgW <= 0 || imgH <= 0) {
            null
        } else {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) {
                null
            } else if (boxNorm.width() > 0.95f && boxNorm.height() > 0.95f &&
                abs(boxNorm.centerX() - 0.5f) < 0.03f && abs(boxNorm.centerY() - 0.5f) < 0.03f
            ) {
                null
            } else {
                // 与 setDetectedPose 同一套 FILL_CENTER 映射
                val scale = max(w / imgW, h / imgH)
                val dx = (w - imgW * scale) / 2f
                val dy = (h - imgH * scale) / 2f
                RectF(
                    boxNorm.left * imgW * scale + dx,
                    boxNorm.top * imgH * scale + dy,
                    boxNorm.right * imgW * scale + dx,
                    boxNorm.bottom * imgH * scale + dy,
                )
            }
        }
        aiBoxStrong = strong && aiBoxView != null
        invalidate()
    }

    /**
     * MediaPipe 结果进来：把正规化座标映到 view 座标（PreviewView 预设 FILL_CENTER
     * 置中裁切，这里用同一套缩放），交给 SubjectTracker 判定主体后喂构图规则引擎。
     */
    fun setDetectedPose(persons: List<List<FloatArray>>, imgW: Int, imgH: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        subjects = if (persons.isNotEmpty() && imgW > 0 && imgH > 0 && w > 0 && h > 0) {
            val scale = max(w / imgW, h / imgH)
            val dx = (w - imgW * scale) / 2f
            val dy = (h - imgH * scale) / 2f
            val mapped = persons.map { person ->
                person.map {
                    floatArrayOf(it[0] * imgW * scale + dx, it[1] * imgH * scale + dy, it[2])
                }
            }
            tracker.update(mapped, w, h)
        } else {
            tracker.update(emptyList(), w, h)
        }
        advice = advisor.update(subjects.map { it.pts }.ifEmpty { null }, w, h)
        invalidate()
    }

    /** 切换镜头时呼叫：旧的追踪全部作废 */
    fun resetTracking() {
        tracker.reset()
        subjects = emptyList()
        invalidate()
    }

    /** 点击：点到人 → 锁定为主体；再点同一人或点空白 → 解除 */
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = hypot(
                    (event.x - touchDownX).toDouble(),
                    (event.y - touchDownY).toDouble(),
                ).toFloat()
                if (moved < 40f) {
                    when (tracker.toggleLockAt(event.x, event.y)) {
                        true -> Toast.makeText(
                            context, R.string.msg_subject_locked, Toast.LENGTH_SHORT,
                        ).show()
                        false -> Toast.makeText(
                            context, R.string.msg_subject_unlocked, Toast.LENGTH_SHORT,
                        ).show()
                        null -> Unit
                    }
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private var touchDownX = 0f
    private var touchDownY = 0f

    // 水平仪在 ±这个角度以内视为「已水平」，变绿
    private val levelToleranceDeg = 1.5f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val levelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 120, 230, 130)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 120, 230, 130)
        style = Paint.Style.FILL
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 102, 217, 255)
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(22f, 16f), 0f)
    }

    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 102, 217, 255)
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 193, 7)
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 193, 7)
        style = Paint.Style.FILL
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 193, 7)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val adviceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }

    private val adviceBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val aiBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 193, 7)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(26f, 18f), 0f)
    }

    private val aiBoxStrongPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 193, 7)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(26f, 18f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        drawThirdsGrid(canvas, w, h)
        val personBox = drawDetectedSkeleton(canvas)
        drawGhostPose(canvas, w, h, personBox)
        drawAdvice(canvas, w, h)
        drawAiBox(canvas, w, h)
        drawLevelIndicator(canvas, w, h)
    }

    private fun drawThirdsGrid(canvas: Canvas, w: Float, h: Float) {
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)
    }

    /** 画所有主体的骨架与锁定框；回传姿势虚线人形的锚定外框（锁定者优先，否则最大主体）。 */
    private fun drawDetectedSkeleton(canvas: Canvas): FloatArray? {
        var anchorBox: FloatArray? = null
        var anchorH = 0f
        for (t in subjects) {
            val pts = t.pts
            for ((a, b) in BODY_EDGES) {
                canvas.drawLine(pts[a][0], pts[a][1], pts[b][0], pts[b][1], skeletonPaint)
            }
            for (i in BODY_JOINTS) {
                canvas.drawCircle(pts[i][0], pts[i][1], 7f, jointPaint)
            }
            if (t.height > anchorH) {
                anchorH = t.height
                anchorBox = t.bbox
            }
        }

        // 锁定框：四个角的青色括号
        tracker.lockedTrack()?.let { locked ->
            drawLockBrackets(canvas, locked.bbox)
            anchorBox = locked.bbox
        }
        return anchorBox
    }

    private fun drawLockBrackets(canvas: Canvas, b: FloatArray) {
        val m = (b[3] - b[1]) * 0.06f
        val l = b[0] - m; val t = b[1] - m; val r = b[2] + m; val btm = b[3] + m
        val seg = min(r - l, btm - t) * 0.22f
        val p = lockPaint
        // 左上、右上、左下、右下
        canvas.drawLine(l, t, l + seg, t, p); canvas.drawLine(l, t, l, t + seg, p)
        canvas.drawLine(r, t, r - seg, t, p); canvas.drawLine(r, t, r, t + seg, p)
        canvas.drawLine(l, btm, l + seg, btm, p); canvas.drawLine(l, btm, l, btm - seg, p)
        canvas.drawLine(r, btm, r - seg, btm, p); canvas.drawLine(r, btm, r, btm - seg, p)
    }

    /** 推荐姿势虚线人形：有人就贴齐人物外框，没人就放画面中央。 */
    private fun drawGhostPose(canvas: Canvas, w: Float, h: Float, personBox: FloatArray?) {
        val pose = guidePose ?: return

        var pMinX = Float.MAX_VALUE; var pMinY = Float.MAX_VALUE
        var pMaxX = Float.MIN_VALUE; var pMaxY = Float.MIN_VALUE
        for ((x, y) in pose.points) {
            pMinX = min(pMinX, x); pMaxX = max(pMaxX, x)
            pMinY = min(pMinY, y); pMaxY = max(pMaxY, y)
        }
        val poseH = max(0.01f, pMaxY - pMinY)

        val targetH: Float
        val centerX: Float
        val bottomY: Float
        var aligned = false
        if (personBox != null) {
            targetH = (personBox[3] - personBox[1]).coerceAtLeast(h * 0.2f)
            bottomY = personBox[3]
            val personCx = (personBox[0] + personBox[2]) / 2f
            if (guideMode == GuideMode.MOVE_SUBJECT) {
                // 移人模式：目标放最近的三分线（带迟滞），同深度同身高，等人走进来
                when {
                    subjectAnchorLeft == null -> subjectAnchorLeft = personCx < w / 2f
                    subjectAnchorLeft == true && personCx > w * 0.58f -> subjectAnchorLeft = false
                    subjectAnchorLeft == false && personCx < w * 0.42f -> subjectAnchorLeft = true
                }
                centerX = if (subjectAnchorLeft == true) w / 3f else w * 2f / 3f
                aligned = kotlin.math.abs(personCx - centerX) < w * 0.04f
            } else {
                centerX = personCx
            }
        } else {
            targetH = h * 0.6f
            centerX = w / 2f
            bottomY = h / 2f + targetH / 2f
        }
        // 移人模式下人走进虚线就变绿
        ghostPaint.color = if (aligned) {
            Color.argb(235, 105, 240, 130)
        } else {
            Color.argb(235, 102, 217, 255)
        }
        val s = targetH / poseH
        fun gx(i: Int) = centerX + (pose.points[i].first - (pMinX + pMaxX) / 2f) * s
        fun gy(i: Int) = bottomY - (pMaxY - pose.points[i].second) * s

        for ((a, b) in PoseLibrary.EDGES) {
            canvas.drawLine(gx(a), gy(a), gx(b), gy(b), ghostPaint)
        }
        val headR = hypot(
            (gx(PoseLibrary.HEAD) - gx(PoseLibrary.NECK)).toDouble(),
            (gy(PoseLibrary.HEAD) - gy(PoseLibrary.NECK)).toDouble(),
        ).toFloat() * 0.62f
        canvas.drawCircle(gx(PoseLibrary.HEAD), gy(PoseLibrary.HEAD), headR, ghostPaint)
    }

    /** 构图引导：提示文字泡泡 + 引导箭头 + 目标点 */
    private fun drawAdvice(canvas: Canvas, w: Float, h: Float) {
        val adv = advice ?: return
        val good = adv.hint == CompositionAdvisor.Hint.GOOD

        // 箭头与目标圈
        val from = adv.arrowFrom
        val to = adv.arrowTo
        if (from != null && to != null) {
            drawArrow(canvas, from.x, from.y, to.x, to.y)
            if (adv.hint == CompositionAdvisor.Hint.MOVE_TO_THIRD ||
                adv.hint == CompositionAdvisor.Hint.EYE_LINE
            ) {
                canvas.drawCircle(to.x, to.y, 20f, targetPaint)
            }
        }

        // 文字泡泡（构图 OK 时缩小、变绿）
        val text = context.getString(adv.hint.textRes)
        adviceTextPaint.textSize = if (good) 36f else 42f
        adviceBgPaint.color = if (good) Color.argb(170, 27, 94, 32) else Color.argb(170, 0, 0, 0)

        val textW = adviceTextPaint.measureText(text)
        val cx = w / 2f
        val cy = h * 0.185f
        val padX = 28f
        val padY = 20f
        val fm = adviceTextPaint.fontMetrics
        val rect = RectF(
            cx - textW / 2f - padX,
            cy + fm.top - padY,
            cx + textW / 2f + padX,
            cy + fm.bottom + padY,
        )
        canvas.drawRoundRect(rect, 26f, 26f, adviceBgPaint)
        canvas.drawText(text, cx, cy, adviceTextPaint)
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val len = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
        if (len < 30f) return
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()).toFloat()
        // 线留一点空间给箭头头部
        val headLen = 34f
        val ex = x2 - headLen * 0.6f * cos(angle)
        val ey = y2 - headLen * 0.6f * sin(angle)
        canvas.drawLine(x1, y1, ex, ey, arrowPaint)

        val path = Path()
        path.moveTo(x2, y2)
        path.lineTo(
            x2 - headLen * cos(angle - ARROW_SPREAD),
            y2 - headLen * sin(angle - ARROW_SPREAD),
        )
        path.lineTo(
            x2 - headLen * cos(angle + ARROW_SPREAD),
            y2 - headLen * sin(angle + ARROW_SPREAD),
        )
        path.close()
        canvas.drawPath(path, arrowHeadPaint)
    }

    /** AI 推荐取景框：虚线框标出"这样拍更好"的位置，需要动时从中心画箭头指过去 */
    private fun drawAiBox(canvas: Canvas, w: Float, h: Float) {
        val b = aiBoxView ?: return
        canvas.drawRoundRect(b, 18f, 18f, if (aiBoxStrong) aiBoxStrongPaint else aiBoxPaint)
        if (aiBoxStrong) {
            drawArrow(canvas, w / 2f, h / 2f, b.centerX(), b.centerY())
        }
    }

    private fun drawLevelIndicator(canvas: Canvas, w: Float, h: Float) {
        val level = abs(rollDegrees) <= levelToleranceDeg
        levelPaint.color = if (level) Color.rgb(76, 217, 100) else Color.argb(230, 255, 214, 10)

        val cx = w / 2f
        val cy = h / 2f
        val half = w * 0.16f

        canvas.save()
        // 手机顺时针倾斜 θ 时，世界水平线在画面上看起来逆时针转 θ
        canvas.rotate(-rollDegrees, cx, cy)
        canvas.drawLine(cx - half, cy, cx - half * 0.35f, cy, levelPaint)
        canvas.drawLine(cx + half * 0.35f, cy, cx + half, cy, levelPaint)
        if (level) {
            canvas.drawLine(cx - half * 0.35f, cy, cx + half * 0.35f, cy, levelPaint)
        }
        canvas.restore()

        if (!level) {
            canvas.drawText("%+.0f°".format(rollDegrees), cx, cy - half * 0.45f, levelTextPaint)
        }
    }

    private companion object {
        const val ARROW_SPREAD = 0.5f // 弧度，箭头开角的一半

        /** MediaPipe 33 点模型的躯干＋四肢连线（脸部细节不画） */
        val BODY_EDGES = listOf(
            11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
            11 to 23, 12 to 24, 23 to 24,
            23 to 25, 25 to 27, 24 to 26, 26 to 28,
        )
        val BODY_JOINTS = listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

        /** 外框计算含鼻子（0）跟脚跟（29,30），高度才会涵盖头到脚 */
        val BODY_BBOX_POINTS = listOf(0, 11, 12, 15, 16, 23, 24, 27, 28, 29, 30)
    }
}
