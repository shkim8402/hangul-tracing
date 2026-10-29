package com.example.hangul

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 따라쓰기 칸들을 주어진 공간 안에서 "가장 크게" 배치합니다.
 * 한 줄에 다 넣는 게 칸이 제일 크면 한 줄, 아니면 여러 줄(예: 3글자 → 2+1)로 나눕니다.
 * 읽는 순서가 자연스럽도록 줄 수가 늘 때마다 점수를 조금 깎아 가로 배치를 우선합니다.
 */
class BoxGrid(context: Context) : ViewGroup(context) {

    private val d = context.resources.displayMetrics.density
    private val vGap = (10 * d).toInt()
    private val maxCell = (340 * d).toInt()

    private var cell = 0
    private var perRow = 1
    private var rows = 1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var h = MeasureSpec.getSize(heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || h == 0) h = w

        val n = childCount
        cell = 0
        perRow = 1
        rows = 1
        if (n > 0) {
            var bestScore = -1f
            for (per in 1..n) {
                val r = (n + per - 1) / per
                val size = minOf(w / per, (h - vGap * (r - 1)) / r, maxCell)
                val score = size * (1f - 0.15f * (r - 1))
                if (score > bestScore) {
                    bestScore = score
                    cell = size
                    perRow = per
                    rows = r
                }
            }
        }
        cell = cell.coerceAtLeast(1)

        val spec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        for (i in 0 until n) getChildAt(i).measure(spec, spec)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = childCount
        if (n == 0) return
        val w = r - l
        val h = b - t
        val totalH = cell * rows + vGap * (rows - 1)
        var y = (h - totalH) / 2
        var i = 0
        for (row in 0 until rows) {
            val inRow = minOf(perRow, n - i)
            var x = (w - cell * inRow) / 2
            for (k in 0 until inRow) {
                val child: View = getChildAt(i)
                child.layout(x, y, x + cell, y + cell)
                x += cell
                i++
            }
            y += cell + vGap
        }
    }
}
