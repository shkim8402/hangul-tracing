package com.example.hangul

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 화면 전체에 폭죽(방사형)과 색종이(떨어지는)를 그리는 투명 오버레이. 터치는 통과합니다. */
class ConfettiView(context: Context) : View(context) {

    private class P(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val size: Float,
        val color: Int,
        var rot: Float,
        val vrot: Float,
        var life: Float,
        val maxLife: Float,
        val kind: Int, // 0 = 폭죽 불꽃, 1 = 색종이
    )

    private val parts = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density
    private val rnd = Random(System.nanoTime())
    private val colors = intArrayOf(
        0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF69F0AE.toInt(),
        0xFF40C4FF.toInt(), 0xFFE040FB.toInt(), 0xFFFF9100.toInt(),
    )

    private var running = false
    private var startNs = 0L
    private var lastNs = 0L
    private var nextBurst = 0

    fun start() {
        parts.clear()
        running = true
        startNs = System.nanoTime()
        lastNs = startNs
        nextBurst = 0
        invalidate()
    }

    fun stop() {
        running = false
        parts.clear()
        invalidate()
    }

    private fun burst(cx: Float, cy: Float) {
        val base = colors[rnd.nextInt(colors.size)]
        for (i in 0 until 80) {
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val speed = (180f + rnd.nextFloat() * 420f) * d
            val color = if (rnd.nextInt(3) == 0) colors[rnd.nextInt(colors.size)] else base
            parts.add(
                P(
                    cx, cy, cos(ang) * speed, sin(ang) * speed,
                    (3f + rnd.nextFloat() * 4f) * d, color,
                    0f, 0f, 0f, 1.1f + rnd.nextFloat() * 0.8f, 0,
                )
            )
        }
    }

    private fun spawnConfetti() {
        parts.add(
            P(
                rnd.nextFloat() * width, -10f * d,
                (rnd.nextFloat() - 0.5f) * 120f * d, (120f + rnd.nextFloat() * 140f) * d,
                (6f + rnd.nextFloat() * 6f) * d, colors[rnd.nextInt(colors.size)],
                rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 720f,
                0f, 6f, 1,
            )
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!running && parts.isEmpty()) return

        val now = System.nanoTime()
        var dt = (now - lastNs) / 1_000_000_000f
        if (dt > 0.05f) dt = 0.05f
        lastNs = now
        val elapsed = (now - startNs) / 1_000_000_000f

        if (running) {
            while (nextBurst < FxTiming.POPS.size && elapsed >= FxTiming.POPS[nextBurst]) {
                burst(width * (0.2f + rnd.nextFloat() * 0.6f), height * (0.12f + rnd.nextFloat() * 0.35f))
                nextBurst++
            }
            if (elapsed < 2.8f) {
                repeat(2) { if (rnd.nextFloat() < 0.6f) spawnConfetti() }
            } else if (nextBurst >= FxTiming.POPS.size) {
                running = false
            }
        }

        val iter = parts.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.life += dt
            if (p.life >= p.maxLife || p.y > height + 40f * d) {
                iter.remove()
                continue
            }
            if (p.kind == 0) {
                p.vy += 600f * d * dt
                p.vx *= (1f - 1.5f * dt)
                p.vy *= (1f - 0.8f * dt)
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.vrot * dt

            if (p.kind == 0) {
                val alpha = ((1f - p.life / p.maxLife).coerceIn(0f, 1f) * 255f).toInt()
                paint.color = (p.color and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawCircle(p.x, p.y, p.size, paint)
            } else {
                paint.color = p.color
                canvas.save()
                canvas.translate(p.x, p.y)
                canvas.rotate(p.rot)
                canvas.drawRect(-p.size / 2f, -p.size / 4f, p.size / 2f, p.size / 4f, paint)
                canvas.restore()
            }
        }

        if (running || parts.isNotEmpty()) postInvalidateOnAnimation()
    }
}
