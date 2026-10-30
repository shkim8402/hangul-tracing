package com.example.hangul

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 글자 한 칸. 연한 회색 글자를 손가락으로 따라 쓰면 됩니다.
 *
 * 완료 조건 (모두 만족해야 함, 마지막 획까지 다 써야 통과):
 *  1) 글자 모양의 70% 이상을 덮었고
 *  2) 글자 밖으로 크게 벗어나지 않았고 (칸 전체 낙서 방지)
 *  3) 글자 위에 "획 굵기만큼의 안 덮인 덩어리"가 남아 있지 않을 것 (빼먹은 획 검출)
 *  4) 글자의 획(뼈대 가지)마다, 손가락이 지나간 칸이 충분히 배정되어 있을 것
 *     (판정 붓이 넓어서 옆 획에 같이 덮여 버린 "안 쓴 획"을 잡아내는 검사)
 *
 * 판정은 화면에 보이는 획이 아니라, 같은 궤적을 더 굵게(JUDGE_FRACTION) 그려 둔 숨은 층으로 합니다.
 * 그래서 손가락이 글자에서 꽤 벗어나도 봐주면서, 화면의 획은 가늘게 유지할 수 있어요.
 */
class WritingBox(context: Context, private val syllable: String) : View(context) {

    var onDone: (() -> Unit)? = null

    var done = false
        private set

    private val d = context.resources.displayMetrics.density

    // 화면에 보이는 가는 획
    private var strokeBmp: Bitmap? = null
    private var strokeCanvas: Canvas? = null

    // 판정 전용(화면에 안 보임): 같은 획을 예전 굵기로 그려 둔 층. 보이는 굵기와 판정을 분리합니다.
    private var judgeBmp: Bitmap? = null
    private var judgeCanvas: Canvas? = null

    private var glyphIdx = IntArray(0)          // 글자 모양 위의 샘플 점 (픽셀 인덱스)
    private var glyphGrid = BooleanArray(0)     // 3px 간격 격자에서 글자 위인 칸
    private var gridW = 0
    private var gridH = 0
    private var tolerance = BooleanArray(0)     // 글자 주변 허용 범위

    // 획별 검사용: 손가락이 그은 선분(칸 크기로 나눈 0~1 좌표)과, 격자 칸마다 "가장 가까운 획 번호"
    private var segs = FloatArray(1024)
    private var segCount = 0
    private var ownerMap = IntArray(0)          // 0 = 획 없음, 1.. = 획 번호
    private var branchSizes = IntArray(1)       // 획 번호별 뼈대 길이(칸 수)

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
    private val judgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val judgeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
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

        val oldJ = judgeBmp
        val bmpJ = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cJ = Canvas(bmpJ)
        if (oldJ != null) {
            cJ.drawBitmap(oldJ, null, Rect(0, 0, w, h), bitmapPaint)
            oldJ.recycle()
        }
        judgeBmp = bmpJ
        judgeCanvas = cJ

        glyphPaint.textSize = w * 0.82f
        val fm = glyphPaint.fontMetrics
        baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        inkPaint.strokeWidth = w * INK_FRACTION
        judgePaint.strokeWidth = w * JUDGE_FRACTION
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
        buildBranches()

        // 2) 글자를 두껍게 부풀린 "허용 범위"
        tmp.eraseColor(0)
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = w * TOLERANCE_FRACTION
        c.drawText(syllable, w / 2f, baseline, p)
        tmp.getPixels(px, 0, w, 0, 0, w, h)
        tolerance = BooleanArray(w * h) { (px[it] ushr 24) > 10 }
        tmp.recycle()
    }

    fun clear() {
        strokeBmp?.eraseColor(0)
        judgeBmp?.eraseColor(0)
        segCount = 0
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
                judgeCanvas?.drawCircle(e.x, e.y, judgePaint.strokeWidth / 2f, judgeDotPaint)
                addSeg(e.x, e.y, e.x, e.y)
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
        judgeCanvas?.drawLine(lastX, lastY, x, y, judgePaint)
        addSeg(lastX, lastY, x, y)
        lastX = x
        lastY = y
    }

    private fun evaluate() {
        val bmp = judgeBmp ?: return // 판정은 보이는 획이 아니라 판정용 층으로 합니다
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

        // 4) 아직 안 쓴 획이 있으면 미완성
        if (hasMissingStroke(w, h)) return

        done = true
        onDone?.invoke()
    }

    // ------------------------------------------------------------ 획별 검사 (빠진 획 검출)

    private fun addSeg(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (width <= 0 || height <= 0) return
        if ((segCount + 1) * 4 > segs.size) segs = segs.copyOf(segs.size * 2)
        val o = segCount * 4
        segs[o] = x0 / width
        segs[o + 1] = y0 / height
        segs[o + 2] = x1 / width
        segs[o + 3] = y1 / height
        segCount++
    }

    /** 8방향 이웃 중 true인 칸 수 (가장자리 칸은 부르지 않음) */
    private fun neighbors(a: BooleanArray, i: Int, gw: Int): Int {
        var n = 0
        if (a[i - gw - 1]) n++
        if (a[i - gw]) n++
        if (a[i - gw + 1]) n++
        if (a[i - 1]) n++
        if (a[i + 1]) n++
        if (a[i + gw - 1]) n++
        if (a[i + gw]) n++
        if (a[i + gw + 1]) n++
        return n
    }

    /** Zhang-Suen 세선화: 글자 모양을 1칸 두께의 뼈대로 줄입니다. */
    private fun thin(src: BooleanArray, gw: Int, gh: Int): BooleanArray {
        val a = src.copyOf()
        val rm = IntArray(gw * gh)
        var changed = true
        while (changed) {
            changed = false
            for (step in 0..1) {
                var rc = 0
                for (y in 1 until gh - 1) {
                    for (x in 1 until gw - 1) {
                        val i = y * gw + x
                        if (!a[i]) continue
                        val p2 = a[i - gw]
                        val p3 = a[i - gw + 1]
                        val p4 = a[i + 1]
                        val p5 = a[i + gw + 1]
                        val p6 = a[i + gw]
                        val p7 = a[i + gw - 1]
                        val p8 = a[i - 1]
                        val p9 = a[i - gw - 1]
                        var n = 0
                        if (p2) n++
                        if (p3) n++
                        if (p4) n++
                        if (p5) n++
                        if (p6) n++
                        if (p7) n++
                        if (p8) n++
                        if (p9) n++
                        if (n < 2 || n > 6) continue
                        var t = 0
                        if (!p2 && p3) t++
                        if (!p3 && p4) t++
                        if (!p4 && p5) t++
                        if (!p5 && p6) t++
                        if (!p6 && p7) t++
                        if (!p7 && p8) t++
                        if (!p8 && p9) t++
                        if (!p9 && p2) t++
                        if (t != 1) continue
                        if (step == 0) {
                            if (p2 && p4 && p6) continue
                            if (p4 && p6 && p8) continue
                        } else {
                            if (p2 && p4 && p8) continue
                            if (p2 && p6 && p8) continue
                        }
                        rm[rc++] = i
                    }
                }
                if (rc > 0) {
                    changed = true
                    for (k in 0 until rc) a[rm[k]] = false
                }
            }
        }
        return a
    }

    /**
     * 글자 뼈대를 갈림길에서 끊어 "획(가지)"들로 나누고,
     * 격자의 모든 칸에 "가장 가까운 획 번호"를 붙여 둡니다.
     */
    private fun buildBranches() {
        val gw = gridW
        val gh = gridH
        ownerMap = IntArray(0)
        branchSizes = IntArray(1)
        if (gw < 5 || gh < 5) return

        val skel = thin(glyphGrid, gw, gh)

        // 갈림길(이웃 3개 이상)과 그 주변 칸을 지워서 가지들로 분리
        val rest = skel.copyOf()
        for (y in 1 until gh - 1) {
            for (x in 1 until gw - 1) {
                val i = y * gw + x
                if (skel[i] && neighbors(skel, i, gw) >= 3) {
                    for (dy in -1..1) for (dx in -1..1) rest[i + dy * gw + dx] = false
                }
            }
        }

        // 연결된 덩어리마다 번호 붙이기 (너무 짧은 가지는 무시)
        val label = IntArray(gw * gh)
        val cells = IntArray(gw * gh)
        val minCells = maxOf(4, Math.round(width * MIN_BRANCH_FRACTION / 3f))
        val sizes = ArrayList<Int>()
        sizes.add(0)
        var next = 1
        for (start in rest.indices) {
            if (!rest[start] || label[start] != 0) continue
            var n = 0
            cells[n++] = start
            label[start] = next
            var head = 0
            while (head < n) {
                val c = cells[head++]
                val cx = c % gw
                val cy = c / gw
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                        val ni = ny * gw + nx
                        if (rest[ni] && label[ni] == 0) {
                            label[ni] = next
                            cells[n++] = ni
                        }
                    }
                }
            }
            if (n >= minCells) {
                sizes.add(n)
                next++
            } else {
                for (k in 0 until n) label[cells[k]] = -1
            }
        }
        if (next == 1) return

        // 모든 칸에 가장 가까운 획 번호 붙이기 (여러 곳에서 동시에 퍼지는 BFS)
        val owner = IntArray(gw * gh)
        val queue = IntArray(gw * gh)
        var qh = 0
        var qt = 0
        for (i in label.indices) {
            if (label[i] > 0) {
                owner[i] = label[i]
                queue[qt++] = i
            }
        }
        while (qh < qt) {
            val c = queue[qh++]
            val cx = c % gw
            val cy = c / gw
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val ni = ny * gw + nx
                    if (owner[ni] == 0) {
                        owner[ni] = owner[c]
                        queue[qt++] = ni
                    }
                }
            }
        }
        ownerMap = owner
        branchSizes = sizes.toIntArray()
    }

    /**
     * 손가락이 지나간 칸들을 "가장 가까운 획"에 배정해서,
     * 획 하나라도 길이의 일정 비율(OWN_NEEDED)만큼 지나가지 않았다면 true (= 빠진 획이 있음)
     */
    private fun hasMissingStroke(w: Int, h: Int): Boolean {
        if (ownerMap.size != gridW * gridH || branchSizes.size <= 1) return false

        val path = BooleanArray(gridW * gridH)
        for (s in 0 until segCount) {
            val o = s * 4
            val x0 = segs[o] * w
            val y0 = segs[o + 1] * h
            val x1 = segs[o + 2] * w
            val y1 = segs[o + 3] * h
            val steps = maxOf(1, (maxOf(abs(x1 - x0), abs(y1 - y0)) / 1.5f).toInt())
            for (k in 0..steps) {
                val t = k.toFloat() / steps
                val gx = ((x0 + (x1 - x0) * t) / 3f).toInt().coerceIn(0, gridW - 1)
                val gy = ((y0 + (y1 - y0) * t) / 3f).toInt().coerceIn(0, gridH - 1)
                path[gy * gridW + gx] = true
            }
        }

        val cnt = IntArray(branchSizes.size)
        for (i in path.indices) {
            if (path[i]) {
                val o = ownerMap[i]
                if (o > 0) cnt[o]++
            }
        }
        for (k in 1 until branchSizes.size) {
            if (cnt[k] < OWN_NEEDED * branchSizes[k]) return true
        }
        return false
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
        /** 화면에 보이는 획 굵기 (칸 너비 대비) */
        const val INK_FRACTION = 0.05f

        /** 판정에 쓰는 획 굵기 (칸 너비 대비). 시뮬레이션으로 맞춘 값이라 보이는 굵기와 상관없이 고정 */
        const val JUDGE_FRACTION = 0.24f

        /** 허용 범위(글자 주변 여유) 굵기 (칸 너비 대비). 손가락이 글자 밖으로 조금 벗어나도 봐주는 폭 */
        const val TOLERANCE_FRACTION = 0.34f

        /** 글자 모양의 몇 %를 덮어야 완료인지 (빼먹은 획은 아래 블록 검사가 따로 잡습니다) */
        const val COVERAGE_NEEDED = 0.70f

        /** 글자 밖으로 벗어난 획의 허용 비율 (칸 전체 낙서를 걸러내는 기준) */
        const val MAX_OUTSIDE = 0.15f

        /** 획별 검사: 이 비율보다 짧은 뼈대 가지는 획으로 안 침 (칸 너비 대비) */
        const val MIN_BRANCH_FRACTION = 0.045f

        /** 획별 검사: 획마다 손가락이 지나간 칸이 뼈대 길이의 이 비율 이상 배정되어야 함 */
        const val OWN_NEEDED = 0.40f

        /** 안 덮인 블록 검사 크기 (칸 너비 대비). 획 굵기(약 0.06)보다 조금 작은 값 */
        const val BLOCK_FRACTION = 0.053f
    }
}
