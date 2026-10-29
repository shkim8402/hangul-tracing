package com.example.hangul

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * 글자 한 칸. 연한 회색 글자를 손가락으로 따라 쓰면 됩니다.
 *
 * 완료 조건 (모두 만족해야 함, 마지막 획까지 다 써야 통과):
 *  1) 글자 모양의 85% 이상을 덮었고
 *  2) 글자 밖으로 크게 벗어나지 않았고 (칸 전체 낙서 방지)
 *  3) 글자 위에 "획 굵기만큼의 안 덮인 덩어리"가 남아 있지 않을 것 (빼먹은 획 검출)
 */
class WritingBox(context: Context, private val syllable: String) : View(context) {

    var onDone: (() -> Unit)? = null

    var done = false
        private set

    private val d = context.resources.displayMetrics.density

    private var strokeBmp: Bitmap? = null
    private var strokeCanvas: Canvas? = null
    private var glyphIdx = IntArray(0)          // 글자 모양 위의 샘플 점 (픽셀 인덱스)
    private var glyphGrid = BooleanArray(0)     // 3px 간격 격자에서 글자 위인 칸
    private var gridW = 0
    private var gridH = 0
    private var tolerance = BooleanArray(0)     // 글자 주변 허용 범위
    private var baseline = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF9E9E9E.toInt()
        strokeWidth = 2f * d
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE3E3E3.toInt()
        strokeWidth = 1.5f * d
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD5D5D5.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7043.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7043.toInt()
        style = Paint.Style.FILL
    }
    private val okFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4CAF50.toInt() }
    private val okLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * d
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // 화면 회전 등으로 크기가 바뀌어도 이미 쓴 획은 크기에 맞춰 유지
        val old = strokeBmp
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (old != null) {
            c.drawBitmap(old, null, Rect(0, 0, w, h), bitmapPaint)
            old.recycle()
        }
        strokeBmp = bmp
        strokeCanvas = c

        glyphPaint.textSize = w * 0.82f
        val fm = glyphPaint.fontMetrics
        baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        inkPaint.strokeWidth = w * 0.12f
        buildMasks(w, h)
    }

    private fun buildMasks(w: Int, h: Int) {
        val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(tmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = glyphPaint.textSize
            textAlign = Paint.Align.CENTER
            typeface = glyphPaint.typeface
        }
        val px = IntArray(w * h)

        // 1) 글자 모양 그대로 (3px 간격으로 샘플링)
        c.drawText(syllable, w / 2f, baseline, p)
        tmp.getPixels(px, 0, w, 0, 0, w, h)
        gridW = (w + 2) / 3
        gridH = (h + 2) / 3
        glyphGrid = BooleanArray(gridW * gridH)
        val list = ArrayList<Int>()
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val i = (gy * 3) * w + gx * 3
                if ((px[i] ushr 24) > 128) {
                    list.add(i)
                    glyphGrid[gy * gridW + gx] = true
                }
            }
        }
        glyphIdx = list.toIntArray()

        // 2) 글자를 두껍게 부풀린 "허용 범위"
        tmp.eraseColor(0)
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = w * 0.22f
        c.drawText(syllable, w / 2f, baseline, p)
        tmp.getPixels(px, 0, w, 0, 0, w, h)
        tolerance = BooleanArray(w * h) { (px[it] ushr 24) > 10 }
        tmp.recycle()
    }

    fun clear() {
        strokeBmp?.eraseColor(0)
        done = false
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (done) return true
        val c = strokeCanvas ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                c.drawCircle(e.x, e.y, inkPaint.strokeWidth / 2f, dotPaint)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) {
                    segment(c, e.getHistoricalX(i), e.getHistoricalY(i))
                }
                segment(c, e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                segment(c, e.x, e.y)
                evaluate()
                invalidate()
            }
        }
        return true
    }

    private fun segment(c: Canvas, x: Float, y: Float) {
        c.drawLine(lastX, lastY, x, y, inkPaint)
        lastX = x
        lastY = y
    }

    private fun evaluate() {
        val bmp = strokeBmp ?: return
        val w = bmp.width
        val h = bmp.height
        if (glyphIdx.isEmpty() || tolerance.size != w * h) return

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // 1) 글자 모양을 얼마나 덮었나
        var covered = 0
        for (i in glyphIdx) if ((px[i] ushr 24) > 40) covered++
        val coverage = covered.toFloat() / glyphIdx.size
        if (coverage < COVERAGE_NEEDED) return

        // 2) 글자 밖으로 벗어난 정도
        var total = 0
        var outside = 0
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val i = (gy * 3) * w + gx * 3
                if ((px[i] ushr 24) > 40) {
                    total++
                    if (!tolerance[i]) outside++
                }
            }
        }
        val messy = if (total == 0) 1f else outside.toFloat() / total
        if (messy > MAX_OUTSIDE) return

        // 3) 안 덮인 획 조각이 남아 있으면 아직 미완성
        if (hasUncoveredBlock(px, w)) return

        done = true
        onDone?.invoke()
    }

    /** 글자 위에서 정사각형 블록 하나가 통째로 안 덮여 있는 곳이 있는지 (적분 이미지로 검사) */
    private fun hasUncoveredBlock(px: IntArray, w: Int): Boolean {
        val gw = gridW
        val gh = gridH
        val k = maxOf(5, Math.round(w * BLOCK_FRACTION / 3f)) / 2
        val win = 2 * k + 1
        if (gw < win || gh < win) return false

        val stride = gw + 1
        val ps = IntArray(stride * (gh + 1))
        for (gy in 0 until gh) {
            var rowSum = 0
            for (gx in 0 until gw) {
                val bad = if (glyphGrid[gy * gw + gx] &&
                    (px[(gy * 3) * w + gx * 3] ushr 24) <= 40
                ) 1 else 0
                rowSum += bad
                ps[(gy + 1) * stride + gx + 1] = ps[gy * stride + gx + 1] + rowSum
            }
        }
        val area = win * win
        for (gy in 0..(gh - win)) {
            for (gx in 0..(gw - win)) {
                val s = ps[(gy + win) * stride + gx + win] -
                    ps[gy * stride + gx + win] -
                    ps[(gy + win) * stride + gx] +
                    ps[gy * stride + gx]
                if (s == area) return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 10f * d
        val inset = 1f * d

        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, bgPaint)

        // 가운데 점선 십자 가이드
        var t = 6f * d
        while (t < h - 6f * d) {
            canvas.drawLine(w / 2f, t, w / 2f, t + 8f * d, guidePaint)
            t += 16f * d
        }
        t = 6f * d
        while (t < w - 6f * d) {
            canvas.drawLine(t, h / 2f, t + 8f * d, h / 2f, guidePaint)
            t += 16f * d
        }

        canvas.drawText(syllable, w / 2f, baseline, glyphPaint)
        strokeBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        borderPaint.color = if (done) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
        borderPaint.strokeWidth = if (done) 4f * d else 2f * d
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, borderPaint)

        if (done) {
            val cx = w - 22f * d
            val cy = 22f * d
            canvas.drawCircle(cx, cy, 14f * d, okFill)
            canvas.drawLine(cx - 6f * d, cy, cx - 2f * d, cy + 5f * d, okLine)
            canvas.drawLine(cx - 2f * d, cy + 5f * d, cx + 7f * d, cy - 5f * d, okLine)
        }
    }

    companion object {
        /** 글자 모양의 몇 %를 덮어야 완료인지 */
        const val COVERAGE_NEEDED = 0.85f

        /** 글자 밖으로 벗어난 획의 허용 비율 (칸 전체 낙서를 걸러내는 기준) */
        const val MAX_OUTSIDE = 0.18f

        /** 안 덮인 블록 검사 크기 (칸 너비 대비). 획 굵기(약 0.06)보다 조금 작은 값 */
        const val BLOCK_FRACTION = 0.053f
    }
}
